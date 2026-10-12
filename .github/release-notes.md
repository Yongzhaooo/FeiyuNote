# 肥鱼笔记 0.5.0 / Feiyu Notes 0.5.0

Android 8.0 及以上，早期预览版。

## 中文

- **课程手册**：每门课程可导入或编写一份 UTF-8 Markdown，支持目录、书签和离线阅读。发送问题时，在设备上检索本课程的相关完整章节；可以随时暂停参考。最多 1 MiB、1000 个标题，代码与表格以可读源码展示。
- **随堂记录**：在课次中边听边记，自动保存；可选择随问题附带，也可与已完成问答一起整理成复习笔记。只有记录、没有问答时也能整理。
- **课次标题**：点击标题即可手工改名，或显式请求提炼主题，确认后保留原日期。中文主题为 4 至 8 个汉字，英文为 2 至 6 个词。
- **参考笔记引导**：列表为空时提示使用右上角「整理本课」；公共聊天对应「整理对话」。
- **英文支持**：内置学习、聊天、整理与标题 prompt，以及未修改的预装模板名称和说明，跟随应用语言切换。你自己修改的 prompt、模板和笔记保持原文。

失败重试使用发送时保存的记录和手册快照。手册修改或删除后，请重新提问，或明确移除旧引用后重试。课程手册按课程隔离，复习记录继续保存在本地，不自动加入模型上下文。

## English

An early preview for Android 8.0+.

- **Course handbooks**: import or write one UTF-8 Markdown document per course, with contents, bookmarks and offline reading. Sending a question searches complete relevant sections from that course locally. Pause reference whenever you need. The limit is 1 MiB and 1,000 headings; code and tables remain readable source.
- **Class notes**: take notes inside a session with autosave. Choose whether to attach them to questions, and summarize them with completed Q&A. Notes-only summaries are supported.
- **Session titles**: tap a title to rename it, or explicitly request a topic suggestion and confirm it while keeping the date. Topics use 4 to 8 Chinese characters or 2 to 6 English words.
- **Reference guidance**: an empty picker points to Summarize, or Summarize chat in general chat.
- **English support**: built-in study, chat, summary and title prompts, plus unedited preinstalled template names and instructions, follow the app language. Your custom prompts, edited templates and notes keep their original text.

Retries use the class notes and handbook snapshot saved with the question. If the handbook changes or is deleted, ask a new question or explicitly remove the old excerpts before retrying. Handbooks stay within their course. Review records remain local and are not automatically added to model requests.

## 截图 / Screenshots

实际应用界面，合成演示资料与模拟模型回答。Actual app UI with synthetic materials and mock replies.

| 中文 / Chinese | English |
| --- | --- |
| <img src="https://raw.githubusercontent.com/Yongzhaooo/FeiyuNote/v0.5.0/docs/screenshots/0.5/zh/handbook.png" width="240" alt="课程手册"> | <img src="https://raw.githubusercontent.com/Yongzhaooo/FeiyuNote/v0.5.0/docs/screenshots/0.5/en/handbook.png" width="240" alt="Course handbook"> |
| <img src="https://raw.githubusercontent.com/Yongzhaooo/FeiyuNote/v0.5.0/docs/screenshots/0.5/zh/class-notes.png" width="240" alt="随堂记录与相关章节"> | <img src="https://raw.githubusercontent.com/Yongzhaooo/FeiyuNote/v0.5.0/docs/screenshots/0.5/en/class-notes.png" width="240" alt="Class notes and relevant sections"> |
| <img src="https://raw.githubusercontent.com/Yongzhaooo/FeiyuNote/v0.5.0/docs/screenshots/0.5/zh/title.png" width="240" alt="课次标题建议"> | <img src="https://raw.githubusercontent.com/Yongzhaooo/FeiyuNote/v0.5.0/docs/screenshots/0.5/en/title.png" width="240" alt="Session topic suggestion"> |

## 安装 / Installation

从本页下方的签名 APK 或[独立下载页](https://feiyunote.cangming.fyi/feiyu/)覆盖安装。升级保留课程、问答、笔记、照片、复习记录、设置和密钥。数据保存在本地；发送问题时，所选上下文（包括启用的随堂记录和手册章节）会发送给 DeepSeek。未调用真实模型进行本次验收。

Install the signed APK below over your existing version, or use the [independent download page](https://feiyunote.cangming.fyi/feiyu/). Upgrades retain courses, conversations, notes, photos, review records, settings and your key. Data stays on your device; sending a question transmits the selected context, including enabled class notes and handbook sections, to DeepSeek. This release was verified using mock responses rather than paid model calls.

[中文说明](https://github.com/Yongzhaooo/FeiyuNote/blob/v0.5.0/README.md) · [English guide](https://github.com/Yongzhaooo/FeiyuNote/blob/v0.5.0/README.en.md) · QQ 交流群 / discussion group 1079399140
