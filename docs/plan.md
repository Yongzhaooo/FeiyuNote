# 肥鱼笔记实施与验证记录

产品行为以 [spec](spec.md) 为准；本文记录实现边界与验证证据，待处理事项见 [Wayfinder](wayfinder.md)。

## 实现边界

单个 `app` 模块，Kotlin/Compose、Material 3 Adaptive、Navigation 3；Android 原生 SQLite 保存数据，OkHttp 发送请求。包名 `com.feiyu.notes`，下文 `K` 表示 `app/src/main/java/com/feiyu/notes`。minSdk 26、compileSdk 37、targetSdk 36；构建使用 JDK 21、AGP 9.4.1 与 Gradle 9.8.0。构建命令见 [README](../README.md#构建与验证)。

| 边界 | 核心文件 | 职责与约束 |
| --- | --- | --- |
| 应用生命周期 | `K/FeiyuApp.kt` | 手工持有 `NotebookStore`、`PhotoFiles`、`Generator`；启动时调用 `markPendingInterrupted`，不自动重发请求。 |
| 导航 | `K/ui/AppNavigation.kt` | 导航键只传笔记本、课次、条目 ID；`ListDetailSceneStrategy` 支持单双栏，`BackNavigationBehavior.PopLatest` 保证双栏返回时逐级退栈。 |
| 数据 | `K/data/Models.kt`、`NotebookDatabase.kt`、`NotebookStore.kt` | 单库四表；按笔记本/课次限定查询，写入入口校验归档与掌握状态。事务成功后递增 `changes: StateFlow<Long>` 供页面重读；删除事务成功后再清理图片。数据库 v2 将旧单图迁移为有序图片列表；字段与失效引用规则见 spec §5。 |
| 附件 | `K/data/PhotoFiles.kt` | 只分配、解析和删除私有 `images/<notebookId>/` 内的文件，拒绝含路径分隔符的文件名。 |
| 凭据 | `K/settings/ApiSettings.kt` | 固定 endpoint；API Key 以 AES-256-GCM 加密，Keystore 中的加密密钥不可导出。完整配置只由 `Generator` 读取；设置首页与密钥页（`K/ui/KeySettingsScreen.kt`）只读取模型名和 Key 是否存在，明文不进入笔记库、导出或日志。 |
| 界面状态 | `K/settings/AppPrefs.kt`、`K/study/StudyViewModel.kt` | `AppPrefs.lastLesson: Pair<Long, Long>?` 保存笔记本与课次 ID，失效时清空；另保存课次头像索引。ViewModel 持有选择、草稿、附件等界面状态，不持有请求任务；数据变化、发送和重试前重新校验引用。 |
| 模型客户端 | `K/ai/AiTypes.kt`、`DeepSeekClient.kt` | 注入配置与 OkHttpClient，不读取设置；只允许 user 消息带图，缩放压缩为 JPEG 后编码；返回最终正文，区分认证、限流、网络、空回答与超限错误。协程取消传递到 HTTP 请求，不自动重试。 |
| 上下文 | `K/study/ContextBuilder.kt`、`StudyPrompts.kt` | 纯函数按已保存 user 条目的 action、父链、附件与引用组装请求；不另建一份请求输入。普通问答与整理沿用 spec §6 的不同上下文范围。 |
| 生成 | `K/study/Generator.kt` | 应用级单例，同一时刻只接受一个请求，不随页面销毁。删除前调用 `cancelIfAffected`；`commitReply` 在事务内复核目标仍为 pending，`commitSummary` 复核课次仍存在，迟到结果不能重建已删除内容。 |
| 公式与导出 | `K/math/MathText.kt`、`MathRenderer.kt`、`K/ui/MathContent.kt`、`K/export/NoteExporter.kt` | 纯文本解析与原生离线渲染共用于对话、笔记和 HTML；保留可复制、可编辑源码，失败回退原文。导出转义正文、内嵌公式图片，不带脚本、外部资源或内部条目 ID。 |

## 验证入口

复用 `scripts/ci.ps1`：默认执行构建与 JVM 单测，`-Full` 增加仪器和界面测试。界面测试对相机、相册等外部应用打桩，真实设备交互仍需人工抽查；计费 API 冒烟默认跳过。

SDK 缺失时使用 `scripts/setup-sdk.ps1`，需要模拟器时加 `-WithEmulator`。SDK 默认位于 `%LOCALAPPDATA%\Android\Sdk`，CI 使用独立 `Feiyu_CI_API36`（`emulator-5556`），只接受模拟器目标。JDK、SDK 与 AVD 为持久环境，不应纳入临时目录清理。

## 0.3.2 更新与问题反馈实施计划

日期：2026-10-01。状态：实施中，分支 `feat/0.3.2-support`（worktree `.claude/worktrees/0.3.2`）。P1 至 P4 的代码与测试已完成，反馈服务和站点已部署到 VPS，0.3.2 发布由 P5 收口（见下方实施进度）。产品范围以 [spec §9](spec.md#9-032-更新与问题反馈待实现) 为准。用户后续授权使用 `feiyunote.cangming.fyi` 并直接配置 DNS；功能实现、HTTPS/服务部署和发布仍等待后续执行指令。

### 当前基础与变更边界

- 主代码位于 `E:\Projects\Opensource\肥鱼笔记`；另一个 `FeiyuNote` 工作目录目前只有 `.git` 和 `assets`，实施前应核对所在 checkout。
- 复用 `K/ui/SettingsScreen.kt::SettingsScreen`、`SettingsSection`，Navigation 3 的 `K/ui/AppNavigation.kt`，以及 `K/FeiyuApp.kt` 的应用生命周期和测试环境注入。
- 已有 OkHttp、kotlinx.serialization、JUnit、MockWebServer、Compose 仪器测试和 FileProvider。本次客户端不增加依赖，不迁移笔记数据库。
- `K/ai/AiTypes.kt::AiError` 的部分 message 含服务响应、网络地址或图片名；诊断不能直接写 `message`、`toString()` 或 `printStackTrace()`。保留原用户错误提示，单独映射安全的错误类型和状态码。
- 发布入口是 `.github/workflows/android.yml`，目前只上传 GitHub Releases。`app/src/main/res/xml/file_paths.xml` 已暴露 `cache/exports/`，诊断分享可复用该目录，不扩大到整个私有目录。
- 2026-10-01 交接时 HEAD 为 `dda9322`（聊天卡片、引导模板、可编辑提示词与文字 Skill 已进入提交），工作区仍有 spec/plan/wayfinder 的文档改动；接手先检查实际状态，保留所有既有改动。历史发布记录中 0.3.1 为 versionCode 4，当前代码为 versionCode 5 / versionName 0.3.1；版本收口由发布项统一负责，不能直接认定 5 仍未占用。

### 实施默认值与共享接口

以下为本计划提出的实施默认值。接口可据此开始本地开发；试用主机确定为 `feiyunote.cangming.fyi`，剩余 VPS 部署参数和对外保留期限由 P0 收口。预定更新源为 `https://feiyunote.cangming.fyi/updates/android.json`，下载页为 `https://feiyunote.cangming.fyi/feiyu/`，反馈入口为 `https://feiyunote.cangming.fyi/api/v1/feedback`；这些 HTTPS 路由尚未部署。若修改字段、语义或限制，只暂停受影响的消费者并同步本节。

**更新接口。** 静态发布端生产 `GET /updates/android.json`，`UpdateClient` 消费；UTF-8 JSON，schemaVersion 为 1，响应上限 32 KiB，总请求超时 15 秒。只手动检查，不复用带 DeepSeek 凭据的请求构造，不携带 API Key。字段如下：

```json
{
  "schemaVersion": 1,
  "versionCode": 5,
  "versionName": "0.3.2",
  "minSdk": 26,
  "notes": {"zh": "更新说明", "en": "Release notes"},
  "downloadPageUrl": "https://feiyunote.cangming.fyi/feiyu/"
}
```

示例中的版本号数值不是发布配置；域名已配置 DNS，但下载页仍待部署。只有远端 versionCode 大于本机才提示升级；设备低于 minSdk 时说明系统不受支持并禁用下载。必需字段缺失、类型错误、不支持的 schemaVersion、超限响应均视为检查失败。说明按应用语言选择，缺少对应语言时使用英文；作为普通文本显示。更新源及下载页必须 HTTPS 且属于 P0 确认的主机白名单，重定向也逐跳校验；不得退回 GitHub。测试通过注入本地 MockWebServer 地址完成，不改变 release 的明文流量限制。

**诊断接口。** 新建 `K/support/Diagnostics.kt`，由应用生命周期及出错点生产数据，反馈预览、提交和分享消费同一份快照。对外提供 `record(event: DiagnosticEvent)`、`snapshot(): DiagnosticSnapshot` 和清理入口；使用固定事件/错误枚举和白名单字段，禁止任意 Map 或任意 message 成为日志入口。快照为 schemaVersion 1，含 `app { versionName, versionCode }`、`device { manufacturer, model, androidSdk }`、`events`、可空的 `crash` 和 `truncated`。事件字段限 UTC 时间、固定操作名、结果、错误分类、可空 HTTP 状态码及耗时；崩溃只含异常类名及限定长度的类名/方法名/源码文件名/行号，不含异常消息和运行时路径。

普通日志按日轮转，保留最近 7 天且总计不超过 1 MiB；最近一份崩溃记录上限 64 KiB，也在 7 天后过期。快照最多 256 KiB，超限按完整事件从旧到新移除并置 `truncated=true`，不能截断成无效 JSON。单事件最多 4 KiB，堆栈最多 64 帧、原因链最多 4 层。正常写入在 IO 调度器中串行完成；异常处理只做有界的同步落盘，写入失败也必须调用之前的异常处理器。崩溃处理器每进程只安装一次，测试环境切换不重复套装；拒绝或忽略提示后该次崩溃不再自动弹出。

**反馈接口。** 新建客户端 `FeedbackClient` 调用 `POST /api/v1/feedback`，服务端由 P2 生产。使用 `application/json; charset=utf-8`，schemaVersion 为 1；不携带 DeepSeek Key，也不把长期服务密钥嵌入 APK。总请求超时 20 秒，关闭自动重试及 POST 重定向。请求体上限 512 KiB，不支持压缩请求体或附件：

```json
{
  "schemaVersion": 1,
  "submissionId": "客户端生成的 UUID",
  "description": "问题描述",
  "diagnostics": null
}
```

description 去除首尾空白后为 1 至 4000 个 Unicode 码点；diagnostics 未勾选时必须为 null，勾选时为上述完整快照。未附带诊断时不额外提交设备信息。用户预览后冻结这一请求；失败重试使用同一个 submissionId 和相同内容，修改描述或诊断选择后生成新 ID。服务端事务持久保存后返回 `201 {"reportId":"服务端生成的 UUID"}`；相同 submissionId 和内容重试返回 200 及原编号，内容不同返回 409，绝不覆盖原报告。去重记录与报告一同保留 30 天；超期草稿重试时重新确认、生成新 ID，并提示无法判断旧报告是否曾送达。

失败响应使用 `{"error":"固定错误码"}`：400/415 为格式或媒体类型错误，409 为提交冲突，413 为过大，429 为限流并附 Retry-After，503 为暂不可用或存储容量不足。客户端按状态显示本地化提示，不展示服务端原始内容；只有收到有效 200/201 和合法 reportId 才显示成功，其他响应或断线均保留草稿。所有反馈响应上限 8 KiB。

匿名入口不提供公开查询或列表。服务端建议保留 30 天，每 IP 每分钟最多 5 次（反向代理执行，IP 只作短期限流），报告总存储预算 256 MiB；超预算拒收并返回 503，不丢弃尚未到期的报告。服务和代理均不记录请求正文，反馈路径不保留常规访问日志；来源 IP 仅信任已配置代理。维护者通过现有 SSH 权限读取指定反馈编号，无需新增管理后台。

### 工作项与验收

#### P0：确认部署地址与发布基线

- 已完成（2026-10-01）：Porkbun API 原先没有 `feiyunote.cangming.fyi` 独立记录，公开解析来自泛域名停放。按用户授权新增 CNAME，目标 `vps.cangming.fyi`，TTL 600 秒，记录 ID `589627650`；API 读回及 Porkbun 权威 DNS、Cloudflare `1.1.1.1` 均验证通过，最终解析到 `158.101.160.20`。API 前后比较确认其他 DNS 记录未变；没有购买域名。
- 保留约束：本次是新增子域名，`vps.cangming.fyi` 原记录、原站点及其他服务保持不变。CNAME 只提供名称解析，不是 HTTP 跳转；后续在 Caddy 新增 `feiyunote.cangming.fyi` 的站点路由，不能重命名、覆盖或重定向现有 VPS 站点。
- 运维依据：复用了 personal-site 已有 DNS 操作方式，凭据从 NAS 受保护配置读取，仅在内存中用于 Porkbun 请求。无凭据的操作前记录保存在本机 `C:\Users\CYZ\.codex\tmp\feiyunote-dns-before-20261001T174511Z.json`（原独立记录为空）；若需回退，只删除上述新记录，不改泛域名及其他记录。
- 已查环境：通过既有 `yz_vps` SSH 确认 `/usr/bin/python3` 和 `/usr/local/bin/caddy` 存在。全局 Caddy 的维护源为 `E:\Projects\Services\vps-management`，后续新增站点遵循该仓库的配置比对、备份、校验和显式 reload 流程。本次没有修改 Caddy、签发证书或部署站点。
- 剩余产出：确定 APK 地址、接收方说明及 30 天保留期，核实服务目录、运行用户、存储空间和发布基线；验证 HTTPS 和实际目标用户网络。DNS 成功不代表下载页或反馈服务已上线。
- 范围：只读查看已授权资源及现有发布版本；结果补充到本节。新增部署说明由 P2 写入 `services/feedback/README.md`，不在仓库保存密钥。版本协调覆盖当前未提交工作，最终 versionName 为 0.3.2，versionCode 严格大于所有已分发构建。
- 步骤：沿用已确定的试用域名，核实 Caddy 路由和 HTTPS 部署条件，再确认从目标用户网络访问下载/API 的可行性；复用已有资源，不重复创建 DNS，不默认购域名、订阅或新增付费存储。
- 验证：保留已有 DNS 证据，补充主机、部署路径、发布基线和实际连通结果；HTTPS 和目标用户网络未验证时分别注明，不得以 DNS 或 VPS SSH 可达替代。
- 停止条件：缺少域名归属、服务运行环境或发布基线时暂停正式配置和上线验证；P1、P3、P4 的本地实现与模拟测试仍可继续。需要购买服务时先准备具体服务商、价格/币种、账号和条款，购买等待该具体行动的用户授权。

#### P1：本地诊断与崩溃恢复提示

- 产出：实现 L01/L02，反馈流程能读取有界、可预览的诊断快照。
- 范围：新增 `K/support/Diagnostics.kt`、`DiagnosticModels.kt`；修改 `K/FeiyuApp.kt`、`K/study/Generator.kt`、`K/ui/ModelSettings.kt`、`K/study/StudyViewModel.kt`、`K/ui/NoteScreen.kt` 中实际的生成、连接测试、图片导入和导出错误入口。新增 `app/src/test/java/com/feiyu/notes/support/DiagnosticsTest.kt` 及必要的隔离仪器测试。提示界面的导航与文案统一交 P4 集成。
- 步骤：先实现白名单记录与轮转，再接入上述失败点和启动初始化；保留协程取消语义，取消不是崩溃。从合成异常构造安全堆栈，崩溃重启后向 P4 提供待处理状态。诊断数据和测试数据使用各自私有目录，不修改笔记数据库或导出正文。
- 验证：用包含合成 Key、私密正文、URL 和文件路径的异常证明这些内容不出现在快照；验证按天/容量清理、快照截断后仍可解析、并发写入及磁盘写失败不会破坏业务结果。隔离崩溃实验验证落盘、下次启动提示及调用原处理器；不能杀死仪器测试 runner 来冒充通过。
- 停止条件：要收集白名单之外的内容、引入后台上传或接管系统崩溃行为时回到范围决策；一般测试失败由本项修复。部分崩溃无记录时按 L02 的覆盖边界报告。

#### P2：反馈接收服务及部署准备

- 产出：实现 F01 的持久接收、去重、回执和维护者读取；服务端准备好与客户端联调。
- 范围：新增 `services/feedback/server.py`、`test_server.py`、`README.md` 及最小服务/反向代理配置样例。本项独占服务目录和测试数据库，不改 Android 文件。
- 步骤：建议用 Python 3 标准库 HTTP 服务与 sqlite3，在反向代理后仅监听回环地址、单进程运行；SQLite 独立于应用笔记库，以 submissionId 唯一约束和事务完成去重，不增加 Web 框架、容器或数据库服务。实现有界读取、读超时、结构/字段/大小验证和固定错误响应，代理限制连接数、大小和频率。准备最小 systemd 配置、按日清理过期报告和只按 reportId 读取的命令；数据库置于非网站公开目录，仅服务用户可读写。若 VPS 已有合适服务框架，由 P0 决定复用，并在实现前更新运行方式。
- 验证：`python -m unittest discover -s services/feedback -p 'test_*.py'` 使用临时库和合成报告，覆盖首次/重复/并发提交、同 ID 不同内容、非法 schema、超限、重启后回执、事务失败不返回成功及过期清理。代理侧单独验证 429、413、非公开监听和公开目录不可读取报告。Windows 执行 Python 时设置 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`，分别检查退出码与数据库实际记录。
- 停止条件：生产部署等 P0 真实参数及后续执行授权；无需部署即可完成本地服务和单测。任何向第三方发邮件、通知或购服务的扩展均不属于本项；若确需增加，先完成可审阅内容并等待具体用户授权。

#### P3：检查更新与独立下载页

- 产出：实现 U01/U02 的版本查询模块及可直接下载 APK 的静态页面。
- 范围：新增 `K/support/UpdateClient.kt`、`UpdateManifest.kt`、`app/src/test/java/com/feiyu/notes/support/UpdateClientTest.kt`；新增 `site/feiyu/index.html`、`site/updates/android.json` 模板和 `scripts/publish-download.ps1`。本项不改设置页、字符串资源和 GitHub 发布 workflow，共享位置由 P4/P5 集成。
- 步骤：按固定接口查询和比较版本，用 PackageManager 获取安装版本；页面给出版本、说明、下载按钮、系统安装简短指引和对应版本源码/许可证入口。复用现有签名 APK，发布脚本接受 APK、版本和部署目的地参数，先验证再上传 APK 与页面，最后原子替换版本 JSON；失败保留旧版索引。GitHub 保留为可选源码渠道，下载按钮不指向它。
- 验证：MockWebServer 覆盖远端更高/相同/更低版本、不兼容 minSdk、坏 JSON、未知 schema、超限、超时、HTTP 错误、非 HTTPS/非白名单链接及重定向；断言请求不带凭据。静态页验证窄屏下载按钮、可访问名称和可复制下载链接，发布脚本用测试目录验证中途失败不切换索引。
- 停止条件：真实域名和 APK 未具备时只完成本地页面、客户端与测试；公共可达及覆盖安装由 P5 统一验证。若计划改为应用内下载或安装，先更新范围与平台权限约定。

#### P4：反馈、分享及设置页集成

- 产出：完成 F01 至 F04，并把更新结果和崩溃恢复入口接入同一套帮助流程。
- 范围：新增 `K/support/FeedbackClient.kt`、`FeedbackDraftStore.kt`、`K/ui/SupportScreen.kt`、`SupportViewModel.kt` 及对应 `support/FeedbackClientTest.kt`、`FeedbackDraftStoreTest.kt`；新增 `app/src/androidTest/java/com/feiyu/notes/ui/SupportFlowTest.kt`。本项独占 `K/ui/SettingsScreen.kt`、`AppNavigation.kt`、`app/src/main/res/values/strings.xml`、`values-zh/strings.xml` 的支持功能修改，P1 完成后再修改 `FeiyuApp.kt` 的共享初始化。
- 步骤：先用固定接口的测试替身实现页面、草稿和手动提交，再接入真实快照与客户端；发送中禁用重复提交，回执和重试遵循接口语义。草稿保存在私有目录，重建/重启后恢复；附带诊断默认关闭。崩溃提示由应用根界面展示，查看后进入反馈页，忽略只消除本次提示。分享将预览内容写入已有 `cache/exports/`，通过 FileProvider 授予临时读权限；专用诊断导出文件 24 小时后清理，不删除笔记导出文件。
- 验证：覆盖描述为空、Unicode 长度边界、诊断开关决定请求内容、合法成功回执、超时后相同 ID 重试、409/413/429/503、返回坏 JSON、失败保留草稿和旋转不重发。UI 检查崩溃提示只出现一次、离线分享、无分享应用时复制兜底、中英文、大字号/深色及窄宽布局；复制与分享不谎报服务已收到。测试隔离诊断目录，FileProvider 仍不能读取凭据和私有原始日志。
- 停止条件：最终联调等 P1/P2/P3；本地界面可先用替身开发。需要邮件自动发送、截图上传、联系方式或反馈查询时暂停这些新增部分并回到范围决策。

#### P5：联调、版本收口与发布验收

- 产出：所有 U/L/F 要求有证据，0.3.2 在无 GitHub 访问条件下能安装、检查更新、报错和分享。
- 范围：独占 `app/build.gradle.kts` 的版本和正式端点配置、`.github/workflows/android.yml`、`.github/release-notes.md`、`README.md`、`README.en.md` 及本计划的验收记录。更新发布流程调用 P3 脚本，上传凭据仅放在发布环境，不进入 APK 或源码。
- 步骤：先核对其他在途改动与版本号，串行完成客户端/服务联调；运行既有完整 CI 和服务单测的必要剩余检查，再构建并验签 release。正式部署先启动反馈服务，再上传 APK/页面，最后发布更新索引。现有 GitHub Release 发布与独立下载共用同一签名 APK，记录双渠道结果；镜像失败时旧索引保持可用。
- 验证：`pwsh -NoProfile -File scripts/ci.ps1 -Full`；签名环境执行 `gradlew.bat assembleRelease lintVitalRelease --console=plain`，检查退出码、APK 产物、versionName/versionCode、applicationId、非 debuggable 和原证书一致。使用合成内容安装旧版再覆盖到 0.3.2，确认笔记、照片、加密 Key 和设置保留。真实目标网络屏蔽 GitHub 后完成独立下载及反馈回执；更新分支用测试索引模拟更高版本，不发布虚假版本。核对维护者能够按反馈编号取回该份报告，未勾选诊断时服务端确无诊断。真机测浏览器下载、系统确认安装和一个实际可用的分享目标。
- 停止条件：本次只规划，不执行上述部署或发布；执行阶段缺少签名、域名、真机/目标网络时只挂起对应验收，不宣称 0.3.2 已完整交付。购买、邮件、Issue/评论等面向他人或付费操作须另列具体目的地、内容和费用并取得特定授权；本版没有这些必需步骤。

回退：服务故障时客户端保留草稿并允许分享；禁用故障服务不影响离线笔记。更新索引可原子恢复上一份已验证内容以停止推荐坏包，但不会让已安装用户降级；客户端修复须发布更高 versionCode。反馈服务回滚须保留其数据库，清理只作用于已到期报告。任何客户端启动失败仍通过独立下载页提供修正版。

### 依赖、写入归属与接续

| 项目 | 实现依赖 | 验收依赖 |
| --- | --- | --- |
| P0 | DNS 已完成；后续执行时继续核实部署参数 | HTTPS、实际目标网络和发布版本尚待验证 |
| P1 | 后续执行授权后可开始 | 自身单测；提示交互由 P4，完整崩溃恢复由 P5 收口 |
| P2 | 本地代码按本节接口可开始；生产配置等 P0 | 客户端结合由 P5；代理与生产行为等部署环境 |
| P3 | 本地代码/静态页可开始；正式主机配置等 P0 | P4 集成；真实下载及安装等 P5 |
| P4 | 固定接口替身可先行；修改 FeiyuApp 等 P1 交接 | P1 快照、P2 接口、P3 更新模块全部交付后联调 |
| P5 | P0 的正式值、P1 至 P4 的产物及执行/发布范围已明确 | 一个集成负责人串行运行最终 CI、真机与网络验收 |

P1/P2/P3 可并行，P4 在独占文件中可与它们并行；共享 FeiyuApp、资源、设置页、版本和 workflow 按上述归属顺序编辑。所有写入者先保留现有用户改动；若他人正在修改同一文件，由集成负责人协调后再接入。模拟器、生产代理、发布目录和版本索引属于共享可变环境，集成检查和部署串行执行。

窄检查由各项执行者负责，P5 只补跑未验证的组合及必要完整 CI；同一产物未变化时复用证据，不让每项重复跑全套。上述命令均为计划检查，本次只检查了文档和相关代码入口，未运行功能测试或部署。

### Handoff（2026-10-01）

接手位置：`E:\Projects\Opensource\肥鱼笔记`。先读本仓库指令、`docs/spec.md` §9 和本计划，再核对 `git status`、HEAD 与版本号。`docs/wayfinder.md` 中的历史待办和 Skill 长期方向不自动纳入 0.3.2。

- 目标：完成检查更新、独立下载、本地有界诊断、崩溃恢复提示、应用内匿名反馈和系统分享兜底；普通用户无需 GitHub。
- 已完成：产品和接口计划；新增子域名的 Porkbun API 创建、读回及权威/公共 DNS 验证。无需重复注册或修改 `vps.cangming.fyi`。
- 未完成：P0 剩余部署参数和版本基线；P1 至 P5 的功能代码、测试、HTTPS/服务部署与发布。现有 CI 历史结果不能作为这些新功能的验收证据。
- 下一步：在用户给出执行指令后收口 P0 剩余项，并按共享接口实现 P1/P2/P3；P4 可先用替身开发，P5 负责唯一的最终联调和发布验收。域名已经确定，无需重新向用户询问。具体文件归属、并行条件和停止条件沿用上表。
- 运维入口：`ssh yz_vps`。涉及全局 Caddy 时先读 `E:\Projects\Services\vps-management\AGENTS.md`、`README.md`、`docs/runbook.md`，由该仓库维护源新增站点，保留其他服务的实时配置；肥鱼笔记业务代码与部署说明继续归本项目。DNS 若确需调整，复用已有受保护凭据读取方式，不能输出或提交凭据。
- 必须保留：原包名与签名、旧笔记/照片/设置、原 VPS 域名及站点；日志不含 Key 或学习正文，反馈失败不丢草稿，重试不重复建单。日志默认值、保留期限和接口字段以本节为准。
- 交付证据：新增逻辑窄测试、完整本地 CI、release 验签与覆盖安装、脱离 GitHub 的下载/反馈实测、维护者按编号取回报告。只记录实际通过的检查，未具备的环境单列为待验证。
- 授权接续：当前完成的是计划、DNS 与交接文档；本交接本身不新增功能实施或上线授权。后续用户明确要求实施或部署时，按其授权范围继续，无需重复确认已获授权的同一动作。邮件、对外消息及付费动作仍需各自的具体授权。

后续把完成项、证据、待联调项和下一入口直接更新到本节，不另建平行计划。

### 实施进度（2026-10-01）

基线：分支从 `a40627a` 切出。0.3.1 最终使用 versionCode 6，所以 0.3.2 至少为 7；main 之后又有 0.3.1 的提交，P5 前需要 rebase。

- P2 已完成本地部分：`services/feedback/server.py` 和 `test_server.py`，13 项 unittest 通过（首次/重复/并发提交、409、非法 schema 与孤立代理项、415、413、重启后回执、存储失败返回 503、预算超限、429 及 Retry-After、过期清理、按编号读取、非公开路径）。限流放在服务内实现（stock Caddy 没有限流），只采信回环代理的 `X-Forwarded-For`。systemd 与 Caddy 样例在 `services/feedback/deploy/`，尚未部署。
- P3 已完成本地部分：`K/support/SupportServer.kt`、`UpdateManifest.kt`、`UpdateClient.kt`，`UpdateClientTest` 13 项通过（更高/相同/更低版本、minSdk、坏 JSON 与带引号数字、未知 schema、32 KiB 上限、超时、断网、HTTP 错误、非白名单下载页、重定向策略与循环、生产主机策略，请求不带凭据或 Cookie）。下载页模板 `site/feiyu/index.html`。`scripts/publish-download.ps1` 从 APK 读取版本信息并先跑 apksigner 验签，索引最后原子替换；更新索引直接由脚本生成，不另放 JSON 模板。`scripts/test-publish-download.ps1` 用 debug APK 验证正常发布及中途失败时旧索引不变，已通过；窄屏页面已在浏览器检查。
- P1 已完成本地部分：`K/support/DiagnosticModels.kt`、`Diagnostics.kt`，已接入 `FeiyuApp`（每进程安装一次崩溃处理器，测试环境切换时读取当前实例）、`Generator`（问答/整理的成功、取消、失败及耗时）、连接测试、图片导入、笔记导出与分享。`AiError.TooLarge` 的 code 改为属性，供错误分类使用。`DiagnosticsTest` 9 项通过：合成 Key/正文/URL/路径不进入快照、7 天过期、1 MiB 上限、快照截断后仍可解析、并发写入、磁盘失败不抛错，崩溃处理器在写入失败时仍调用原处理器、因果链与帧数上限。崩溃处理器只在 JVM 中直接调用验证，真实进程崩溃后的下次启动提示待 P4 界面接入后验证。
- 全部 JVM 单测 48 项通过，`assembleDebugAndroidTest` 编译通过。0.3.1 CI 当时占用 emulator-5556，因此本分支还没跑仪器/界面测试。
- 新增需求：反馈页显示 QQ 群 1079399140 作为讨论渠道（spec F04），由 P4 实现。
- 0.3.1 遗留问题并入 0.3.2：会话模型标签缩写为 `dsf.low` 这类形式（读屏仍读完整名称），窄屏输入区不再换行；公共聊天顶栏和模板选择标题改为“整理对话”；问答历史超过 60,000 字符时只发送最近的部分（`ContextBuilder.HISTORY_CHAR_BUDGET`）。新增 `ModelLabelTest` 和 `ContextBuilderTest` 的长历史用例；界面测试改为断言新标签与“整理对话”。Skill 的真实 GitHub 安装暂不验证，长期方向是框架内 Skill（spec §10）。
- 已 rebase 到 `bc33554`（0.3.1 最终提交）。CI 去重：完整 CI 通过后把测试过的 tree 写入 `build/ci/full-passed`；pre-push 钩子发现推送的提交与它只差 Markdown 时跳过；远端推送到 main 只跑 `assembleDebug`，PR 和 tag 仍跑单测。
- P4 已完成：`K/support/FeedbackClient.kt`、`FeedbackDraftStore.kt`、`K/ui/SupportScreen.kt`、`SupportViewModel.kt`；设置页新增“关于与帮助”入口，应用根部负责崩溃提示，反馈页显示 QQ 群 1079399140。预览时冻结请求体，重试沿用同一个 submissionId；修改内容后生成新 ID；超过 30 天的未确认提交需要重新预览。分享时把文本写入 `cache/exports/feiyu-feedback-*.txt`（超过 24 小时自动清理），没有可接收的应用时改为复制。`FeedbackClientTest` 11 项覆盖请求体、码点上限、200/201 回执、各错误码与 Retry-After、8 KiB 上限、发出后超时只发一次、断连、无法连接、非白名单和草稿读写；`UiFlowTest` 新增两条界面流程：检查更新，未确认后重建 Activity 再重试且 ID 不变；崩溃提示进入反馈并附带诊断、分享交给其他应用、提交内容不含异常消息、再次启动不再提示。
- 界面测试修正：输入后先收起键盘再点击；诊断内容很长时“分享”按钮会被挤出可见区域，因此分享和复制按钮移到诊断内容上方。
- P5：版本改为 0.3.2（versionCode 7），更新 release notes 和中英 README。完整 CI 通过：JVM 60 项，仪器/界面 `OK (37 tests)`，通过记录 tree `146e755`。
- 发布问题与修正：第一次发布时，中文弯引号 `“”` 被 PowerShell 当成参数引号，说明文字的后半段落进了 `-BaseUrl`，版本索引里的 `downloadPageUrl` 因此出错。应用会拒绝白名单外的地址，只会显示检查失败。已改用「」重新发布，并给 `-BaseUrl` 加了 https 格式校验。另外，通过管道把脚本传给 ssh 时，最后一行多了 CR，暂存目录没被清理；现在远端命令改为作为 ssh 参数传递，重新发布后确认没有遗留。
- 部署（2026-10-01，用户授权随 0.3.2 一起部署）：VPS 新增系统用户 `feiyu-feedback`，服务 `/opt/feiyu-feedback/server.py` 监听 127.0.0.1:8787，数据库 `/var/lib/feiyu-feedback/`（0700），每日清理 timer 已启用。Caddy 站点块由 vps-management 维护（`caddy/feiyunote.caddy`，提交 `b62ec80`、`abdb4b1`）：以 caddy 用户校验后通过 admin API reload（该单元没有 ExecReload）。HTTPS 证书已签发；根路径 302 跳到 `/feiyu/`；反馈路径不写访问日志（访问日志中计数为 0）；原有 `vps`、`groceries`、`cpr` 站点仍为 200。仓库内的 Caddy 样例已删除，改为指向 vps-management。

## 1.0 稳定开发目标

日期：2026-10-01。状态：已确定方向，尚未完成版本验收，发布日期未定。1.0 聚焦现有 Android 应用的稳定开发，围绕可拍照的手机、平板与折叠屏，打磨拍照采集和随手记录的日常使用流程。产品范围见 [spec §10](spec.md#10-后续方向)，0.3.2 按既定计划推进。

- 持续修复实际使用中的崩溃、数据丢失和交互问题，完善拍照、选图、输入、保存与回看流程；随手记录入口的具体改进按试用反馈确定。
- 沿用现有 Kotlin/Compose 与 Android 工程，优先保障已有笔记、图片、设置及版本升级兼容性。
- 1.0 验收以核心学习流程回归和真机试用为依据：拍照或选图后能完成记录与提问，重启后内容仍可读取，断网、取消和请求失败不损坏已保存内容，离线阅读、公式显示与 HTML 导出可用。发布前收口影响核心流程的已知问题，并记录实际验证结果。

## 2.0 iOS 长期目标

2.0 长期目标为支持 iOS，延续拍照采集、随手记录与本地学习笔记的产品方向。当前尚未启动，发布日期未定；用户已有 Mac，可供后续开发验证。

实施前再评估 Kotlin Multiplatform / Compose Multiplatform 的复用范围，以及相机、图片、存储、凭据和公式渲染的适配方式，确定支持设备与验收条件。跨设备同步单独确定范围。

## 执行记录

### 0.3 多图附件（2026-10-01）

- 系统多选、拍照追加、逐张移除和导入反馈复用现有附件目录与请求链路；未增加相册读取权限或依赖。数据库 v1 → v2 保留旧图片、文字与关系。
- `scripts/ci.ps1 -Full` 通过：JVM 20 项，仪器/界面 runner `OK (33 tests)`，真实 API 冒烟默认跳过。新增覆盖旧库迁移、多图保存/删除、请求顺序、完整重试与缺图拒绝；界面覆盖多选、坏图、移除、拍照追加、取消及 Activity 重建。截图 `build/ci/screenshots/multi-photo-phone.png` 已检查。
- 本地签名 `assembleRelease lintVitalRelease` 通过，`apksigner` 验签成功，release 的 `debuggable=false`。沿用原发布签名。
- 断线测试发现 OkHttp 默认连接重试可能重发请求，已禁用并断言失败请求只发送一次。


以下为截至 2026-10-01 的验证结果，不代表所有真实设备场景均已验收。

### 0.2 本地功能与安全

- 完整命令 `pwsh -NoProfile -File scripts/ci.ps1 -Full -Serial emulator-5556 -Avd Feiyu_CI_API36` 通过；JVM 单测 19 项，仪器/界面 runner 报告 `OK (30 tests)`，其中真实 API 冒烟默认跳过。
- 自动化覆盖问答、追问、展开、整理、笔记编辑/导出/分享、相机相册打桩、刷题本、模板、归档删除、冷启动恢复、单双栏切换与草稿保留，以及首选语言和随机/自定义头像。
- 公式覆盖行内/独立公式、分式/根号/积分/矩阵、原文复制、编辑预览、无效语法回退与离线导出。窄屏截图位于 `build/ci/screenshots/math-chat-phone.png`；长公式位图宽度有渲染测试，未单独验证横向滑动手势。
- 独立合成 Key 验证密文落盘、随机 IV、Keystore 密钥不可导出、私有文件 UID/权限、篡改拒绝、密钥丢失后重新输入、备份排除与 FileProvider 隔离；界面测试检查 `FLAG_SECURE`，JVM 测试检查配置字符串脱敏。未读取用户真实 Key。
- 2026-09-30 的真实 DeepSeek 冒烟以两张课堂截图完成 5 次调用，均为 COMPLETE，覆盖照片、照片加文字、附原图追问、展开与整理；这是历史接口验证，不代表真机安装验收。

### 发布与贡献 CI

| 版本 / 提交 | 验证证据 |
| --- | --- |
| [v0.3.2](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.2) · `a15aa06` | 本地完整 CI（JVM 60 项 + 37 项仪器/界面测试）、[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36910541403)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36910554851) 通过。Release APK 已下载验签：证书 SHA-256 `10caccf5…d88b` 与 0.3.1 一致，versionCode 7、versionName 0.3.2，可覆盖安装。同一 APK 已发布到独立下载页，四项线上检查通过：裸域名 https/http 均跳到 `/feiyu/`、下载页、APK（字节与 Release 一致，MIME 为 APK）、版本索引（0.3.2/7，下载页地址正确，no-cache）。正式反馈接口实测：首次 201、同 ID 重试 200 且编号相同，维护者按编号取回 `b530317f-…` 合成报告（30 天后自动清理），访问日志中反馈路径 0 条。真机下载安装与应用内检查更新尚待人工确认。 |
| [v0.3.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.1) · `a40627a` | 同版本覆盖发布：首发 `cd1afdf`（versionCode 4）后，按用户要求将公共聊天卡片与专属横幅、可替换首页插图、预装引导式讲解模板、内置提示词编辑与 Skill 文字导入并入 0.3.1；每次覆盖都删除原 Release、移动 tag 并升 versionCode，当前为 6。本地完整 CI（JVM 单测 + 35 项仪器/界面测试，含数据库 v3 迁移、内置提示词覆盖、公共聊天横幅/编号/跳转/会话 effort）、[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36903801704)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36903803186) 通过。实际 Release APK 已下载验签，证书与 0.3.0 一致，versionCode 6、versionName 0.3.1、非 debuggable。TB321FU 与 PHP110 均已覆盖安装 versionCode 6，PHP110 首页与公共聊天横幅显示正常。Skill 网络导入仅有 URL/解析单测，未连真实 GitHub 验证。 |
| [v0.3.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.0) · `0f440de` | [main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36884434892)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36884438952) 通过。实际 Release APK 已下载验签，包名 `com.feiyu.notes`、versionCode 3、versionName 0.3.0、debuggable=false。TB321FU 已安装；手机安装与真机界面检查待系统交互，见 Wayfinder。 |
| [v0.1.0-alpha.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1) · `9fd23ea` | [main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36784905890)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36785654962) 通过，签名预览 APK 已发布。 |
| [v0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0) · `22b5331` | 本地签名 release 构建及 `lintVitalRelease`、[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765077)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765078) 通过。下载实际 Release APK 后用 `apksigner` 验证签名，`apkanalyzer` 确认包名 `com.feiyu.notes`、versionCode 2、versionName 0.2.0、debuggable=false。 |
| 贡献流程 · `287191f` | [CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36835816719) 单测、debug 构建和 APK 上传通过，main 推送正确跳过 release；CODEOWNERS 检查无错误，`approved` 标签已建立。 |

贡献规则见 [CONTRIBUTING](../CONTRIBUTING.md)。PR 事件及只读权限已检查配置，尚无外部 PR 的实际运行记录。CODEOWNERS 只请求评审，未启用分支保护或强制审批门禁。签名备份位于仓库外 `../肥鱼笔记-private/signing/`，不得提交。

未完成的真机、折叠态与环境检查，以及已知显示/附件问题，统一保留在 [Wayfinder](wayfinder.md)。

## 课程复习记录实施边界与验证（试验阶段）

日期：2026-10-02；2026-10-06 已随 0.3.3 发布。产品范围以 [spec §12](spec.md#12-课程复习记录试验) 与 [memory-design §1](memory-design.md#1-课程内复习记录试验当前阶段实施规格) 为准。最终验证和发布证据见下方「0.3.3 课程复习发布验证」。

### 架构与变更边界
- 数据库版本：`VERSION` 由 3 增至 4，保留已有的 v1→v2→v3 升级链。
- 新增 `review_records` 表及索引 `idx_review_records_notebook`。
- 级联与删除语义：`notebook_id REFERENCES notebooks(id) ON DELETE CASCADE`；删除问答线程或课次时，关联的 `review_records` 显式标记 `source_deleted = 1`，避免可误导的有效跳转。
- 数据校验：`insertReviewRecord` 强校验 `notebookId` 归属 COURSE 课程，且 `sourceEntryId` 必须属于同一课程，防止跨课污染；对同一来源防重复插入。
- 界面流：`ChatScreen` 与 `NoteScreen` 提供「加入复习」；`LessonListScreen` 提供「复习记录」入口导航至 `CourseReviewScreen`，支持状态切换、编辑、删除与跳转回来源。
- 零模型请求：纯本地 SQLite 操作，无模型调用，不修改现有的提示词与请求流。

## 0.3.3 课程复习发布验证

日期：2026-10-06。基线：PR #2 合并提交 `c370aaf`，实际工程与远端为 `E:\Projects\Opensource\肥鱼笔记`、`Yongzhaooo/FeiyuNote`。版本为 0.3.3 / versionCode 8，数据库 v4；沿用现有签名和 GitHub 预览版、独立下载站双渠道。

- 本地 CI 接入 PR 的 `parse-test-runner.py`，检查 ADB 退出码、runner 逐项状态和必须执行的数据层、生成器、界面测试类；增加四张复习界面截图的收集。Python 子进程显式启用 UTF-8。
- 原测试在双栏模拟器出现两个「返回」按钮，导致四个新增用例选中歧义；改为复用系统返回辅助方法，保留原行为断言。定向复测四项全部通过。
- 解析器已有七项合成日志回归全部通过；JVM 单测 76 项通过。最终 `scripts/ci.ps1 -Full` 通过：76 项 JVM 单测，设备 runner 55 项（54 通过、1 跳过、0 失败），涵盖 6 个测试类，九张截图已提取；复习列表和来源删除状态截图已检查。
- 原有未提交 `docs/tutorial/` 和 `output/` 属于用户工作，不纳入本次发布提交。

- 签名 ssembleRelease lintVitalRelease --no-configuration-cache 通过；实际 APK 包名为 com.feiyu.notes、0.3.3 / 8、minSdk 26、debuggable=false。证书 SHA-256 与已发布 0.3.2 相同（10caccf5…d88b）；本地 release APK SHA-256 为 7e3024c52b147b6242be9be73bc5ab75f20d74d5a1d4ee0f2f67e6661ccfedc9。日志位于 build/ci/，产物位于 app/build/outputs/apk/release/app-release.apk。
- 本次未执行真实 DeepSeek 请求、真机浏览器下载或真实折叠态验收；设备 runner 跳过项为默认关闭的真实 API 冒烟。
- 发布提交 `0d9a86c6555c79e9affc585b438f17f541c9b707` 已推送到 main，标签 `v0.3.3` 指向该提交。[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/37472672999) 与 [tag 构建、单测及签名发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/37472673231) 均通过；[GitHub 预览版](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.3) 已发布。
- 下载实际 Release 产物 `build/release-v0.3.3/feiyu-notes-v0.3.3.apk` 验签通过，0.3.3 / 8、minSdk 26、debuggable=false，证书与 0.3.2 一致。其 SHA-256 为 `2285f71387e314a85061476f08bd313c6c20ca338eb75a25010f6dfd8ced0c6e`；本地构建与远端构建的 APK 字节不同，公开双渠道统一使用远端 Release 产物。
- 同一 Release APK 已用 `scripts/publish-download.ps1` 发布至 `yz_vps:/srv/feiyunote`，更新索引最后原子切换。线上 [下载页](https://feiyunote.cangming.fyi/feiyu/) 和 APK 下载均为 HTTP 200，APK MIME 正确，实际下载 SHA-256 与 Release 相同；[更新索引](https://feiyunote.cangming.fyi/updates/android.json) 为 0.3.3 / 8、minSdk 26、正确下载页地址，Cache-Control 为 no-cache。

## 设置拆分与界面风格（2026-10-06，未发布）

日期：2026-10-06。基线 `dc2e306`（main），分支 `feat/settings-split-cute-ui`，改动尚未提交。产品行为见 [spec](spec.md#设置拆分与界面风格2026-10-06)。

- 实现：新增 `K/ui/KeySettingsScreen.kt` 与导航键 `KeySettingsKey`，FLAG_SECURE 从设置首页移到密钥页。保存或清除 Key 仍调用 `ApiSettings.save`，并传入当前模型与推理强度；设置首页“AI 讲解”用 `save(null, …)` 只保存模型，不动 Key。新增 `K/ui/Decor.kt`，集中放置配色徽章、贴纸、泡泡和打字点等共用组件；`theme/Theme.kt` 换成鲸鱼娘配色与更圆的形状，另加 12 个线性图标。
- 新建课次改为浮动按钮，使用带内容插槽的 `ExtendedFloatingActionButton`。带 `text`/`icon` 参数的写法不会把标签放进无障碍树，界面测试和读屏都找不到它。
- 测试：`UiFlowTest` 新增 `keyPageIsTheOnlySecureScreenAndModelSavesKeepTheKey`，覆盖设置首页可截屏、密钥页禁止截屏且离开后恢复、合成 Key 保存后不显示明文、保存默认模型不清除 Key、清除 Key 需确认且不改模型、返回设置后状态卡同步。原 FLAG_SECURE 断言移入该用例，模板入口改为先滚动再点击；CI 截图清单增加 `home-general-chat`、`settings-phone`。
- 验证：`scripts/ci.ps1 -Full` 通过，JVM 单测 76 项；设备 runner 56 项（55 通过、1 跳过即默认关闭的真实 API 冒烟、0 失败），涵盖 6 个测试类，11 张截图已提取并检查。首轮 `-Full` 因浮动按钮标签不在无障碍树中失败 14 项（13 项卡在点击“新课次”，1 项为连带失败），修复后重跑全部通过。之后只改了笔记页提示文字颜色和 import 顺序，快速 `scripts/ci.ps1` 通过。
- 模拟器人工检查（1080x2300）：浅色与深色的首页、课次列表、空状态、设置首页和密钥页。密钥页的 `adb screencap` 为全黑，说明禁止截屏生效，页面效果改用模拟器宿主截图查看。检查后已删除演示数据，并恢复主题和分辨率。
- 产物：debug APK `dist/feiyu-notes-0.3.3-dc2e306-dirty-debug.apk`，SHA-256 `4de32414a6bcc408e6058cb657b81f2102fc447ec0f50d94710139218e15add9`；未改版本号，未做签名 release。
- 平板预览：经用户同意，用正式签名在本地打包 `app/build/outputs/apk/release/app-release.apk`（SHA-256 `2bfecce7bbfb7e1619415b27fa4428057124d3a2de9257fc9007b57c224bd3a0`，证书 `10caccf5…d88b` 与已发布版本一致，版本仍显示 0.3.3 / 8，不可调试），已在 TB321FU 上从 0.3.1 原地覆盖安装：首次安装时间不变，启动后在前台运行，崩溃日志为空。该 APK 未复制到 `dist/`，也未发布；观感待用户确认。
- 未执行：真实折叠态、真实 DeepSeek 请求，以及提交、推送和发布。
- 追加（同分支）：提示词按语言加载、聊天/学习开关、公共聊天“鱼鱼”人设。`StudyPrompts.kt` 改为 `PromptSet` 中英两套，`StudyPrompts.of(language)` 取用，`PromptKind.default(language)`；`ContextBuilder.buildTurn`/`buildSummary` 增加 `language` 参数；`Generator` 按 `AppLanguage` 取语言，并对未编辑的预装引导式模板调用 `localizedTemplate`。`AppPrefs.prompt(kind, language)`，`setPrompt` 收到任一语言的默认文字时删除覆盖值；新增 `Features(chat, study)`，键为 `chat_enabled` / `study_enabled`，读到两项都关时按都开处理。界面改动在 `SettingsScreen.kt`（`FeatureSettings`）、`NotebookListScreen.kt` 和 `MainActivity.startStack`。测试：`ContextBuilderTest.builtInPromptsFollowTheLanguage`；`UiFlowTest` 新增 `chatOrStudyCanBeTurnedOffButNotBoth`，公共聊天用例的人设断言改为“鱼鱼”。验证：JVM 单测 77 项通过；`scripts/ci.ps1 -Full` 在改断言前跑了一轮，56 项中 2 项失败：一项是预期的“通用助手”旧断言，另一项是 `courseReviewArchivedAndEditDeleteWorkflow` 在等第一条回答时超时（与本次改动无关）。修复后重建 APK，单独重跑这两项、新增的开关用例和高级提示词用例，4 项全部通过。平板 TB321FU 已覆盖安装新的正式签名 release（SHA-256 `b922b78ce1709ffc88bfb61b0d19d49998da39756c61af811a57e879729a446b`，证书同上），首次安装时间不变，启动正常。
- 追加：关闭学习时首页和平板右侧空白栏不再显示学习欢迎卡片。设置首页新增交流群卡片（`GroupCard`，testTag `settings-qq-group` / `settings-copy-group`），`discussion_title` 中文改为“交流群”。`keyPageIsTheOnlySecureScreenAndModelSavesKeepTheKey` 增加群号显示与复制断言；帮助页用例改为按 `qq-group` 标签断言。相关 5 项界面用例在模拟器上通过。平板再次覆盖安装 release（SHA-256 `44570843557edf55a334232c105858f4b37c7ff52f479630ed62917606b8bb7a`，证书不变），启动正常。
- 下载页：`site/feiyu/index.html` 改为鲸鱼娘配色和鱼鱼问候，新增交流群卡片（复制按钮带降级）。经用户要求，只更新了线上页面：用线上 0.3.3 APK 和更新说明在本地渲染，确认 SHA-256、大小、文件名与线上一致；再把 `index.html` 原子替换到 `yz_vps:/srv/feiyunote/feiyu/`，旧页备份为 `index.html.bak-20261006`。APK 和 `updates/android.json` 未改动。线上页面 HTTP 200，内容哈希与本地渲染一致，APK 链接 200。模拟器 Chrome 中按 1080x2300 检查过排版，深色模式未看。

## 0.4.0 发布

日期：2026-10-06。基线 `dc2e306`（main），功能提交 `b8bba05`（分支 `feat/settings-split-cute-ui`，快进合入 main）。版本 0.4.0 / versionCode 9，数据库仍为 v4，没有迁移。内容见上方“设置拆分与界面风格”一节及其追加记录。

- 用户在 TB321FU 上试用覆盖安装的预览包后确认发布。
- README 新增界面预览：五张截图由新增的 `UiFlowTest.showcaseScreenshots` 在 1080x2300 模拟器上生成（仅 `-e showcase true` 时运行，平时跳过），缩到 540 宽后放在 `docs/screenshots/0.4/`。截图内容为模拟回答生成的演示数据。README 待做新增：贴纸未打标签导致随机性过强；考虑允许自定义鱼鱼头像。
- 本地验证：`scripts/ci.ps1 -Full` 通过，设备 runner 58 项（56 通过、2 跳过：展示截图和默认关闭的真实 API 冒烟，0 失败），6 个测试类；JVM 单测 77 项通过。
- 发布：`e3fe0cc` 已推送到 main（pre-push 完整 CI 再次通过，58 项同上），标签 `v0.4.0` 指向该提交。[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/37532849477) 与 [tag 构建及签名发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/37533363475) 均通过，[GitHub 预览版](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.4.0) 已发布。
- Release 产物 `build/release-v0.4.0/feiyu-notes-v0.4.0.apk` 验签通过：0.4.0 / 9、minSdk 26、debuggable=false，证书 `10caccf5…d88b` 与以往版本一致，SHA-256 `4a5d03e31c35698d6e2711df5fa9253364bd982bacd75cdda16c7cf5dbeed7e1`。
- 同一 APK 用 `scripts/publish-download.ps1` 发布到 `yz_vps:/srv/feiyunote`。线上下载页显示 0.4.0 和交流群；APK 为 HTTP 200、MIME 正确，下载后 SHA-256 与 Release 一致；[更新索引](https://feiyunote.cangming.fyi/updates/android.json) 为 0.4.0 / 9、minSdk 26，Cache-Control 为 no-cache。
- TB321FU 已覆盖安装 Release 产物，版本 0.4.0 / 9，首次安装时间不变。
- 未执行：真实 DeepSeek 请求、真实折叠态、深色模式下的下载页检查。

## 五项 Issue 的评估与实施计划（2026-10-12）

本节先记录规划：用户确认五项指 #1、#3、#4、#5、#6；#1 仅考虑尚缺部分，并确认 #6 沿用单个 SQLite 数据库，严格按课程隔离。随后用户授权执行 #3—#6，并在 AGY 交付质量不足后要求主线程直接接管。以下规划保留为决策依据，实际实现和验收状态见末尾执行记录。

### 基线与需求状态

应用源码在 `E:\Projects\Opensource\肥鱼笔记`，当前聊天目录 `E:\Projects\Opensource\FeiyuNote` 是素材 checkout。应用 HEAD 为 `72d2eb5af23783c982e8d842f28d56a5ec84bcc5`，分支 `feat/settings-split-cute-ui`；实际 origin 是 `Yongzhaooo/FeiyuNote`，与 AGENTS.md 中的历史 fork 说明不同。版本 0.4.0 / 9，数据库 v4，minSdk 26。

规划开始时，`UiFlowTest.kt` 已有用户改动，另有未跟踪的 `docs/superpowers/`、`docs/tutorial/`、`output/`、`scripts/make-promo.py`。本轮只追加本节，不改动这些内容。后续实现前重新核对基线和工作区，不在当前工作区直接切分支。

2026-10-12 读取 GitHub 全部 Issue、正文及评论，并核实 PR #2 的合并状态：

| 需求 | 当前事实 | 评估与安排 |
| --- | --- | --- |
| [#1 课程复习记录](https://github.com/Yongzhaooo/FeiyuNote/issues/1) | 作者 XePWang；Issue 仍 open，但 [PR #2](https://github.com/Yongzhaooo/FeiyuNote/pull/2) 已于 10 月 6 日合并，功能随 0.3.3 发布 | 原始 CRUD、三态、课程隔离、来源回查已实现。维护者评论中的随机知识卡片是后续方向，列为 P5 候选，不重做已交付功能 |
| [#3 参考笔记空状态](https://github.com/Yongzhaooo/FeiyuNote/issues/3) | 作者 Yongzhaooo；open；assignees 为空，无评论 | 收益直接、风险低、无模型开销。P1，最先交付 |
| [#4 日期与主题标题](https://github.com/Yongzhaooo/FeiyuNote/issues/4) | 作者 Yongzhaooo；open；assignees 为空，无评论 | 改善课次查找；有显式模型调用与取消/确认状态。P2，接在 P1 后 |
| [#5 随堂手记](https://github.com/Yongzhaooo/FeiyuNote/issues/5) | 作者 Yongzhaooo；open；assignees 为空，无评论 | 课堂记录价值高，涉及持久化、发送快照与迁移。P3，作为知识库接入前的数据与上下文基础 |
| [#6 课程知识库](https://github.com/Yongzhaooo/FeiyuNote/issues/6) | 作者 Yongzhaooo；open；assignees 为空，无评论 | 阅读和检索两种模式都交付；成本最高。P4a 先验证中文 FTS，P4b 完成手册，P4c 完成提问联动 |

空 assignee 不能证明没有人在私下开发。#3 至 #6 的公开页面目前没有认领证据；#1 已由贡献者实现。以上状态不等同于批准、认领或编码授权。

既有 `docs/superpowers/plans/2026-10-11-issue-3-4-5-features.md` 是未提交草案。本节作为五项计划入口，保留其产品意图，替代其中任务分解与接口建议：空状态的 JVM 布尔断言不能证明 UI；Lesson 默认字段测试不能证明迁移；当前 Generator 没有 `rawGenerate`；草案中的 12 字标题上限与 Issue 的 4 至 8 字不同。后续执行采用本节，不继承强制逐项提交或额外代理要求。

### 共享边界与接口

下文 `K` 为 `app/src/main/java/com/feiyu/notes`，`U` 为 `app/src/test/java/com/feiyu/notes`，`A` 为 `app/src/androidTest/java/com/feiyu/notes`。标有“新建”的路径当前不存在。每个纵向工作项包含自身代码、测试和中英说明。

1. 复用 NotebookStore、Generator、ContextBuilder、Navigation 3、MathContent。普通问答只在发送时本地取资料；编辑、展开、读手册和切换页面不触发模型请求。标题、问答、整理共用 Generator 的单请求限制、配置加载、错误映射与取消。
2. 课程知识库只用于 `COURSE` 且 `notebookId != GENERAL_ID` 的课程。刷题本的既有“引用关联课程笔记”行为保持不变，首版不自动读取关联课程知识库。复习记录继续纯本地，不注入普通问答。
3. #4 与 #5 的课次入口先覆盖课程和刷题本，公共聊天保持原入口。#6 专用于课程。数据层复核 notebook/lesson 归属，不能依赖 UI 隐藏入口。
4. 迁移顺序建议为 v4 → v5（P3 手记及请求快照）→ v6（P4b/c 手册及索引）。保留 v1/v2/v3 升级链，不删除重建用户库；版本由同一数据负责人管理。执行前发现版本已被占用时重新分配版本，暂停相关迁移工作。
5. 单库隔离已获用户确认。手册、章节和书签由普通表保存，使用外键与课程归属约束；FTS 只是可重建索引。检索必须连接实体表并约束 `notebook_id = ?`，之后才能排序和 LIMIT，不能先跨课程取 top-2 再过滤。索引更新和实体写入同一事务，课程删除时实体、书签和索引一起清除。
6. 新材料的开关与来源在发送前可见。建议“附带本课手记”默认开启；资料首次导入确认页让用户选择启用课程知识参考，此后在课程内保存开关。关闭时请求完全不包含对应材料。

建议固定的生产者/消费者接口如下。接口调整只暂停受影响项，先更新本节再继续执行。

| 接口 | 生产者与消费者 | 建议契约与失败行为 |
| --- | --- | --- |
| `Lesson.scratchpad: String = ""` | P3 数据层 → StudyViewModel/编辑区/整理 | 新课与旧课默认空串；不是 NOTE，不混入参考笔记列表 |
| `updateScratchpad(notebookId: Long, lessonId: Long, text: String): Boolean` | StudyViewModel → NotebookStore | 事务内校验课次归属；保留全文和空串；目标失效返回 false，不重新创建课次；IO 异常保留草稿并显示保存失败 |
| `Entry.requestContext: TurnContext` | P3/P4c 发送入口 → 持久化用户 Entry → Generator/ContextBuilder/重试 | 新建 `K/study/TurnContext.kt`（也可在 Models.kt 定义数据类）；SQLite `entries.request_context` 保存 UTF-8 JSON，空默认 `{}`。v1 数据形状为 `version=1, scratchpad: String?, knowledge: List<KnowledgeContext>`；旧条目解码为空上下文，损坏数据报错，不伪造空成功 |
| `KnowledgeContext` | P4c 数据层 → TurnContext | `notebookId, documentId, documentRevision, sectionId, headingPath, markdown`；存完整命中小节及来源版本，最多两条。数据层复核所有 ID 的课程归属 |
| `ContextBuilder.buildTurn(..., requestContext: TurnContext = empty)` | 已保存 Entry → 纯请求组装 | 手记和 `<课程知识>` 是参考资料，提示词明确不执行资料中的指令；标签与边界字符转义。不改变祖先链、图片与显式参考笔记规则 |
| `ContextBuilder.buildSummary(..., scratchpad: String? = null)` | Generator → 整理 | 获取本课已保存手记快照和未归档已完成问答；不加入其他课手记、课程知识库或复习记录；来源 Entry ID 仍只记录真实问答 ID |
| `Generator.suggestTitle(notebookId, lessonId): StartResult` | P2 UI → 共用生成入口 | 不创建聊天 Entry，不自动改标题。建议新增状态 `GenerationStatus.titleSuggestion`，包含目标 ID 和候选标题；StudyViewModel 只消费匹配课次的结果。新增 operation=TITLE 与诊断枚举，保留单请求与取消行为 |
| `readCourseHandbook(notebookId)` / `saveCourseHandbook(notebookId, markdown, expectedRevision)` | P4b 编辑/导入 → NotebookStore | 每课程一份资料，版本递增；冲突返回 Conflict，保留编辑稿，避免覆盖新版本；导入失败不动原资料 |
| `searchCourseKnowledge(notebookId, question, limit = 2)` | P4c Generator → 数据层 | 参数化查询，limit 固定约束 1 或 2；无命中返回空列表。索引故障显示参考不可用，发送前允许用户关闭参考继续，不能悄悄扩大课程范围 |

请求预算由 P3 定义，P4c 消费：建议完整文本输入上限 60,000 个字符，统计基础提示、模板、参考笔记、问题、手记、课程知识及历史；这只是本地字符预算，不声称精确等于模型 token 上限。问题、模板和显式参考材料优先保留，历史沿现有规则从最旧开始移除并保持问答起点。手记与知识库合计最多 12,000 字符，知识小节整条保留或整条舍弃；手记超限提示修改或关闭附带，不静默截断。单个小节无法完整放入时不注入并给出可见提示。预算规则用合成输入验证，不靠真实计费 API 验收。

新提问在保存用户 Entry 时冻结附带手记和课程知识快照，进行中继续编辑不影响该请求。重试复用原提问的开关与快照；手记后续编辑不改变旧提问。知识资料版本变化或删除时，重试需先提示参考已变化，让用户显式移除旧参考或作为新提问使用新资料，不悄悄重新检索。历史快照保留于原提问，不能因为资料删除又被自动发送。整课删除按现有 Entry 级联规则处理。

### P1：参考笔记选择器空状态（#3）

- 结果：无参考笔记时，在“不用参考笔记”下方显示 Issue 的中文引导；英文与公共聊天使用对应“整理对话”文案。已有笔记时隐藏引导。
- 范围与归属：修改 `K/ui/ChatScreen.kt::Composer/ChoiceDialog`、`res/values/strings.xml`、`res/values-zh/strings.xml`；验收修改 `A/ui/UiFlowTest.kt`，先保留和理解该文件既有用户 diff。不改数据库和生成路径。
- 步骤：为 ChoiceDialog 加可选空提示参数，只由参考笔记调用点提供；模板选择器保持原样。复用实际整理动作的本地化名称，避免引导找不到按钮。
- 验证：真实 Compose 用例打开空课程选择器，看到“不用参考笔记”和引导；添加一条整理笔记后引导消失、条目可选；模板弹窗不出现此文案。上述打开/关闭过程 fake 模型调用计数为 0，原无参考选择语义不变。
- 停止条件：若实际 UI 已有更改或整理入口位置与 Issue 不符，先调整文案对应关系；不得为本项重做参考系统。

### P2：显式提炼日期与主题标题（#4）

- 结果：点击课次标题打开重命名弹窗，支持本地手动改名；点击“提炼主题”才请求模型，展示候选，用户确认才写入 `lessons.title`。
- 范围与归属：修改 `K/ui/ChatScreen.kt`、`K/study/StudyViewModel.kt`、`Generator.kt`、`StudyPrompts.kt`、`K/support/DiagnosticModels.kt` 和中英文资源；复用 `K/data/NotebookStore.kt::renameLesson`。新建 `K/study/TitleSuggester.kt` 与 `U/study/TitleSuggesterTest.kt`，扩大 `A/study/GeneratorTest.kt` 与 `A/ui/UiFlowTest.kt`。
- 步骤：先定义纯函数负责筛选摘录、构造指令与校验候选，再接 Generator。建议取本课最早三组未归档已完成问答，不带图片、笔记和兄弟链外材料，摘录最多 6,000 字符；日期优先保留现标题中合法 `YYYY-MM-DD` 前缀，否则取 `createdAt` 的设备本地日期，不使用生成当天日期。标题候选为“日期 + 一个空格 + 4 至 8 个中文码点主题”；异常、空白、多段解释或不合长度的结果保持原题并提示重试/手改，不靠截断制造有效主题。英文界面主题建议 2 至 6 个词，这是本计划新增的本地化默认值。
- 验证：候选不改变数据库，确认后课次列表和会话顶部同步，重开仍在；取消与网络失败不改原题；无已完成问答只允许手动改名，不发请求。快速双击只请求一次；问答忙时提炼返回 Busy；旋转不重发；删除课次后的迟到候选丢弃。日期前缀、无日期旧标题、非 BMP Unicode、超长或空模型输出分别有纯函数断言。fake 模型验证无新聊天条目。
- 停止条件：若要扩大到公共聊天、取消确认直接覆盖，或改变默认日期规则，先由计划负责人更新契约。Generator 的 Running 不再用 `isSummary` 猜所有动作，需要窄范围 operation 枚举并更新现有消费者。

### P3：随堂手记与问答/整理联动（#5）

- 结果：课次内可展开/折叠编辑纯文本或 Markdown 源码；保存到本地，提问可附带发送时的最新手记；“整理本课”融合手记和问答。手记与 AI 整理笔记分开保存。
- 范围与归属：修改 `K/data/Models.kt`、`NotebookDatabase.kt`、`NotebookStore.kt`、`K/study/StudyViewModel.kt`、`Generator.kt`、`ContextBuilder.kt`、`StudyPrompts.kt`、`K/ui/ChatScreen.kt` 和中英资源。测试归本项：`U/study/ContextBuilderTest.kt`、`A/data/NotebookStoreTest.kt`、`A/study/GeneratorTest.kt`、`A/ui/UiFlowTest.kt`；新建 v4 SQL fixture `app/src/androidTest/assets/notes-v4.sql`。
- 步骤：v5 新增 `lessons.scratchpad TEXT NOT NULL DEFAULT ''` 与 `entries.request_context TEXT NOT NULL DEFAULT '{}'`；更新 create/read/upgrade 的全部路径。StudyViewModel 管编辑稿、已存稿、保存中/失败状态。建议 500 ms 合并保存，写入串行且带编辑版本防止旧写覆盖新写；提供立即保存动作。发送、整理、正常离开/收起前 flush，flush 失败保留编辑稿并阻止依赖它的发送。短暂恢复用 SavedStateHandle，数据库保留已完成保存，不宣称突然断电前的未落盘字符也已保存。
- 验证：手记保存、清空、重开、旋转恢复；课程 A 的手记不进入 B；公共聊天无入口。关闭附带时问答请求不含手记；开启时与问题和图片同一请求；发送后编辑、失败重试仍使用已存快照。整理含手记与完成问答，不含归档问答；建议支持“只有手记也可整理”，两者都空才返回 summary_empty。保存失败保留稿，旧异步保存不能覆盖新文字，删除课次不被迟到保存重建。
- 迁移证明：在真实 Android SQLite 中执行 v1/v2/v3/v4 → v5；旧标题、ID、问答父链、图片路径、模板与复习记录保持，新增内容为空。新增 JSON 往返与损坏处理有断言。Lesson.copy 测试不能替代此检查。
- 停止条件：若要求完整富文本编辑器或导出手记，此项先更新产品范围。需要改持久化快照形状时暂停 P4c，保持 P4a 独立推进。

### P4a：中文 FTS 与章节完整性验证（#6 的前置项）

- 结果：用小型可运行仪器测试确定 Android 原生 FTS4 在 API 26 与现有 CI API 36 上可用，确认中文关键词确实能命中完整小节，再确定正式索引字段与查询规则。
- 范围与归属：新建 `A/data/CourseKnowledgeFtsTest.kt`，仅测试私有临时库和合成 Markdown；若需要分段/分词纯函数，新建 `K/study/CourseKnowledgeText.kt` 与对应 JVM 测试。此项不变更正式 notes.db schema。
- 步骤：验证 `CREATE VIRTUAL TABLE ... USING fts4` 与 tokenizer；对“特征值”“因果分析”“贝叶斯定理”、中英混合、公式、重复章节、无命中、含引号/OR/星号的问题检查召回和安全查询。候选实现为 Kotlin 生成中文相邻双字词与英文词的索引文本，再使用 FTS4；问题端使用同一规则，不直接把整句当 MATCH 语法。单字与纯图片提问无可靠关键词时返回空结果，不额外调用模型/OCR。
- 验证：固定 20 个有目标章节的问题，预期章节进入 top-2 至少 18 个；5 个无关问题不注入材料；两课程有相同词时仅返回指定课程。fixture 包含定理前提、结论和完整公式，返回原小节全文。20 个问题、预期章节和实测结果保留在测试/本节，先检查再确定排名阈值。另以 1 MiB、1,000 小节合成库记录查询耗时，建议 CI 热查询 p95 ≤ 200 ms，不把它当真机性能证据。
- 停止条件：API 26 不支持候选模块、召回未达标或不能保留前提时，停止 P4c 的正式实现，交回实测失败。可继续 P1 至 P3；不静默更换为向量库、联网检索或额外模型抽取。

SQLite 官方说明 unicode61 根据空白和标点分词；据此不能假定原始中文连续句子已具备词语召回，必须验证中文预处理。[SQLite FTS3/FTS4 文档](https://www.sqlite.org/fts3.html#tokenizer)是技术依据，实际设备结果由 P4a 提供。

### P4b：课程 Markdown 手册、章节导航与书签（#6）

- 结果：课程页面提供“课程资料”入口，每门课程导入、编辑和离线阅读一份 UTF-8 Markdown；可按大纲跳转、添加/移除书签，重启后书签保留，公式沿用原生渲染。
- 范围与归属：修改 `K/data/Models.kt`、`NotebookDatabase.kt`、`NotebookStore.kt`、`K/ui/LessonListScreen.kt`、`AppNavigation.kt` 与中英资源；新建 `K/ui/CourseHandbookScreen.kt`、`K/study/CourseHandbookViewModel.kt`、`K/study/CourseKnowledgeText.kt`（若 P4a 已创建则接续）。验收 `A/data/NotebookStoreTest.kt`、`A/ui/UiFlowTest.kt`、新建 `U/study/CourseKnowledgeTextTest.kt`。
- 建议数据形状：`course_documents(id, notebook_id UNIQUE FK, markdown, revision, updated_at)`；`course_sections(id, document_id, notebook_id, heading_path, markdown, ordinal)`；`course_bookmarks(id, notebook_id FK, section_id nullable, heading_path_snapshot, created_at)`。document/section 提供 `(id, notebook_id)` 组合唯一键，section/bookmark 的组合外键校验课程一致；所有实体保留直接的 notebook 级联删除外键。删除结构中的章节前，在同一事务把对应书签的 section_id 置空并保留标题路径快照，使其可以显示失效。FTS 最终结构待 P4a 冻结，P4b 持有 v6 schema 写权限并统一加入 P4c 索引定义，消费者不得各自升级 VERSION。
- 步骤：系统 OpenDocument 选择文件，读入上限建议 1 MiB，严格 UTF-8（允许 BOM），解码/超限失败显示原因且保留旧资料；不执行 HTML、脚本或加载远端图片。保存文档及分段在一个事务完成，保存失败保留旧版本与编辑稿。新导航 `CourseHandbookKey(notebookId, sectionId: Long? = null)` 只传 ID，按课程重查。
- 阅读与切分：先支持 ATX/Setext 标题、大纲、段落、列表、加粗/斜体和原生公式；代码块/复杂表格保留源文可读，完整 Markdown 语法支持不作为默认承诺。现有 MathContent 只处理文字和公式，不能直接声称已渲染 Markdown。新阅读组件只在本项复用 MathContent，不顺带重写聊天渲染。公式块和代码围栏内的 `#` 不能当标题；检索小节边界以一级/二级标题为主，三级以下与正文一起保留，章节路径及必要上级前导内容一并返回，防止拆开定理条件。
- 书签：按章节实体 ID 保存；仅正文变化且标题路径唯一不变时保留该 ID。重复标题使用层级路径与出现序号避免歧义；结构更换后无法确定映射的书签明确显示失效并可移除，不自动跳到不同章节。整份替换前预览资料和将失效的书签，确认才保存。
- 验证：中文/英文资料、所有四种已有公式分隔符、同名标题、无标题资料、公式中的 `#`、围栏标题、超宽公式；导入失败/保存失败不丢旧资料；从目录和书签跳转正确；重开、正常编辑后书签位置正确，结构删除后失效可见；A 课程不能用 B 的 sectionId 打开或写书签；删除课程清除所有实体及索引。浅/深色、大字号、手机/双栏可读。
- 停止条件：若提出多文档、PDF/OCR、外部图片、同步或完整 Markdown 兼容要求，更新范围与成本再实施。P4a 未冻结索引结构时可做阅读组件和解析测试，正式 v6 schema 等其结果。

### P4c：本地检索与提问上下文（#6）

- 结果：启用课程知识参考后，发送问题时本地检索最多两个相关完整小节，将来源章节和正文作为 `<课程知识>` 注入 System Prompt；阅读与检索共用同一资料版本，新增模型调用数为 0。
- 范围与归属：修改 `K/data/NotebookStore.kt` 的知识查询/事务索引操作、`K/study/Generator.kt`、`ContextBuilder.kt`、`StudyPrompts.kt`、`StudyViewModel.kt` 和 `K/ui/ChatScreen.kt` 的参考状态显示；索引 schema 由 P4b 数据负责人统一修改。复用 P3 TurnContext 与预算，验收 `U/study/ContextBuilderTest.kt`、`A/data/CourseKnowledgeFtsTest.kt`、`A/study/GeneratorTest.kt`、`A/ui/UiFlowTest.kt`。
- 步骤：采用 P4a 达标的同一分词/排名规则；读取课程开关和当前文档版本，检索、校验、保存提问快照时复核版本，版本冲突不发模型请求。最多两小节，标题/正文证据满足阈值才选择，无命中不注入。只用当前提问文本检索；纯图片问题仍按原识图路径发送。快照放入已保存用户 Entry，再交纯 ContextBuilder 组装，避免另造一条未持久化请求路径。
- 验证：fake 请求正文包含指定课程的 1 至 2 个完整小节和 `<课程知识>`，图片仍仅在 user 消息；其他课程、复习记录、兄弟分支和全库资料不出现。关闭/无命中不包含知识段，普通发送仍只有一个模型请求。预算边界整节舍弃、版本冲突、索引故障、删除后重试、资料指令注入样例分别验证；开关、重新进入界面、旋转不发请求。删除/替换资料后下一次检索不会命中旧索引。
- 停止条件：依赖 P3 快照/预算、P4a 的实测契约、P4b 文档/章节 ID 与版本。若某项未稳定，允许准备测试，暂停正式请求接入。

### P5：复习记录剩余方向（#1，候选）

#1 原始需求没有发现新的实施缺口。现有三态和手工新增是实际已发布行为，维护者早期“两态、暂缓手工新增”建议不能据此倒退已发布功能。[后续评论](https://github.com/Yongzhaooo/FeiyuNote/issues/1#issuecomment-5960794721)提出参考随机知识卡片，但没有确定交互和抽样规则。

- 建议结果：在现有课程复习页显式点击“随机复习”，本地从当前筛选结果抽一张已有 ReviewRecord；可看说明、回到来源、标记已理解，使用既有记录和状态，不调用模型、不新建卡片表。
- 建议范围与归属：仅 `K/ui/CourseReviewScreen.kt`、必要的 `K/ui/ReviewState.kt`、中英资源及 `U/ui/ReviewInteractionTest.kt`、`A/ui/UiFlowTest.kt`；既有 CRUD/schema 保持。窗口复用现有复习数据及来源状态。
- 建议步骤：直接从已加载的课程/筛选列表抽取，空集合显示“暂无可复习内容”；重新抽取有至少两项时不连续抽到同一项。已理解默认由当前筛选控制，不隐藏用户主动选定的记录。抽中后记录被删除则关闭卡片并提示，不跳到失效来源。
- 验证：空、单条、两条、跨课程隔离、筛选一致、状态更新、来源删除、当前记录删除；随机选择注入可控 Random 以稳定断言；所有动作 fake 模型调用为 0。
- 停止条件：这是新增设计提案。随机抽取是否应在筛选结果内、是否需要遮挡答案/复习间隔尚未获用户确认，实施阶段暂停 P5，先交付独立的 P1 至 P4。若最终只保持现有复习记录，则本项结案为“无新增开发”。

### 顺序、并行与收口

推荐交付批次为：P1 空状态 → P2 标题 → P3 随堂手记 → P4b/P4c 完整知识库；P4a 可提前验证。P5 不绑定该版本。各批次都可独立验收，但 P4 的完成条件同时包含手册阅读与 FTS 联动，不能仅以阅读器交付宣称 #6 完成。版本号、发布时间不在本轮决定。

| 工作项 | 可开始阶段 | 实施依赖 | 验证依赖 / 写入限制 |
| --- | --- | --- | --- |
| P1 | 下次获得实现授权后可开始 | 无 | 与 P2/P3 共用 ChatScreen、双语资源及 UiFlowTest，串行写入 |
| P2 | 同上，可先做纯函数与测试 | Generator operation/result 契约按上文落实 | 与 P3 共用 Generator/ViewModel，串行集成 |
| P3 | 同上，可先迁移与存储 | v5 版本和快照形状由数据负责人确认未占用 | 数据层可在 P2 UI 阶段推进；重叠资源/UI/Generator 统一由集成负责人串行修改 |
| P4a | 同上，可与 P1/P2/P3 并行 | 无，仅隔离测试库 | 单一模拟器上的仪器检查排队，不并发修改测试服务/设备 |
| P4b | 解析/阅读准备可开始 | 正式 v6 schema 等 P3 v5、P4a 索引契约 | 不与 P3 同时修改 Models/Database/Store/UiFlowTest；独立阅读组件可并行准备 |
| P4c | 可先按接口准备纯组装测试 | 正式实现等 P3 + P4a + P4b | 端到端验收等同一版本的文档、索引与请求；集成负责人拥有此连接验收 |
| P5 | 范围确认前只保留提案 | 用户确认随机复习产品范围 | 确认后可与 P4 独立实现；双语资源和 UiFlowTest 合入仍串行 |

这些关系描述可行并行度，不要求启动子代理。单人执行可完全按顺序完成。即使分开 checkout，共用模拟器和服务也不并行验证；若要多执行者，先分配独立文件写权限，数据库版本及连接验收只设一个负责人。

### 验收证据与交接

本轮已执行的是需求/评论/PR 状态查询、Git/构建配置核对和相关源码阅读；仅添加计划文档。下列都是后续计划检查，不表示本轮已通过功能、迁移或设备测试。

1. 各项实现者先跑自己新增或受影响的 JVM/仪器用例。界面行为由 Compose 真正打开页面断言；数据约束和迁移由 Android SQLite 验证；模型次数与输入由现有 fake generate 验证。新 fixture 只使用合成内容。
2. 集成负责人在完成当前批次后运行 `pwsh -NoProfile -File scripts/ci.ps1`；最终包含数据库/UI/生成器的批次运行 `pwsh -NoProfile -File scripts/ci.ps1 -Full`。全套证据只归收口项，不要求各角色重复跑。API 26 的 FTS 验证另行记录，不能用 CI API 36 结果替代。
3. Windows 文本和日志明确 UTF-8；Python 子进程设 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`。分别记录命令退出码、runner 逐项结果和实际产物，默认不调用真实计费模型。Markdown 文档改动本身仅检查 diff、内容与链接。
4. 实现相应批次时更新 `docs/spec.md` 和本节接受结果：#5 是显式附带人工手记，#6 是指定课程导入资料的本地检索，不能将旧“无 RAG/普通问答不注入”的阶段边界当作授权扩展复习记忆。更新 `docs/wayfinder.md` 当前进展与下一项；保留历史发布证据。
5. 保存交接：实际基线、分支/工作区、完成项、实测命令/结果、失败项、P4a 冻结契约、剩余依赖和下一就绪项。迟到结果、失效参考、迁移失败等用例失败时进入本项诊断和修复，不为普通失败另加审批。

本轮没有认领、发评论、提交 PR 或发布授权。若后续需要通知维护者/贡献者，先完成面向具体 Issue 的认领或协作评论草稿；发布评论步骤标记为“等待用户针对目标 Issue 和最终文本的明确授权”，发送是独立步骤。提交主线时按 CONTRIBUTING 核对实际范围批准；准备材料可先做，推送、PR、合并和发布按届时用户授权执行。

规划当时下一项为 P1；P5 的随机复习仍待产品范围确认。

### 执行记录：主线程接管（2026-10-12）

实际源码仍位于 `E:\Projects\Opensource\肥鱼笔记`，HEAD `72d2eb5`、分支 `feat/settings-split-cute-ui`。用户负责认领 Issue；本地实现没有代发认领、评论、推送、PR 或发布。原有 UiFlowTest 的 68 行改动，以及教程、输出和宣传脚本均保留。

AGY 完成 #3/#4 初稿，第三次调用仅部分接入 #5 的 schema。主线程按用户要求停止 Pi 及其专属 ACP 进程，调用退出后接管；未把 AGY 自报成功当作验收。原调用证据保留在忽略目录 `build/agy-execution/20261012-issues-3-6/`。

已完成本地代码：

- P1：参考笔记空状态及课程/公共聊天对应的整理动作，模板选择器不显示该提示。
- P2：课次标题手动编辑、显式提炼、候选确认；取消关闭、删除、忙碌和迟到结果处理，标题请求不写聊天条目。
- P3：可折叠随堂记录、自动及显式保存、随问题附带开关、发送快照和重试；仅有随堂记录也可整理。
- P4：UTF-8 Markdown 手册预览/编辑/阅读、目录、书签、参考开关、中文 FTS4 检索和请求联动。正文、章节、索引更新在同一事务；课程、来源版本及全文校验在数据层完成。知识块不跨课程，手册编辑/暂停/删除后的旧快照必须显式移除才能重试。
- 数据库 v4 → v5 → v6 → v7，保留早期升级链。v7 兼容本地早期 v6 测试包尚未包含手册开关字段的情况，升级保留原文、索引和书签。单库隔离使用复合外键和课程约束；删除章节使书签失效，删除课程清除实体和索引。

实现采用规划中的既有工具，无新增依赖。检索为中文双字及拉丁词的字面匹配，不承诺语义同义词检索。父章节的前提文字会带入子章节，按完整章节预算取舍。Markdown 显示范围见 spec 第 13 节；代码和表格保留可读原文。随堂记录默认附带，可关闭；课程手册保存后默认启用，可在手册页面暂停。模型输入仍由保存的用户 Entry 决定。

已取得的定向证据：

- JVM 单测及 app/test APK 编译通过，日志 `build/primary-build.log`。
- API 36 定向仪器测试 18 项通过：真实 SQLite、Generator fake 请求和新界面；日志 `build/primary-targeted-tests.log`。
- API 26 真实 SQLite 4 项通过：v4 迁移、手记/快照、数据库约束、课程隔离、来源与书签失效、中文检索；日志 `build/api26-course-tests.log`。
- 中文问题 top-2 命中 20/20，5 个无关问题全部无命中；完整定理前提和公式保留。1,021,891 字节 / 1,000 章节 / 20 次查询：API 36 p95 95 ms，API 26 p95 113 ms。模拟器数据不代表真机性能。
- 首轮 Full 测试 71 项中 2 项失败（正文重复分词导致 p95 1.15 秒、界面测试误用按钮文字），3 项可选测试跳过；修复后上述定向复测通过。最终 Full 回归待收口，不沿用失败的通过标记。

最终验收使用合成资料和 fake 模型，不调用计费 API；真机、真实 DeepSeek 和折叠铰链仍未验证。#1 已发布功能保留，P5 随机知识卡片未进入本次执行。

### 最终验收结果（2026-10-12）

- `scripts/ci.ps1 -Full`：退出码 0；96 项 JVM 单测全部通过，API 36 设备测试 73 项（70 通过、3 可选测试跳过、0 失败）。包含标题候选确认、取消、随堂记录快照、手册阅读/目录/书签、课程隔离、来源删除后的迟到响应与既有功能回归。日志 `build/ci/gradle.log`、`build/ci/instrumented.log`。
- API 26：`CourseFeatureTest,NotebookStoreTest` 共 31 项通过、0 跳过、0 失败，含 v1/v4/v6 升级、图片及来源关系、真实 SQLite/FTS、书签及课程约束。日志 `build/api26-course-tests.log`。近 1 MiB / 1,000 章节检索的已记录样本 p95：API 36 29 ms，API 26 27 ms；20 个中文问题命中 20/20，5 个无关问题无命中。
- 窄屏测试曾选中正文内同名「导数」文字，导致目录未关闭，返回检查失败；限定可点击目录项并等待对话框退出后复测通过，未改动导航实现。手机和平板截图已检查，见 `build/ci/screenshots/course-handbook*.png`、`scratchpad*.png`。
- Full 后仅把随堂记录入口限定在课程/刷题本，公共聊天保留既有入口；随后重新编译、跑 96 项单测，并定向验证公共聊天及课程随堂记录 2 项通过。日志 `build/primary-final-build.log`、`build/primary-final-ui-boundary.log`。其余实现沿用上述 Full 证据；源文件在 Full 期间有变更，因此不把旧 `full-passed` stamp 当作最终工作树证据。
- `git diff --check` 通过；原有宣传流程测试完整保留。分支仍为 `feat/settings-split-cute-ui`，未提交、推送或发布。

最终测试 APK：`dist/feiyu-notes-0.4.0-72d2eb5-dirty-debug.apk`，SHA256 `09CBD8434D6737DCEA88BC7D8BA8ED072DBCFA43B2A59AE036B2522ADDE02266`。这是本地 debug 构建，不更改已发布的版本。当前授权范围无剩余实现项。

### 英文支持与提示词验收（2026-10-12）

- 用户要求确认英文界面可用、内置 prompt 自动跟随应用语言，并授权推送 GitHub 运行 CI。继续使用 `feat/settings-split-cute-ui`，目标远端经核实为 `Yongzhaooo/FeiyuNote`；不触发标签发布。
- `StudyPrompts` 集中提供中英文学习、聊天、整理、预装引导模板、动作说明和材料标签。英文请求使用 `<course-knowledge>` 与 `<class-notes>`；中文标签保持兼容。学生原始笔记、手册内容、自定义 prompt 和编辑过的模板保留原文。
- `agy-assist` 使用配置的 Antigravity ACP `gemini-3.8-flash` / medium，仅审阅公开的 prompt 源码；exit 0 / ok true。参考 MyAgents creator 的输入、输出、边界和验证原则，主线程审阅后采纳英文聊天措辞、标题约束及中英文整理说明。支持仅有随堂记录的整理，保留条件和不确定性，不虚构对话或来源。预装模板文字不改，保留既有本地化判定。
- 新增 JVM 用例验证英文材料包装、转义、中文学生笔记保留、仅记录整理与无伪造来源；新增设备流程验证英文手册、参考笔记空态、随堂记录、英文标题建议、整理及公共聊天实际请求，切回中文不触发请求且不覆盖自定义 prompt。另保留系统首选语言回退和中文界面的既有设备测试。
- 本轮调试发现测试助手写死中文“确定”和摘要片段误用精确文本匹配，均在测试中修正；未更改产品导航来适配测试。
- 证据：`build/prompt-review/result.json`、`build/english-build.log`、`build/english-ui-final.log`；提交 hook 运行构建及单测，推送 hook 运行全部设备测试。GitHub 使用既有 `android.yml` 的 `workflow_dispatch` 在本功能分支运行单测/debug 构建；最终结果以实际 Actions 运行记录为准。
- 原有 promo 测试与 `docs/superpowers/`、`docs/tutorial/`、`output/`、`scripts/make-promo.py` 属于用户未提交工作，保留并排除本轮源码提交。
- 英文针对性验收结果：97 项 JVM 单测通过（0 失败/错误/跳过）；新增英文设备流程通过，覆盖 4 次显式请求（学习、标题、整理、公共聊天）；系统德语首选→英文、繁体中文→中文的既有设备用例也通过。使用 fake 模型验证请求正文和调用数，不等同于真实模型输出质量验收。
- 英文截图另发现旧库预装模板名称仍是中文：补齐模板列表、选择器、问答标记及编辑器的名称/说明显示，未改名的内置模板跟随语言；自定义名称与说明保留。保存模板时保留原来源标记，因此未修改内置说明的保存不会取消后续语言切换。课次列表标题限制一行，避免英文课程名被窄工具栏拆成单字母行。
- 补齐模板显示前的一轮 Full 已完成：74 项设备测试（71 通过、3 可选跳过、0 失败），7 个测试类，约 381 秒。此期间在工作区补齐模板显示，所以脚本未盖最终树章。SSH 连接因等待 hook 期间断开，推送退出 141；后续使用仅本次 Git 命令的 SSH keepalive 参数重试，不修改全局 Git/SSH 配置。最终补丁重新通过提交/推送 hook 后再交付 Actions 验证。
