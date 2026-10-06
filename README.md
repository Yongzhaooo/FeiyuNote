# 肥鱼笔记

中文 | [English](README.en.md)

<img src="app/src/main/res/drawable-nodpi/whale_02_01.webp" width="128" alt="肥鱼笔记图标">

拍下板书或 PPT，围绕问题与 DeepSeek 对话，再把讲解整理成本地复习笔记。支持按课程和课次管理内容，也可以用刷题本记录错题与掌握状态。

项目处于早期阶段，目标是小而美。欢迎[报告问题](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=bug.yml)和[建议功能](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml)。向主线贡献代码前，请先在 Issue 中确认范围；详见[贡献指南](CONTRIBUTING.md)。

## 下载与使用

前往 [Releases](https://github.com/Yongzhaooo/FeiyuNote/releases) 下载预览版 APK，支持 Android 8.0 及以上。

1. 安装后打开「设置」。
2. 在 [DeepSeek 开放平台](https://platform.deepseek.com/api_keys) 创建 API Key 并存入应用。API 按使用量计费，请自行检查平台余额与价格。
3. 新建课程和课次，输入问题、拍照或从相册选图后发送；需要复习时点击「整理本课」。

笔记、照片和对话保存在设备本地。发起模型请求时，所选问题、上下文和图片会发送给 DeepSeek。

API Key 使用 AES-256-GCM 加密存入私有目录，加密密钥由 Android Keystore 管理且不可导出。凭据不参与备份或设备迁移；设置页防截图、录屏，发布 APK 不可调试。密文损坏或 Keystore 密钥丢失后需重新输入 Key。

## 界面与特性

- 界面语言可在设置中选择中文、英文或跟随系统（中文系统显示中文，其余显示英文）；支持调整字号。
- 适配手机单栏与大屏双栏，支持浅色与深色主题。
- 每个课次随机分配一张鲸鱼娘头像并保持稳定，支持在设置中更换自定义头像。
- 笔记支持离线阅读、编辑/预览、HTML 导出与系统分享。
- 0.2 原生离线 LaTeX 公式：行内支持 `$...$` 与 `\(...\)`，独立公式支持 `$$...$$` 与 `\[...\]`。支持常用分式、根号、积分和矩阵；宽公式横向滚动。复制保留公式源码，HTML 导出内嵌公式图片。不支持的语法显示原文，不支持完整 TeX 文档或自定义宏。

## 版本更新

- **[0.3.3](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.3)**：课程新增复习记录。可从已完成的问答或笔记「加入复习」，也可手工新增；支持编辑、删除、按状态筛选，标记「待复习」「已理解」「仍有疑问」，并回到来源。来源删除后保留复习文字并标明来源已删除。记录按课程隔离，全部操作在本地完成，不额外调用模型。
- **[0.3.2](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.2)**：设置新增“关于与帮助”，可手动检查更新并打开[独立下载页](https://feiyunote.cangming.fyi/feiyu/)，不需要访问 GitHub。应用内可以反馈问题：可选附带诊断信息，提交前预览，成功后显示反馈编号，失败时保留草稿并可重试，离线时可以分享或复制；反馈页提供 QQ 讨论群 1079399140。本机保存有上限的诊断记录，不含 Key、聊天和笔记，不自动上传；意外退出后，下次启动会提示。模型标签缩短为 `dsf.low` 这类形式，公共聊天改为“整理对话”，长对话只发送最近部分的历史。
- **[0.3.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.1)**：首页新增“一起来聊天吧”公共聊天卡片，无需建课即可随手问，进入后显示专属横幅；欢迎卡片和公共聊天卡片的插图都可在设置中换成自己的图片。默认模型 DeepSeek V4.1 Flash，推理强度提供官方 low/high/max 三档（默认 low），可在设置中改默认值，也可在每个会话单独调整；设置页可测试连接。预装“引导式讲解”模板，新建笔记本默认使用。设置新增“高级”：可修改内置提示词，也可通过 GitHub 链接安装 Skill（只读取 SKILL.md 的文字说明作为讲解模板，不运行任何代码）。问答按会话顺序编号，提问 N 对应回答 N；长会话右侧提供跳转节点。新增语言、日夜和字号设置，修复字体过细。
- **[0.3.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.0)**：一条提问支持多张图片；相册多选、继续拍照追加、逐张预览和移除，导入失败保留其他图片。旧单图笔记自动迁移，多图支持重试与删除清理。使用系统照片选择器，无需整个相册的读取权限。
- **[0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0)**：对话、笔记与导出支持离线 LaTeX；新增编辑/预览及源码复制，完善 API Key 加密与凭据保护。
- **[0.1.0-alpha.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1)**：课程/课次问答、刷题本、笔记整理与导出；中英界面、鲸鱼头像与自适应布局。

## 后续方向

近期候选：改善回答中 Markdown 格式的显示，清理未发送图片的残留文件，补齐真实折叠屏验证。较远期考虑讲解模板扩展和随机抽取复习卡片；尚无固定排期，范围通过 Issue 讨论后确定。

有其他需求，欢迎[提出功能建议](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml)，说明使用场景；参与开发请先阅读[贡献指南](CONTRIBUTING.md)。

## 构建与验证

需要 JDK 21、Android SDK（compileSdk 37）和网络连接。Android Studio 可直接打开项目。

```bash
# Linux / macOS
bash ./gradlew testDebugUnitTest assembleDebug

# Windows：快速构建与单测
pwsh -NoProfile -File scripts/ci.ps1

# 完整 CI：使用独立测试模拟器运行仪器与界面测试
pwsh -NoProfile -File scripts/ci.ps1 -Full
```

Windows 首次配置或 SDK 缺失时运行 `pwsh -File scripts/setup-sdk.ps1`；需要完整 CI 时加 `-WithEmulator`，创建或复用 `Feiyu_CI_API36`。SDK 默认位于 `%LOCALAPPDATA%\Android\Sdk`；CI 默认使用 `emulator-5556`，只接受模拟器目标。

本地完整 CI 还需要 Python 3，用于验证 Android runner 的逐项结果与完整结束状态；复习记录的数据层、界面和零模型调用用例随全套测试运行。本地完整 CI 使用隔离数据和模拟回答，不调用计费接口；截图位于 `build/ci/screenshots/`。可通过 `git config core.hooksPath .githooks` 启用 Git 钩子：提交前运行快速 CI，推送前运行完整 CI；如果推送内容与上次完整 CI 通过的版本相比只改了 Markdown，则跳过。推送到 main 后，远端只检查能否构建；PR 和发布 tag 仍会运行单测。

GitHub Actions 对指向 main 的 PR 及 main 推送运行单测和 debug 构建，使用只读权限。推送 `v*` 标签且构建通过后，独立任务使用仓库 Secrets 签名并发布预览版。详见[构建流程](.github/workflows/android.yml)。本地签名 release 构建需配置 `FEIYU_KEYSTORE` 与 `FEIYU_KEY_PASSWORD`（别名 `feiyu`），勿提交密钥。

## 许可与署名

代码采用 [GPL-3.0-or-later](LICENSE)。

鲸鱼娘人设：**上善无形、ZipZipPipe**。表情包：小红书 **Hidcote**（图标为 02-01）。Noto Sans SC 字体采用 SIL OFL 1.1；素材与代码许可分开，详见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 项目文档

[产品约定](docs/spec.md) · [实施与验证记录](docs/plan.md) · [当前状态](docs/wayfinder.md) · [参与方式](CONTRIBUTING.md)
