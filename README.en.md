# Feiyu Notes

[中文](README.md) | English

<img src="app/src/main/res/drawable-nodpi/whale_02_01.webp" width="128" alt="Feiyu Notes icon">

Photograph a whiteboard or slide, ask DeepSeek about it, and turn the explanation into local study notes. Organize conversations by course and session, or use practice notebooks to track mistakes and mastery.

This early-stage project aims to stay small and focused. [Bug reports](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=bug.yml) and [feature suggestions](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml) are welcome. For upstream contributions, confirm the scope with the maintainer in an Issue first; see the [contribution guide](CONTRIBUTING.md#english).

## Download and get started

Download a preview APK from [Releases](https://github.com/Yongzhaooo/FeiyuNote/releases). Requires Android 8.0 or later.

1. Open Settings after installation.
2. Create an API key on the [DeepSeek platform](https://platform.deepseek.com/api_keys) and save it in the app. API usage is billed by DeepSeek; monitor your own account balance and pricing.
3. Create a course and session. Type a question, take a photo, or choose an image, then tap Send. Tap "Summarize session" to generate study notes.

Notes, photos, and conversations are stored locally on your device. Sending a request transmits the selected text, context, and attached images to DeepSeek.

API keys use AES-256-GCM encryption in private storage, with non-exportable encryption keys managed by Android Keystore. Credentials are excluded from backup and device transfer; Settings blocks ordinary screenshots and screen recordings, and release APKs are not debuggable. Damaged ciphertext or a lost Keystore key requires entering the API key again.

## Interface and features

- Choose Chinese, English, or follow the system (Chinese systems show Chinese, others English) in Settings; text size is adjustable.
- Single-pane layout on phones, two-pane layout on large screens; light and dark themes.
- Each session keeps a randomly selected whale-girl portrait; custom avatars are available in Settings.
- Notes support offline reading, edit/preview modes, HTML export, and system sharing.
- Version 0.2 renders LaTeX offline: inline `$...$` or `\(...\)`, and display `$$...$$` or `\[...\]`. Common fractions, roots, integrals, and matrices are supported; wide formulas scroll horizontally. Copying preserves formula source, and HTML exports embed formula images. Unsupported syntax falls back to raw text. Full TeX documents and custom macros are not supported.

## Version history

- **[0.3.3](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.3)**: Course review records. Add a completed answer or note to review, or create a record manually. Edit, delete, filter by status, mark Pending / Understood / Still confused, and return to the source. Deleted sources are marked while the review text is kept. Records stay within their course, and all review operations run locally without extra model requests.
- **[0.3.2](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.2)**: Settings adds About and help: check for updates manually and open the [independent download page](https://feiyunote.cangming.fyi/feiyu/) without GitHub. Report problems in the app with optional diagnostics, a preview and a report number; drafts survive failures, retries never duplicate, and you can share or copy when offline. The feedback page lists QQ discussion group 1079399140. Bounded local diagnostics exclude keys, chats and notes and are never uploaded automatically; the app offers a report after an unexpected exit. The model label is shorter (e.g. `dsf.low`), general chat says "Summarize chat", and long chats send only recent history.
- **[0.3.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.1)**: A "Let's chat" general chat card on the home screen for quick questions without creating a course, with its own banner inside; the welcome and chat card illustrations can be replaced in Settings. The default model is DeepSeek V4.1 Flash with the official low/high/max reasoning tiers (default low); set the default in Settings or override it per session. Settings can test the API connection. A preinstalled "Guided explanation" template is the default for new notebooks. A new Advanced section lets you edit the built-in prompts and install a Skill from a GitHub link (only the SKILL.md text is used, as an explanation template; no code is run). Questions and answers are numbered in session order, so question N pairs with answer N, and long sessions get a jump rail on the right. Adds language, light/dark, and text-size settings, and fixes overly thin text.
- **[0.3.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.0)**: Attach multiple images to one question. Select several photos, append camera captures, preview and remove individual attachments. Failed imports keep the other images. Existing single-image notes migrate automatically; retry and deletion handle all attachments. Uses the system photo picker without full-library permission.
- **[0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0)**: Offline LaTeX in conversations, notes, and exports; edit/preview modes and source copying; improved API-key encryption and credential protection.
- **[0.1.0-alpha.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1)**: Course/session conversations, practice notebooks, note summaries and exports; Chinese/English UI, whale avatars, and adaptive layouts.

## Planned directions

Near-term candidates: improve Markdown display in answers, clean up orphaned unsent images, and verify real foldable devices. Longer-term ideas include richer explanation templates and randomly selected revision cards. There is no fixed schedule; scope is agreed through Issues.

[Suggest a feature](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml) with your use case. For implementation contributions, read the [contribution guide](CONTRIBUTING.md#english) first.

## Build and verify

Requires JDK 21, Android SDK (compileSdk 37), and an internet connection. Android Studio can open the project directly.

```bash
# Linux / macOS
bash ./gradlew testDebugUnitTest assembleDebug

# Windows: fast build and unit tests
pwsh -NoProfile -File scripts/ci.ps1

# Full CI: run instrumented and UI tests on an isolated emulator
pwsh -NoProfile -File scripts/ci.ps1 -Full
```

On Windows, run `pwsh -File scripts/setup-sdk.ps1` to install or repair the SDK. Add `-WithEmulator` for full CI to create or reuse `Feiyu_CI_API36`. The SDK defaults to `%LOCALAPPDATA%\Android\Sdk`; CI defaults to `emulator-5556` and accepts only emulator targets.

Local full CI also requires Python 3 to validate individual Android runner results and clean completion. Course review data, UI and zero-model-call checks run with the full suite. Local full CI uses isolated data and mock responses without paid API calls. Screenshots are saved in `build/ci/screenshots/`. Enable optional Git hooks with `git config core.hooksPath .githooks`: fast CI before each commit, full CI before each push, skipped when the pushed content differs from the last passing full run only in Markdown files. Remote CI only checks the build on pushes to main; pull requests and release tags also run the unit tests.

GitHub Actions runs unit tests and debug builds for PRs targeting main and pushes to main, with read-only permissions. A `v*` tag and a successful build trigger a separate job to sign and publish a prerelease using repository Secrets. See the [workflow](.github/workflows/android.yml). Signed local builds require `FEIYU_KEYSTORE` and `FEIYU_KEY_PASSWORD` (alias `feiyu`); never commit signing keys.

## License and credits

Code is licensed under [GPL-3.0-or-later](LICENSE).

Whale-girl character design: **上善无形 and ZipZipPipe**. Stickers: **Hidcote** on Xiaohongshu (icon: 02-01). Noto Sans SC uses SIL OFL 1.1. Artwork is separate from the code license; see [third-party credits](THIRD_PARTY_NOTICES.md).

## Project documents

[Product specification](docs/spec.md) · [Implementation and verification](docs/plan.md) · [Current status](docs/wayfinder.md) · [Contributing](CONTRIBUTING.md)

Development documents are currently in Chinese.
