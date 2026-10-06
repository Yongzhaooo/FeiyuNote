# 课程复习记录验收矩阵与真机准备报告 (COURSE-REVIEW-VALIDATION.md)

- **合并前基准**：`1af8e70`
- **上游主线目标**：`bbefb7931d03e6d9e5d4daf2a44cbca07fc63bbb`（含提交 `0e9c405` 与 `bbefb79`）
- **合并提交**：`e9513fd4132ca45f7ac81486464b54617f22015d`
- **目标分支**：`feat/course-review-records`
- **远端仓库**：`git@github.com:XePWang/FeiyuNote.git` (origin)
- **验证设备**：SHARP A101SH（Android 12 / API 31，serial `354xxx644` 脱敏）

---

## 1. 验收矩阵状态一览 (V01–V19)

| 编号 | 场景要求 | 成功判据 | 最低证据要求 | 当前状态 | 证据与代码位置 |
| --- | --- | --- | --- | --- | --- |
| **V01** | 已初始化 GENERAL、PRACTICE、关联刷题本 | 数据层拒绝且 UI 无入口；真实 COURSE 可用 | Store 设备用例 + UI 用例 | 通过 (真机 Store + UI 验证) | `NotebookStore.isCourseNotebook` 显式排除 `GENERAL_ID`；`ChatScreen` / `LessonListScreen` / `NoteScreen` 均隐藏入口；`NotebookStoreTest.reviewRecordRejectsNonCourseNotebook` 在真机通过。 |
| **V02** | A 携带 B 的 recordId 读/改/改状态/删 | 全拒绝，B 内容/状态/时间不变 | Store 设备用例 | 通过 (真机 Store 验证) | `NotebookStore` 统一要求 `notebookId` 并在 SQL 中施加 `AND notebook_id = ?` 约束；`NotebookStoreTest.reviewRecordCrossCourseSecurityEnforced` 在真机通过。 |
| **V03** | 跨课/失效/非完成来源、空主题 | 明确拒绝，不写入，不误报重复 | Store + UI 失败用例 | 通过 (真机 Store 验证) | `insertReviewRecord` 拒绝跨课来源、未完成 assistant 回复 (`PENDING`) 及空白主题；`NotebookStoreTest.reviewRecordRejectsBlankTopic` 与 `reviewRecordRejectsIncompleteAssistantSource` 在真机通过。 |
| **V04** | 三个新增入口和手工记录 | 保存、重启读回正确，来源归属可查 | Store 重开 + UI | 通过 (真机 Store + UI 验证) | `NotebookStoreTest.reviewRecordCrudAndReopen` 在真机验证增删改查与数据库重开持久化；UI 在 `CourseReviewScreen`、`ChatScreen`、`NoteScreen` 分别支持新增，并在真机 UI 测试 `courseReviewWorkflowFullCycle` 与 `courseReviewNoteSourceWorkflow` 实测通过。 |
| **V05** | 快速重复/并发保存 | 同来源唯一；重复不覆盖已有正文；无来源单次动作不重复 | 事务用例 + 延迟 UI | 通过 (真机 Store 查重 + UI 防重状态机验证) | `NotebookStore.insertReviewRecord` 在写入事务内查重，重复时返回 `ReviewInsertResult.AlreadyExists`；`NotebookStoreTest.reviewRecordDuplicateSourcePrevention` 在真机通过；`ReviewInteractionTest.concurrentSubmitIsBlockedWhileSaving` 验证保存中禁止并发重入。 |
| **V06** | 笔记/线程/课次/课程删除及回滚 | 文本/失效标记/级联符合规范，其他课程不变 | Store 设备用例 + UI 用例 | 通过 (真机 Store + UI 验证) | `deleteNotebook` 外键级联删除；`deleteThread`、`deleteLesson` 与 `deleteNote` 在事务内执行 `UPDATE review_records SET source_deleted = 1`；`NotebookStoreTest.sourceDeletedMarkedOnLessonOrThreadDeletion` 真机通过；`UiFlowTest.courseReviewArchivedAndEditDeleteWorkflow` 在真机实测删除来源后界面显示“来源已删除”并禁用跳转按钮。 |
| **V07** | 归档、取消归档 | 不当作删除，不自动解除归档，能查看对应来源 | Store + UI | 通过 (真机 Store + UI 验证) | 归档只作用于根 question；`isThreadArchived` 递归识别归档状态；`ReviewSourceDestination.Archived` 路由至归档界面；`NotebookStoreTest.isThreadArchivedDetectsThreadStatus` 真机通过；`UiFlowTest.courseReviewArchivedAndEditDeleteWorkflow` 在真机实测归档来源精准路由至归档列表且不擅自解除归档。 |
| **V08** | 新建库、v1/v2/v3、已存在 v4 | 升至最终 schema，原数据/模板/照片关联保留 | 真实历史 fixture 设备用例 | 通过 (真机 Store 验证) | `NotebookDatabase.onUpgrade` 支持旧版本升级，保留多图与模板来源；`NotebookStoreTest` 涵盖 `upgradesFromV1ToV4...`, `upgradesFromV2ToV4...`, `upgradesFromV3ToV4...`（确认自定义模板不被重新播种覆盖）在真机真实 SQLite 环境执行通过。 |
| **V09** | 保存/编辑/状态/删除失败与重试 | 正确反馈，草稿不丢、不伪报成功，重试结果唯一 | 可控结果 UI + Store | 部分通过 / 状态机与空主题已验 | JVM 单元测试覆盖草稿保留、重试唯一性及异常重抛 (`ReviewInteractionTest.dialogStatePreservesDraftOnFailure/Exception`)；真机 UI 实测空白主题拦截 (`courseReviewDialogFailureAndDraftRetention`)；底层真实 SQLite 物理写入失败未在真机全量注入，如实区分。 |
| **V10** | 重建页面/旋转时正在编辑 | 目标 ID、草稿、过滤保持且不重复写入 | UI 用例 / 状态保存 | 通过 (JVM 单元测试 + 真机 UI 验证) | `CourseReviewScreen` 中 `editingRecordId`、`statusFilter` 采用 `rememberSaveable`；`ReviewDialogState` 配备自定义 Saver；`ReviewInteractionTest.saverPreservesDraftAndClearsSavingAcrossRestoration` 验证状态机跨重建恢复；`UiFlowTest.courseReviewDialogFailureAndDraftRetention` 在真机实测 Activity 重建后长文本草稿与编辑草稿完整恢复。 |
| **V11** | NOTE/回答/归档来源导航与返回 | 到正确条目，回到原复习页，不跨课程 | UI 用例 | 通过 (真机 UI 完整实测) | `ReviewSourceDestination` 分发：Note -> `NoteKey`，Archived -> `ArchivedKey`，Chat -> `LessonKey(focusEntryId)`；使用 `backStack.add` 压栈；真机 `UiFlowTest` 的 `courseReviewWorkflowFullCycle` (Chat), `courseReviewNoteSourceWorkflow` (Note), `courseReviewArchivedAndEditDeleteWorkflow` (Archived) 三种来源全部通过实测，按返回键正确回到原复习页。 |
| **V12** | 课程被删除、加载、空课程、筛选无结果 | 每种状态明确，不能误建或留下可用假入口 | UI 用例 | 部分通过 / 筛选空态与删除空态已验 | `CourseReviewScreen` 提供 `records?.isEmpty() == true` 空态文案、`statusFilter` 筛选；`UiFlowTest.courseReviewArchivedAndEditDeleteWorkflow` 实测筛选无结果与记录全删后的空态；极端慢速 IO 加载状态由 Compose 状态机保证，未实测注入。 |
| **V13** | 窄屏/横屏/长文本/英文中文/大字体 | 关键操作可达，内容不遮挡，说明可读 | 设备截图 / 实际真机步骤 | 部分通过 / 核心交互已验 | SHARP A101SH 实测长文本输入与键盘收起（`courseReviewDialogFailureAndDraftRetention`）；中英文通过资源与常规验证；普通横屏与系统大字号可在当前设备手工试用；双栏模拟 (`wm size`) 与系统多语言 (Android 13 LocaleManager) 因硬件/系统条件跳过。 |
| **V14** | 复习操作与既有请求行为 | fake 请求计数无新增，原请求构造回归通过 | JVM + Generator/UI fake | 通过 (真机 UI 与 Generator 验证) | 复习操作为纯 SQLite 数据操作，全程不创建 `AiInput` 或调用 `Generator`；真机 `GeneratorTest` (8/8) 与 `UiFlowTest` 全部通过，断言 `inputs.size` 零增长。 |
| **V15** | JVM、debug/test APK、差异检查 | 退出码、JUnit 汇总、APK 哈希真实一致 | 最终版本构建日志 | 通过 (构建与差异核验) | JVM 单测 76/76 全部通过；真机主套件单次完整跑测（51 项调度：49 通过、2 跳过、0 失败）；Debug 与 AndroidTest APK 校验 SHA256 一致。 |
| **V16** | 文档与推送 | schema/行为/测试证据一致；实时远端 SHA 对应交付 HEAD | diff 自审 + ls-remote | 通过 (文档与测试结果同步) | 文档、解析脚本、测试日志与远端功能分支均已同步。 |
| **V17** | 上游发布与功能合并 | a15aa06 与 8e9eb47 均为最终 HEAD 祖先；无 MERGE_HEAD/冲突；功能文件未被覆盖 | Git 父关系 + 双边 diff + 新基线构建 | 通过 (上游合并完成) | 成功合并上游 v0.3.2 (`a15aa06`) 及 main 后续提交 (`bbefb79`)，无冲突残留，合并后构建与真机测试全部成功。 |
| **V18** | 0.3.2 原有行为 | 支持页面可达、反馈草稿/预览与诊断约束保持，普通聊天文案/历史预算正确 | 既有 JVM + fake UI 用例 | 通过 (真机回归通过) | 真机运行 `UiFlowTest` 覆盖 0.3.2 诊断与帮助反馈流程，全部通过。 |
| **V19** | 开发机交付与升级 | 指定设备运行最终包；合成旧数据升级保留；复习与 0.3.2 冒烟通过 | APK 哈希、serial/API、runner 报告、手工检查表 | 部分通过 / 自动化交付通过 | SHARP A101SH (Android 12/API 31, serial `354xxx644`) 运行 `verify-course-review.sh` 解析验证通过；SQLite 历史迁移用例通过；真机跨版本直接覆盖安装待手工试用。 |

