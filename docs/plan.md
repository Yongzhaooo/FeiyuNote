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
- 未执行：真实 DeepSeek 请求、真实折叠态、深色模式下的下载页检查。
