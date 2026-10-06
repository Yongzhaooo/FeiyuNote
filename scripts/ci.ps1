<#
.SYNOPSIS
  Local CI/CD for 肥鱼笔记: build, unit tests, emulator instrumented + UI tests, APK artifact.

.EXAMPLE
  pwsh scripts/ci.ps1          # fast: unit tests + debug/test APK builds + artifact
  pwsh scripts/ci.ps1 -Full    # fast + all instrumented/UI tests on the emulator

  A passing -Full run records the tested tree in build/ci/full-passed; the pre-push hook skips
  pushes whose commits have exactly that tree.
#>
param(
    [switch]$Full,
    [string]$Serial = 'emulator-5556',
    [string]$Avd = 'Feiyu_CI_API36'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This pipeline only targets an explicitly named Android emulator, never a physical phone.' }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Reuse the machine's toolchain; fall back to the standard install locations.
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'JDK 21 is missing. Set JAVA_HOME to a persistent JDK installation.' }
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-37.0\android.jar")) {
    throw 'Android SDK 37.0 is missing. Run pwsh -File scripts/setup-sdk.ps1 (add -WithEmulator to restore the emulator tools/image).'
}
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$logDir = Join-Path $root 'build\ci'
New-Item -ItemType Directory -Force $logDir | Out-Null

# A full pass is stamped with the git tree of the files it tested (tracked + untracked, minus ignored),
# so a run on uncommitted work still counts once exactly that content is committed.
$passStamp = Join-Path $logDir 'full-passed'
function Get-WorkTreeId {
    $index = Join-Path $logDir 'stamp.index'
    Copy-Item (git rev-parse --path-format=absolute --git-path index) $index -Force
    $env:GIT_INDEX_FILE = $index
    try {
        git add -A 2>$null
        git write-tree
    } finally {
        Remove-Item Env:\GIT_INDEX_FILE
        Remove-Item $index -Force -ErrorAction SilentlyContinue
    }
}
if ($Full) { $treeAtStart = Get-WorkTreeId }
if ($Full) { $python = (Get-Command python -ErrorAction Stop).Source }

function Invoke-Step([string]$Name, [scriptblock]$Body) {
    Write-Host "==> $Name" -ForegroundColor Cyan
    $started = Get-Date
    & $Body
    if ($LASTEXITCODE -ne 0) { throw "$Name failed (exit $LASTEXITCODE)" }
    Write-Host ("    ok in {0:N0}s" -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
}

Invoke-Step 'Gradle: unit tests, app and test APKs' {
    & .\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain *> (Join-Path $logDir 'gradle.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $logDir 'gradle.log') -Tail 40 }
}

if ($Full) {
    Invoke-Step "Emulator $Serial" {
        $present = (& $adb devices) -match "^$Serial\s+(device|offline)"
        if (-not $present) {
            Write-Host "    starting $Avd headless"
            Start-Process -FilePath $emulator -ArgumentList '-avd', $Avd, '-port', $Serial.Substring(9), '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot-save' -WindowStyle Hidden
        }
        $deadline = (Get-Date).AddMinutes(3)
        while ($true) {
            $probeLog = Join-Path $logDir 'boot-status.txt'
            $probe = Start-Process -FilePath $adb -ArgumentList '-s', $Serial, 'shell', 'getprop', 'sys.boot_completed' -PassThru -WindowStyle Hidden -RedirectStandardOutput $probeLog -RedirectStandardError (Join-Path $logDir 'boot-error.txt')
            if (-not $probe.WaitForExit(10000)) {
                $probe.Kill()
                throw "emulator $Serial ADB shell is unresponsive; restart the existing AVD without wiping data, then rerun CI."
            }
            if ((Get-Content $probeLog -Raw) -match '^\s*1\s*$') { break }
            if ((Get-Date) -gt $deadline) { throw "emulator $Serial did not finish booting" }
            Start-Sleep -Seconds 2
        }
        & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
        & $adb -s $Serial shell svc power stayon true | Out-Null
        & $adb -s $Serial shell wm dismiss-keyguard | Out-Null
        $global:LASTEXITCODE = 0
    }

    Invoke-Step 'Install app and test APKs (emulator only)' {
        & $adb -s $Serial install -r -t app\build\outputs\apk\debug\app-debug.apk | Out-Null
        if ($LASTEXITCODE -eq 0) { & $adb -s $Serial install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk | Out-Null }
    }

    Invoke-Step 'Instrumented + UI tests' {
        $log = Join-Path $logDir 'instrumented.log'
        & $adb -s $Serial shell am instrument -w -r com.feiyu.notes.test/androidx.test.runner.AndroidJUnitRunner *> $log
        if ($LASTEXITCODE -ne 0) { throw "ADB test command failed (exit $LASTEXITCODE); see $log" }
        # ADB may exit 0 on test failures; validate terminal statuses and required suites too.
        & $python scripts/parse-test-runner.py --log $log --classes 'com.feiyu.notes.data.NotebookStoreTest,com.feiyu.notes.study.GeneratorTest,com.feiyu.notes.ui.UiFlowTest'
        if ($LASTEXITCODE -ne 0) { Get-Content $log -Encoding utf8 | Where-Object { $_ -notmatch '^\s+at ' } | Select-Object -Last 40 }
    }
    Invoke-Step 'UI screenshots' {
        $screenshots = Join-Path $logDir 'screenshots'
        New-Item -ItemType Directory -Force $screenshots | Out-Null
        foreach ($name in @('chat-en-phone', 'chat-zh-phone', 'math-chat-phone', 'multi-photo-phone', 'support-phone', 'course-review-dialog', 'course-review-list', 'course-review-note-add', 'course-review-source-deleted')) {
            & $adb -s $Serial pull "/sdcard/Android/data/com.feiyu.notes/files/ui-evidence/$name.png" (Join-Path $screenshots "$name.png")
            if ($LASTEXITCODE -ne 0) { throw "Missing UI screenshot: $name" }
        }
    }
}

Invoke-Step 'Artifact' {
    $version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    $sha = (git rev-parse --short HEAD 2>$null)
    if (-not $sha) { $sha = 'nogit' }
    $dirty = if (git status --porcelain 2>$null) { '-dirty' } else { '' }
    New-Item -ItemType Directory -Force dist | Out-Null
    $target = "dist\feiyu-notes-$version-$sha$dirty-debug.apk"
    Copy-Item app\build\outputs\apk\debug\app-debug.apk $target -Force
    Write-Host "    $target"
    $global:LASTEXITCODE = 0
}

if ($Full) {
    # Stamp only if nothing changed while the tests ran.
    if ((Get-WorkTreeId) -eq $treeAtStart) { Set-Content -LiteralPath $passStamp -Value $treeAtStart -NoNewline }
    else { Write-Host '    files changed during the run; pass not stamped' -ForegroundColor Yellow }
}
Write-Host ("CI passed ({0})" -f $(if ($Full) { 'full' } else { 'fast' })) -ForegroundColor Green