---

## 2. 自动化构建与测试证据

### 2.1 基础构建与 JVM 回归
- **执行命令**：
  ```bash
  bash ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
  ```
- **退出码**：`0` (BUILD SUCCESSFUL)
- **单元测试结果**：**76 / 76 JVM 测试全部通过，0 失败，0 跳过**
  - 复习数据模型与结果：[`ReviewRecordModelTest`](../app/src/test/java/com/feiyu/notes/data/ReviewRecordModelTest.kt) (6 测试通过)
  - 对话框与生产交互状态机：[`ReviewInteractionTest`](../app/src/test/java/com/feiyu/notes/ui/ReviewInteractionTest.kt) (10 测试通过，含草稿保存、并发重入拦截、Saver 状态恢复与协程取消重抛)
  - 0.3.2 新增支持与诊断：[`DiagnosticsTest`](../app/src/test/java/com/feiyu/notes/support/DiagnosticsTest.kt) (9 测试通过), [`FeedbackClientTest`](../app/src/test/java/com/feiyu/notes/support/FeedbackClientTest.kt) (10 测试通过), [`UpdateClientTest`](../app/src/test/java/com/feiyu/notes/support/UpdateClientTest.kt) (13 测试通过), [`ModelLabelTest`](../app/src/test/java/com/feiyu/notes/ui/ModelLabelTest.kt) (1 测试通过)
  - 学习上下文与既有测试：[`ContextBuilderTest`](../app/src/test/java/com/feiyu/notes/study/ContextBuilderTest.kt) (8 测试通过), [`DeepSeekClientTest`](../app/src/test/java/com/feiyu/notes/ai/DeepSeekClientTest.kt) (6 测试通过), [`NoteExporterTest`](../app/src/test/java/com/feiyu/notes/export/NoteExporterTest.kt) (4 测试通过), [`MathTextTest`](../app/src/test/java/com/feiyu/notes/math/MathTextTest.kt) (3 测试通过), [`SkillImportTest`](../app/src/test/java/com/feiyu/notes/settings/SkillImportTest.kt) (3 测试通过), [`ThreadNavigationTest`](../app/src/test/java/com/feiyu/notes/ui/ThreadNavigationTest.kt) (3 测试通过)

