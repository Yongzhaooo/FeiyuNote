# FeiyuNote 开发基线核验报告 (docs/BASELINE.md)

**记录时间**：2026-10-02 (Local)
**基线状态**：针对性 JVM 单元测试通过 (26/26)，Debug APK 构建成功 (exit code 0)。

---

## 1. 执行环境与工具链版本

- **运行环境**：Linux 6.6.87.1-microsoft-standard-WSL2 amd64 (容器 `feiyunote-dev`，镜像 `cimg/android:2026.08.1@sha256:3e2fcc53cb7ea6cd7ca486b070240a97490030dfc13a6074400ad9d5fd9104d0`)
- **JDK 版本**：OpenJDK 21.0.11 (build 21.0.11+10-1-24.04.2-Ubuntu)
- **javac 版本**：21.0.11
- **Android SDK 根目录**：`/home/circleci/android-sdk`
  - Installed Platforms: `android-34`, `android-35`, `android-36`, `android-36.1`, `android-37.0`, `android-37.1`, `android-37.2-beta1`
  - Installed Build-Tools: `35.0.0`, `36.0.0`, `36.1.0`, `37.0.0`
- **Gradle Wrapper**：Gradle 9.8.0
  - Distribution URL: `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip`
  - SHA256: `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c`
  - Launcher JVM: 21.0.11

---

## 2. 仓库与提交信息

- **目标仓库**：`git@github.com:XePWang/FeiyuNote.git` (Fork 自 `Yongzhaooo/FeiyuNote`)
- **远端 URL (origin)**：`git@github.com:XePWang/FeiyuNote.git` (SSH 转发认证：`XePWang`)
- **构建基线提交**：`main` 分支 `bc33554b294a37ff62df5ba04d911d8d68157224` (`test: wait for the prompt dialog to close before navigating back`，与规划时快照一致，无远端漂移)
- **基线交付分支**：`docs/baseline-reproduce`（本地提交 `3c18eee7b7437d96de1ed48004f5bef01d0390d4`，包含规范与基线文档安装）

---

## 3. 构建与单测执行结果

- **执行命令**：
  ```bash
  bash -c 'set -o pipefail; ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tee build/baseline/build.log'
  ```
- **退出状态码**：`0` (BUILD SUCCESSFUL in 4m 28s)
- **Actionable Tasks**：42 executed
- **单元测试结果**：共 26 项 JVM 单元测试全部通过（0 failures, 0 skipped）
  - `com.feiyu.notes.ai.DeepSeekClientTest` (6 tests)
  - `com.feiyu.notes.export.NoteExporterTest` (4 tests)
  - `com.feiyu.notes.math.MathTextTest` (3 tests)
  - `com.feiyu.notes.settings.SkillImportTest` (3 tests)
  - `com.feiyu.notes.study.ContextBuilderTest` (7 tests)
  - `com.feiyu.notes.ui.ThreadNavigationTest` (3 tests)
- **构建输出 APK**：
  - 容器路径：`/home/circleci/project/app/build/outputs/apk/debug/app-debug.apk`
  - 宿主机挂载路径：`C:\Users\XeP\MyProjects\FeiyuNote\app\build\outputs\apk\debug\app-debug.apk`
  - 文件大小：约 27 MiB (28,307,842 字节)
  - SHA256：`858eda0be7fdd0de499333984faa213b07f0728e24a0ff1550d483ac03804067`
- **日志归档**：`build/baseline/build.log`

---

## 4. 关键功能代码路径与核查

| 功能模块 | 核心代码路径 | 状态与说明 |
|---|---|---|
| **课程 / 课次 (Notebook / Lesson)** | `data/Models.kt`, `data/NotebookStore.kt`, `ui/NotebookListScreen.kt`, `ui/LessonListScreen.kt`, `ui/AppNavigation.kt` | 源码具备；SQLite v3 存储结构支持 Notebook、Lesson 级联管理与预装引导模板。 |
| **多图输入 (Multi-image)** | `data/Models.kt` (`imagePaths`, `attachedImageEntryIds`), `study/StudyViewModel.kt`, `ui/ChatScreen.kt` | 源码具备；单元测试覆盖多图输入组装 (`ContextBuilderTest`)，UI 流程测试定义在 `androidTest/UiFlowTest.kt`（本轮未在真机执行）。 |
| **ASK / EXPAND / MISTAKE** | `data/Models.kt` (`EntryAction`), `study/ContextBuilder.kt`, `study/Generator.kt` | 源码具备；`ContextBuilder.buildTurn` 根据动作组装请求，单测覆盖纯图/附图/展开/错题 prompt 组装。 |
| **手动整理 QA (Summary)** | `study/ContextBuilder.kt` (`buildSummary`), `study/Generator.kt` (`summarize`), `study/StudyViewModel.kt` | 源码具备；基于完成状态问答提取 `#ID 问` / `#ID 答` 纯文本并发起单次模型总结，支持自定义 base prompt 与模板覆盖。 |
| **parentEntryId 分支追问** | `data/Models.kt`, `data/NotebookStore.kt`, `study/ContextBuilder.kt` (`ancestors`), `ui/ThreadNavigation.kt` | 源码具备；单测覆盖祖先链追溯 (`ContextBuilderTest`)、分支切换与同层导航 (`ThreadNavigationTest`)。 |
| **参考笔记 (Reference Note)** | `data/Models.kt` (`sourceEntryIds`), `study/ContextBuilder.kt` (`withReference`), `study/StudyViewModel.kt` (`referenceId`) | 源码具备；`ContextBuilderTest` 覆盖参考笔记嵌入 system prompt。 |

---

## 5. 规范与工作目录设置

- **应用规范**：已安装 `/home/circleci/project/AGENTS.md`（源自 `/opt/feiyu-planning/outputs/AGENTS.md`）。
- **记忆规格**：已同步 `/home/circleci/project/docs/memory-design.md`（源自 `/opt/feiyu-planning/docs/memory-design.md`）。
- **本地工作目录**：创建 `/home/circleci/project/.work/`，包含首轮及后续任务规划参考；在 `.gitignore` 中加入 `/.work/` 排除版本控制。
- **记忆功能范围更正**：已记录在 `.work/memory-scope-discussion.md`，第一版聚焦于课程内学习记录（知识点、来源、掌握状态可查改删），暂不铺开全局跨课记忆。

---

## 6. 尚未验证项目与边界说明

1. **真实设备 / 模拟器 UI 交互**：本轮在无 GUI 的 Linux 容器内执行了针对性的 JVM 单元测试与 APK 打包；折叠屏铰链避让、屏幕旋转、真机手势及真实 UI 渲染未在真机/模拟器上验收，不能据此声称 UI 测试完备。
2. **真实模型 API 调用**：`DeepSeekClientTest` 使用 Mock 验证协议与重试机制；未配置生产 API Key，未消耗付费模型额度。
3. **真实 SQLite 迁移与持久化**：已编写迁移逻辑，但 instrumented 测试（`NotebookStoreTest`）需 Android 运行时环境，未在设备上实测。
4. **Windows 专用脚本 (`scripts/ci.ps1`)**：仓库既有 `.githooks` 调用 PowerShell 脚本 `scripts/ci.ps1`，依赖 Windows 本地路径与工具；Linux 容器内通过 Gradle Wrapper 执行构建，未直接运行该 PowerShell 脚本。