### 2.2 真机 Instrumented 测试结果 (SHARP A101SH, Android 12 / API 31, serial 354xxx644)
- **执行命令**：
  ```bash
  ADB_SERVER_SOCKET=tcp:127.0.0.1:5038 ./scripts/verify-course-review.sh -s 354974110447644
  ```
- **测试执行结果**：**合并上游提交 `bbefb79` 后单次全量执行：51 项调度用例：49 通过、2 跳过、0 失败**
  - **日志文件**：`build/course-review-validation/device-test-354xxx644-data_NotebookStoreTest_study_GeneratorTest_ui_UiFlowTest-e9513fd-20261002_082717.log`
  - [`NotebookStoreTest`](../app/src/androidTest/java/com/feiyu/notes/data/NotebookStoreTest.kt)（26 项全部通过）：包含真实 SQLite 下 v1/v2/v3→v4 升级迁移、GENERAL_ID 排除、跨课隔离、空白 topic 拒绝、单条笔记删除级联等。
  - [`GeneratorTest`](../app/src/androidTest/java/com/feiyu/notes/study/GeneratorTest.kt)（8 项全部通过）：生成重试、模板删除选择、多图保留与取消处理等。
  - [`UiFlowTest`](../app/src/androidTest/java/com/feiyu/notes/ui/UiFlowTest.kt)（17 项调度：15 通过，2 跳过，0 失败）：
    - `courseReviewWorkflowFullCycle`（问答来源全周期：新建、状态流转、来源跳转、返回栈保留）通过。
    - `courseReviewNoteSourceWorkflow`（笔记来源全周期：整理笔记、加入复习、跳转 NoteKey、返回复习页）通过。
    - `courseReviewArchivedAndEditDeleteWorkflow`（归档来源跳转不擅自解除归档、记录编辑、筛选空态、来源删除提示红色徽标、删除记录空态）通过。
    - `courseReviewDialogFailureAndDraftRetention`（真实编辑对话框长文本输入、IME 键盘收起、重建 Activity 草稿保留、空白主题校验拦截、取消清理）通过。
    - 11 项 0.3.2 与既有 UI 回归测试通过。
    - 2 项测试如实记录跳过：
      - `layoutAdaptsToWindowWidthAndKeepsDraft`（跳过，原因：折叠屏双栏模拟通过 `wm size` 仅设计用于模拟器，物理机触发 `Assume.assumeTrue` 保护）。
      - `nonChineseLanguageUsesEnglishAndChineseUsesChinese`（跳过，原因：应用语言系统级切换依赖 Android 13+ (API 33+)，设备为 Android 12 (API 31)）。
- **辅助组件测试核验说明**：早期阶段报告曾记录 [`MathRendererTest`](../app/src/androidTest/java/com/feiyu/notes/math/MathRendererTest.kt) (1 项) 与 [`ApiSettingsTest`](../app/src/androidTest/java/com/feiyu/notes/settings/ApiSettingsTest.kt) (2 项)。因归档中暂无对应独立的原始 runner 日志，按严谨原则从已确认总计中移出，标注为“历史阶段声称，当前未独立归档日志，不纳入最终已验证统计”。

### 2.3 产物 APK 与 SHA256 散列
- **Debug 应用程序 APK**：
  - 文件路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 版本信息：versionName `0.3.2`, versionCode `7`
  - SHA256: `9728c0835bb9cbe01aafde7f04a3826f33ea7cd61c02468f00ad44c993de6340`（移除 `app/src/debug/AndroidManifest.xml` 锁屏配置后重新构建）
- **AndroidTest 测试套件 APK**：
  - 文件路径：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA256: `28e691d8507bff8e6adf7808c8cf9c19aace61c07e9a1d0e8758163af45b9e76`

### 2.4 工程小修与离线验证套件
1. **测试脚本失败传播与 Windows 兼容** ([`scripts/verify-course-review.sh`](../scripts/verify-course-review.sh)):
   - 修正了原有仅打印 Warning 的缺陷，当 `ADB_EXIT != 0`、`TEE_EXIT != 0` 或 `parse-test-runner.py` 退出码非零时，统一将 `TEST_FAILED=true`，严禁输出 SUCCESS 或返回 0。
   - 脱敏生成 Windows 安全文件名：使用 `xxx` 替换星号（如 `354xxx644`），避免 Windows 文件系统因非法字符报错。
   - 支持通过环境变量注入 `ADB_BIN` 与 `TEE_BIN`，便于离线仿真验证。
2. **离线测试套件覆盖**：
   - [`scripts/test-verify-script.sh`](../scripts/test-verify-script.sh)：4 项离线 stub 用例（adb 失败传播、tee 失败传播、测试断言失败传播、全部成功），覆盖总脚本退出码与输出诊断，4/4 全部通过。
   - [`scripts/test-runner-parser.sh`](../scripts/test-runner-parser.sh)：7 项 Python 解析器离线 fixture 用例，7/7 全部通过。
3. **移除常态 Debug 锁屏配置**：
   - 删除了 `app/src/debug/AndroidManifest.xml` 中的 `android:showWhenLocked="true"` 与 `android:turnScreenOn="true"`，恢复普通 debug 试用行为，不越权绕过锁屏。

## 3. 安卓开发机验收执行指南

### 3.1 环境准备
1. 准备一台开启了 **开发者选项** 与 **USB 调试** 的安卓手机（建议 Android 8.0 / API 26 及以上，无需 root）。
2. 使用 USB 线连接设备至主机，并在手机上弹出调试授权窗口时勾选“始终允许”。
3. 检查设备连接：
   ```bash
   adb devices -l
   ```
   记录输出中的设备序列号。

### 3.2 一键执行自动化回归跑测
在 FeiyuNote 仓库根目录下执行：
```bash
ADB_SERVER_SOCKET=tcp:127.0.0.1:5038 ./scripts/verify-course-review.sh -s <你的设备序列号>
```
**脚本自动执行流程**：
1. 校验设备序列号存在且已授权（非 unauthorized/offline）。
2. 安装最新的 `app-debug.apk` 与 `app-debug-androidTest.apk`。
3. 调用 `am instrument` 运行测试并将完整日志存入 `build/course-review-validation/`，使用带时间戳、SHA 及脱敏序列号的独立日志文件，避免互相覆盖。
4. 调用 `scripts/parse-test-runner.py` 准确校验终态，若存在失败断言自动返回非零退出码。

### 3.3 手工交互冒烟核对清单
在真机上安装 `app-debug.apk` 后，执行以下关键学习流程验证：

- [ ] **1. 通用对话与练习本隔离**
  - 进入首页顶部的「通用对话」，发送一条提问并等待回答；确认回复卡片底部**不出现**「加入复习」按钮。
  - 打开或创建一个练习本（刷题本）；确认顶部栏与条目卡片**不出现**「课程复习」与「加入复习」按钮。
- [ ] **2. 课程复习记录添加与防重**
  - 进入一个课程笔记本，打开任意课次问答；在助手回复卡片上点击「加入复习」。
  - 弹窗确认主题预填正确，输入说明文本，点击「保存」。
  - 再次在同一条回复上点击「加入复习」，弹窗提示“该内容已在复习记录中”，草稿不丢失。
- [ ] **3. 笔记来源复习记录与跳转**
  - 在课程中完成“整理本课”，进入生成的笔记页面。
  - 点击「加入复习」，保存后返回复习列表；在卡片上点击「回到来源」，确认精准进入原笔记页面并能通过返回键回到复习页。
- [ ] **4. 课程复习列表查看与状态切换**
  - 返回课次列表，点击右上角「课程复习」进入列表。
  - 检查卡片是否展示主题、说明、最后更新时间（格式正确）及状态标签。
  - 点击状态下拉标签，切换为“已理解”或“仍有疑问”，确认颜色和文字即时更新。
  - 使用顶部的“全部 / 待复习 / 已理解 / 仍有疑问”过滤器，确认筛选结果准确。
- [ ] **5. 旋转屏幕与草稿保持**
  - 在复习列表中点击「新建」打开添加弹窗，输入较长的自定义主题与重点。
  - 旋转手机屏幕（竖屏变横屏或横屏变竖屏）；确认弹窗**依然打开**，输入的主题与内容**完整保留**。
- [ ] **6. 归档来源与失效提示**
  - 归档一条问答，在复习列表中点击「回到来源」；确认精准打开该课次的已归档列表，且条目未被自动取消归档。
  - 彻底删除一条已加入复习的笔记或问答；确认回到复习列表后卡片显示红色「来源已删除」标识，点击跳转被安全拦截。
- [ ] **7. 0.3.2 基础功能冒烟**
  - 点击左上角设置 -> 关于与帮助，确认能够进入帮助界面并显示版本 0.3.2 (7)。
- [ ] **8. 锁屏与系统设置适配（普通 debug 行为恢复）**
  - 手机正常锁屏后按电源键点亮，确认应用不再强制在锁屏上显示或无故亮屏；解锁后应用恢复到先前状态。
  - 在系统设置中切换普通横屏或调大字体大小，确认复习卡片、添加对话框操作按钮（保存/取消）依然可正常点击、无内容截断。
- [ ] **9. 用户真实课程试用记录（离线试用 1~2 节课后填写）**
  - 是否愿意再次使用？
  - 添加记录与找来源是否费力？
  - 课程复习页是否切实帮助定位仍有疑问的内容？

### 3.4 真机实际运行截图 (SHARP A101SH, Android 12)

| 1. 问答加入复习弹窗 | 2. 课程复习记录列表 |
| :---: | :---: |
| ![加入复习弹窗](screenshots/course-review-dialog.png) | ![课程复习列表](screenshots/course-review-list.png) |
| **3. 整理笔记加入复习** | **4. 来源删除失效保护** |
| ![笔记加入复习](screenshots/course-review-note-add.png) | ![来源删除失效保护](screenshots/course-review-source-deleted.png) |
