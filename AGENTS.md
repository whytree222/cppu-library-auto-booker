# AGENTS.md — 代理交接与维护手册

本文件写给接手本仓库的编码代理、自动化工具和维护者。它不替代 [`README.md`](README.md)（用户安装与使用说明），而是回答四个问题：

1. 这个应用靠什么正确运行；
2. 哪些地方一改就可能造成真实预约事故；
3. 怎样在本地验证；
4. 怎样安全发版、排障和交接。

**先停手的情况**：要改预约/调度/登录提交/更新安全逻辑、要推送 tag 或发布 Release、要接触 keystore 或 token、要删除或修改历史 Release/tag、要在真实学校系统提交预约。以上操作必须先得到维护者明确授权。

**接手前先读第 12.9 节**：用户已明确要求在原生登录接口上实现全流程自动登录与本机验证码识别，不恢复旧版网页点击或JS填入流程；已授权实现并先交付调试包。正式发布另行确认。第12节此前仅手动验证码的说明是历史决策，已由本次明确选择替代。

---

## 1. 项目概览

这是一个 Android 原生应用（Kotlin + Jetpack Compose），用于在可信校园网内直连 `http://mlib.cppu.edu.cn`，直连不可用时可通过学校 WebVPN（`https://webvpn.cppu.edu.cn`）完成图书馆座位预约。WebVPN 使用原生表单与可选本机加密记住账号；本轮新增原生全流程自动登录及本机验证码识别，手动验证码入口继续保留，详见 12.9。按用户设置的阅览室、座位和使用时段执行。

三个核心能力：

- **定时预约**：每次只安排下一次本地时间 **00:58** 的精确闹钟；闹钟触发后启动前台服务，最多等待学校开放 10 分钟，然后选日、选时段、选座并提交。不是每日循环。
- **直接预约**：按点击时的今天/明天和当前选择生成不可变快照，立即尝试真实预约，不等待 00:58，也不等待尚未开放。
- **应用内更新**：从 Gitee 读取 `update.json`，GitHub 作为备用；下载后校验大小、SHA-256、包名、版本号和签名，再交给系统安装器，由用户确认安装。更新永远不会打断预约。

当前基线（以 [`version.properties`](version.properties) 为准）：

| 项目 | 值 |
| --- | --- |
| 当前版本 | `0.20.3`，`versionCode=55`，`minSupportedVersionCode=50`；10-28 已授权发布选日修复，实际发布状态以远端为准 |
| 包名 | `cn.edu.cppu.libraryautobooker` |
| Debug 包名 | `cn.edu.cppu.libraryautobooker.debug` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Kotlin / AGP / Gradle / JDK | 2.0.21 / 8.7.3 / 8.9 / 17 |
| Compose BOM | 2024.12.01，Material 3 |
| 源码仓库 | GitHub `whytree222/cppu-library-auto-booker`，分支 `main` |
| 发布仓库 | Gitee `whytree/cppu-library-auto-booker-release`，分支 `master`，只放 Release 与客户端读取的 `update.json` |
| 固定下载名 | `library-auto-booker.apk` |

**不是官方应用。** 不要把它描述成学校官方产品；涉及商业化或许可时，提醒维护者先确认学校规定。

---

## 2. 绝对不能破坏的约束

### 2.1 网络与登录范围

- 只允许两条线路：`http://mlib.cppu.edu.cn` 的校园网直连，以及 `https://webvpn.cppu.edu.cn` 的学校 WebVPN 图书馆代理。不要新增第三方代理、公共镜像、自建转换服务或其他校外备用线路。
- WebVPN 全流程自动恢复必须通过原生 `NativeWebVpnSession` 与本机 `DdddOcrEngine`，不使用网页点击或JS填验证码。动态 execution、图片、识别结果、表单身份与 Cookie 必须属于同一会话；不得放宽URL或把账号／验证码发给第三方。
- 双账号已保存开启时默认启用新原生全自动；显式关闭保持不变，不清除paused。每轮90秒、最多三图、换图至少1.5秒，明确验证码拒绝才可续试；账号拒绝、限流、额外认证或提交未知均停止。关闭全流程后SSO手动，内层图书馆自动重登仍独立。旧网页驱动不再是生产恢复入口。
- 自动认证、手动表单与探测Cookie写入共用独占所有权；取消必须等worker/OCR和已发出Cookie写入真正收尾才允许下一认证。手动接管最多等10秒，超时提示稍后处理，不放行第二个写入者。预约运行时不启动新的手动认证。
- `network/LibraryRoute.kt` 校验两条线路的 scheme、主机、端口和 WebVPN 代理前缀；`network/WebVpnPages` 限制 WebVPN 登录页与统一认证跳转；`assets/transport.js` 在网页侧按线路限制请求。三处都必须保留。
- HTTP 明文是学校站点现状，不是遗漏。`res/xml/network_security_config.xml` 只对 `mlib.cppu.edu.cn` 放开明文；不要把明文范围扩大，也不要假设可以改成 HTTPS。WebVPN 使用 HTTPS。
- 直连登录密码只在本机用 AES/GCM + AndroidKeyStore 加密保存。WebVPN 的两套账号只有在用户明确勾选并保存后才允许用同样的本机加密保存；默认不保存统一认证密码。任何密码不得写入日志、崩溃信息、`SharedPreferences` 明文、Intent、`rememberSaveable` 或任何仓库文件。
- `SessionChecker` 只读检查登录状态（`POST /isLogin`），不得记录 Cookie；直连与 WebVPN 状态分开保存，旧请求不得覆盖新状态。`FallbackPolicy` 的“提交后不换线路、不重发”保护必须保留。

### 2.2 预约正确性

以下行为是产品正确性的一部分，不是可随意优化的 UI 细节：

- 定时任务固定为下一次本地 **00:58**，一次点击只安排一次，不每天循环，不恢复自定义多时间。
- 闹钟触发时校验 `expected_at`、配置启用状态和 `reservationFingerprint()`；`BookingScheduler.consume()` 只消费一次并武装下一个待执行时间。不要绕过这些检查直接启动服务。
- 直接预约使用 `ImmediateBookingPlan` 的不可变快照，不覆盖已保存的定时计划；Intent 参数有大小上限，解码必须继续校验房间、座位、座位图和时间段。
- 同一时间只能有一个预约任务运行；并发启动必须被拒绝，不能靠“应该不会同时发生”。
- 提交前必须经过 `FallbackPolicy.markSubmission()`；提交后结果不明时停止并提示核对学校记录，**绝不重复提交**。
- 直连到 WebVPN 的切换只能在**未提交**且直连发生网络故障时发生，且只能单向切换一次。一旦 `markSubmission()` 成功，线路锁定；提交后网络中断、超时或结果不明都只能停止并提示核对，不能换线路、换座位或重发。
- 同一批次只使用一条线路；切换后重新做登录检查并重新开始该批次。WebVPN 会话在**提交前**过期时，可进行一次有界的自动重登（`webVpnReloginDecision`），重登成功后重新加载入口页；`automationStarted` 或本批已重登过则不得再试。一旦 `markSubmission()` 成功，WebVPN 页面再要求登录也只能停止并提示核对，不得重新登录、换线路或重发；不得使用保存的直连密码登录 WebVPN。
- 只有学校明确返回“座位已被预约/占用”之类的冲突，才允许尝试下一个候选座位。网络错误、超时、限流、参数错误、账号错误都不能当作冲突重试。
- 定时任务等待开放最多 10 分钟，截止时间不因轮询重置；确认阶段有 45 秒上限；单批任务有 150 秒上限。不要为了“多等一会儿”放宽这些限制。
- 学校页面上的房间、日期、时段、座位身份必须严格匹配；布局或身份变化时 fail-closed，不做“按序号猜一个”或近似分钟替换。
- “前四段成功后追加后三段”只适用于已核对的过刊七段规则，不能套用到其他房间。

### 2.3 更新安全

`update/UpdatePolicy.kt` 是预约安全闸门，必须保持以下条件全部满足才允许检查、下载或安装：

- 预约服务没有运行；
- 任务状态不是 `RUNNING` / `TRIGGERED`；
- 没有待执行任务落在“现在前 60 秒到未来 15 分钟”窗口；
- 登录页和首次权限引导页没有显示。

另外：

- 更新只读取公开版本信息，不采集预约内容、账号或座位数据；不要在更新流程中加入统计上报。
- 下载必须校验大小、SHA-256、包名、versionCode、versionName 和“与已安装应用相同的签名”。任一失败都删除文件并拒绝安装。
- 只允许 HTTPS 的 Gitee/GitHub 发布地址和明确的 CDN 重定向主机；不要放宽 `UpdateUrlPolicy` 的主机、路径、端口、userinfo、query、fragment 限制。
- 不自动安装。必须交给 Android 系统安装器，由用户确认；应用不能绕过系统确认，也不承诺静默安装。

### 2.4 发布、签名与密钥

- `version.properties` 是版本唯一真相源；tag 必须是 `v` + `versionName`，例如 `v0.20.0`。
- 只有推送 `v*` tag（或在该 tag 上手动运行且 `publish=true`）才发布。`main` 分支推送只测试和构建未签名 release，不创建或修改 Release。
- 不覆盖历史 Release/tag，不使用 `--clobber`，不强制推送，不删除历史资产。失败时只允许删除**本次工作流新建的 GitHub 草稿 Release**。
- 固定发布签名必须保持不变。keystore、密码和 token 不能提交到仓库；keystore 必须离线备份。签名一旦改变，已安装用户无法覆盖升级，只能卸载重装。
- `GITEE_TOKEN` 只放在 GitHub Secrets，需要 `projects` 权限；不得出现在代码、日志、`distribution.properties`、Release 说明或聊天记录中。
- `distribution.properties` 只放公开信息。改 Gitee/GitHub 仓库地址后，必须重新构建并重新生成二维码。

### 2.5 测试与真实环境

- 自动化测试不得访问真实学校系统，不得提交真实预约，不得调用真实 Gitee/GitHub 写接口。Node 测试用 jsdom/fixtures/mock，Android 测试用 JVM/Robolectric。
- 仓库没有模拟器/真机测试环境。代理只能证明“测试和构建通过”，不能声称“真机安装、锁屏唤醒、系统安装器、学校业务已验证”。这些由维护者在手机上完成。
- 不要为了通过测试而放宽 fail-closed 检查、删除断言或改动安全边界。

---

## 3. 环境、构建与测试

### 3.1 工具链

- 仓库**没有** `gradlew` / `gradle-wrapper.jar`。不要写 `./gradlew`，也不要假设本机有 Android Studio。
- 使用 JDK 17、Gradle 8.9、Android SDK 35。
- Node 用于网页脚本和发布脚本测试；CI 使用 Node 22，本地 Node 24 也可运行。
- 当前 Windows 维护机的路径（其他机器请用等价路径）：
  - JDK：`D:\android-build\jdk-17.0.20.1+1`
  - Gradle：`D:\android-build\gradle-8.9\bin\gradle.bat`
  - `GRADLE_USER_HOME`：`D:\android-build\gradle-home`
  - Android SDK：`C:\Users\20571\AppData\Local\Android\Sdk`
- `local.properties` 是未跟踪文件，内容应指向本机 SDK，例如：

  ```properties
  sdk.dir=C\:\\Users\\20571\\AppData\\Local\\Android\\Sdk
  ```

  没有它 Gradle 可能找不到 SDK；不要提交它。

### 3.2 Git Bash 下的本地命令

在仓库根目录执行：

```bash
export JAVA_HOME=/d/android-build/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/d/android-build/gradle-home
export ANDROID_HOME=/c/Users/20571/AppData/Local/Android/Sdk

GRADLE=/d/android-build/gradle-8.9/bin/gradle.bat

# 首次拉取或清理后准备固定 OCR 资源（ONNX 模型与字符集已 gitignore）
python scripts/prepare_ocr.py

# 全量 Android JVM/Robolectric 测试
"$GRADLE" --no-daemon testDebugUnitTest

# 只跑一个测试类
"$GRADLE" --no-daemon testDebugUnitTest \
  --tests 'cn.edu.cppu.libraryautobooker.booking.EntryLoginUiTest'

# Debug 构建（可安装，包名带 .debug）
"$GRADLE" --no-daemon assembleDebug

# 未签名 release 构建（用于检查编译/混淆，不可直接安装）
"$GRADLE" --no-daemon assembleRelease
```

`app/build.gradle.kts` 的 `verifyOcrAssets` 会在构建前校验模型和 `charsets.json` 的存在与 SHA-256；缺少资源时构建直接失败，不要删除该校验或提交模型文件。CI 使用 Python 3.12 运行 `prepare_ocr.py`，再安装固定版本 `onnxruntime` 执行 `scripts/verify_ocr_model.py`；本地 Python 3.7 只能运行下载脚本，不能运行模型契约验证。

输出位置：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/`（没有签名环境变量时通常是 unsigned）

如果 Gradle 报 “Project directory ... is not part of the build”，先确认当前目录是仓库根目录，不要在 `app/build/...` 子目录里执行。

### 3.3 Node 测试

```bash
npm ci
npm test
```

`npm test` 运行 `node --test tests/*.test.cjs`，覆盖：

- 网页登录、直连/WebVPN 传输层、统一认证/图书馆两层自动登录脚本、验证码页面策略、等待开放、选日/选时段、确认与提交保护；
- 房间/时段目录、座位图、发布配置、`update.json`、Gitee 幂等发布、固定别名、双二维码；
- 发布工作流的“不覆盖历史 Release”“只在 tag 发布”“同步/补传只增不改”等约束。

改动 `assets/*.js`、`scripts/*.mjs` 或 `.github/workflows/*` 后必须跑 Node 测试。测试失败时优先修实现，不要改测试来掩盖问题。

### 3.4 测试基线

截至 2026-10-26 的 v0.20.0 发布基线：

- Node：**440 个测试全部通过**；
- Android JVM + Robolectric：**311 个测试全部通过**。

v0.19.1 发布时的基线是 Node 302、Android 184。数量会随功能增长，不是固定目标；判断标准是“全绿 + 覆盖了本次改动”。

第 12 节改动后的当前测试数量（2026-10-26）：Node **444**、Android **345**；发布预检仍需全部通过。Android 的用例数可以直接从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总（Gradle 在 `UP-TO-DATE` 时不打印数量）。

### 3.5 签名与 Debug/Release 差异

- 本地签名需要四个环境变量同时存在，缺一不可，否则 `app/build.gradle.kts` 会直接报错：
  `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
- 不签名时不要设置其中任何一个；四个都不设置才能构建 unsigned release。
- Debug 构建：
  - 包名加 `.debug`，可与正式版共存；
  - 应用标签是“图书馆自动预约（调试）”（`app/src/debug/AndroidManifest.xml` 用 `tools:replace="android:label"` 覆盖）。这是故意的：两个包共存时，标签相同会让维护者装了修复版却去测旧正式版。不要删掉这个清单；
  - `WebVpnLoginDriver.fail()` 只在 `BuildConfig.DEBUG` 下给失败文案追加“（调试最后阶段：…）”，用于定位真机失败发生在哪一步。正式版文案不变，且任何状态都不得包含账号、密码或页面内容；
  - `BuildConfig.UPDATE_MANIFEST_URL` 等为空，**不能测试应用内更新**；
  - 使用 debug 签名，不能覆盖安装正式版。
- Release 构建开启 R8/ProGuard；`@JavascriptInterface` 方法已有 keep 规则，ONNX Runtime 也需 keep。新增 JS Bridge 方法后必须至少跑一次 `assembleRelease`，不能只验证 debug。
- Release 只打包 `arm64-v8a` / `armeabi-v7a`（ONNX Runtime 的 x86 变体只用于本地模拟器 debug）。含离线验证码模型后 release APK 约 51 MB，仍低于 manifest 的 100 MB 上限；debug 构建包含全部 ABI。
- 应用内更新的签名校验是“与当前已安装应用相同”。v0.18.0 是 CI 临时调试密钥签名，所以 v0.19.0 必须卸载重装；这不是可以通过代码兼容的问题。

---

## 4. 代码地图

### 4.1 入口、登录与主界面

| 路径 | 职责 | 修改注意 |
| --- | --- | --- |
| `MainActivity.kt` | 应用状态中枢：登录门禁、Session 探测、WebVPN 卡片与开关、更新 ViewModel、权限引导、预约草稿、调度、房间规则、历史、结果弹窗 | 大部分状态用 `remember`/`rememberSaveable` 持有；不要为了 UI 方便绕过更新安全闸门或并发保护 |
| `EntryLoginScreen.kt` | 登录入口：Session 检查、账号密码输入、直连/WebVPN 手动网页登录、自动重登、错误提示 | 密码故意使用 `remember` 而不是 `rememberSaveable`；`fieldError` 只用于本地输入错误，`detailIsError` 用于网络/存储等状态错误 |
| `SessionChecker.kt` | 只读 `POST /isLogin` 检查，7 秒超时，按直连/WebVPN 两层登录状态（`vpnAuthState`）分类网络、SSO 过期、图书馆过期和服务器错误 | 不记录 Cookie；不要让旧请求覆盖新状态（`SessionProbeEpochs`）；不把 WebVPN 密码页误判为已登录；两层状态只由已核对页面/跳转判断 |
| `AutoLogin.kt` | 直连共享 WebView 登录、`SessionCoordinator`、直连→WebVPN 检查与恢复协调 | 直连自动登录只用于直连；`SessionCoordinator` 在直连网络故障且开关开启时才检查 WebVPN；WebVPN 恢复委托给 `WebVpnAutoLogin`；登录页识别、同源限制和安全页面判断不能放宽 |
| `LoginActivity.kt` / `WebVpnNativeForm.kt` | WebVPN 原生两步表单、手动验证码和可选本机记住账号；直连手动网页入口保留 | 密码/验证码不进入 Bundle；`EXTRA_WEBVPN` 保持为 `"webvpn"`；生产 WebVPN 分支不创建 WebView |
| `network/NativeWebVpnSession.kt` / `NativeVpnHttp.kt` | 严格解析学校表单、密码编码、Cookie 同步、受限 HTTP 与跳转、一次提交及只读核对 | 不跟随 307/308 重发凭据；不暴露动态参数；Cookie 写入回调完成后才继续下一跳；挑战过期、变更或重复使用时停止 |
| `WebVpnCard.kt` | 预约页的 WebVPN 状态、自动切换开关、手动登录/检查、自动登录账号设置入口 | 开关只保存 `webVpnBackup`，不能改预约目标或取消已排队闹钟 |
| `WebVpnAccountsActivity.kt` | 分别保存/暂停/删除统一认证与 WebVPN 图书馆两套账号，明确授权说明 | 密码字段必须用 `remember` 而非 `rememberSaveable`；不得把密码放进 Intent；保存只在本机加密 |
| `WebVpnAutoLogin.kt` | 仅在 WebVPN 外层会话有效时恢复图书馆内层；订阅者管理、冷却与可见登录表单互斥 | 不自动恢复 SSO；不得在预约提交结果不明时启动，不得与原生登录表单并行 |
| `WebVpnLoginDriver.kt` | 历史网页驱动保留；当前生产后台固定 `allowSso=false`，仅图书馆内层自动登录 | 不允许生产入口开启 SSO/OCR；保留提交不明不重发和旧回调封闭，旧驱动测试不代表当前 UI |
| `WebViewLoginTap.kt` | 在受信 SSO 页面内执行一次原生 DOWN/UP 触摸 | 只在受信地址和非零布局下执行；不得越过验证码/登录按钮；生命周期变化必须取消 |
| `ocr/CaptchaPagePolicy.kt` / `CtcDecoder.kt` / `DdddOcrEngine.kt` / `WebVpnCaptchaController.kt` | 本机验证码页面策略、CTC 解码、ONNX 推理和按载入填表控制 | 只接受已验证 HTTPS 登录页；只输出 4 位数字且置信度 ≥ 0.70；最多 3 张图/180 秒；禁止联网、日志记录图片或读取账号 |
| `data/WebVpnCredentialStore.kt` | 分开保存 SSO 与 WebVPN 图书馆账号（AES/GCM + AndroidKeyStore，AAD，`revision`） | `Kind` 的存储名/密钥别名是迁移契约；不得明文回退；`Credentials.toString()` 必须继续脱敏 |
| `LibraryAppPages.kt` | 底部三个页面：预约、权限、记录；保留各页 SaveableState | 只改 UI 时优先改这里和独立 Compose 组件 |
| `PermissionsPage.kt` / `PermissionSetup.kt` / `OemProfiles.kt` | 精确闹钟、通知、电池优化、厂商自启动路径与首次引导 | OEM 页面名称可能随系统变化；不要承诺“设置后一定不被杀” |
| `UpdateCard.kt` / `UpdateDialog.kt` | 更新卡片和更新弹窗 | 下载/安装入口必须继续受 `UpdatePolicy` 保护 |
| `Theme.kt` / `UiComponents.kt` / `StatusCards.kt` 等 | Material 3 主题与通用组件 | 保持深色模式、字体缩放和页面状态 |

### 4.2 预约域

| 路径 | 职责 | 修改注意 |
| --- | --- | --- |
| `booking/BookingScheduler.kt` | 计算下一次 00:58、保存待执行队列、安排/消费精确闹钟、60 秒唤醒测试 | `consume()` 的一次性语义和 `expected_at` 校验不能改 |
| `booking/FixedBookingPlan.kt` | 把用户草稿转换为“下一次 00:58 的一次性计划” | 固定 00:58；不要恢复每日循环或自定义抢座时间 |
| `booking/ImmediateBookingPlan.kt` | 直接预约的不可变 Intent 快照与解码校验 | 不能覆盖定时计划；Intent 有大小上限；解码必须严格 |
| `booking/BookingAlarmReceiver.kt` | 闹钟触发：校验启用、消费队列、带指纹启动前台服务 | 指纹不匹配时必须停止，不能带旧配置继续 |
| `booking/BootReceiver.kt` | 开机/升级/精确闹钟权限变化后恢复 | 已过期待执行时间记 `MISSED`，不补交；只恢复未来任务 |
| `booking/BookingService.kt` | 前台服务：并发拒绝、配置/座位图校验、按线路登录、WebVPN 提交前一次性自动重登、WebView、直连→WebVPN 单向回退、脚本注入、超时、结果记录 | 10 分钟等待、150 秒批次、提交保护和“未知结果停止”都在这里；回退只在未提交且直连网络故障时允许；`markSubmission()` 之后禁止任何重新登录或重发 |
| `booking/WebVpnReloginPolicy.kt` | 提交锁优先、每批最多一次 WebVPN 重登的纯策略 | `submitted` 必须先于 `automationStarted`/`alreadyRecovered` 判断；修改时同步 `WebVpnReloginPolicyTest` 和 `tests/webvpn-booking-recovery.test.cjs` |
| `booking/BookingSequence.kt` | 单批/双批进度与成功失败消息 | 双批仅限过刊“前四段 + 后三段” |
| `booking/AutomationScript.kt` | 把配置和当前线路注入 JS 模板 | 注意转义和配置字段一致性；WebVPN 必须传入代理 origin/prefix |
| `assets/transport.js` | 按当前线路限制同源请求、映射 WebVPN 代理、XHR 钩子、提交前调用 `beginSubmission` | 不能放宽到其他主机；直连绝对 URL 只能映射到已核对的图书馆接口；网络错误必须上报 |
| `assets/release-wait.js` | 等待学校开放，最多 10 分钟，串行轮询 | 截止时间不重置；只有明确“未开放”才重试 |
| `assets/selection.js` | 选房间、日期、时段，校验页面签名和旧勾选 | 页面身份变化必须停止，不做近似替换 |
| `assets/automation.js` | 座位图匹配、选座、确认身份、提交、冲突重试、结果分类 | 只在明确座位冲突时换候选；提交后未知/超时绝不重发 |
| `assets/login.js` | 直连学校登录页同源表单提交（自动重登） | 只用于直连；不处理验证码/iframe；失败要返回明确状态 |
| `assets/webvpn-sso-login.js` / `webvpn-library-login.js` / `webvpn-captcha.js` | WebVPN 两层登录表单、验证码读取与填表保护 | 只允许已核对页面和字段；不读取/回传密码到日志；手动输入优先；提交结果不明时不得重发 |
| `assets/room-discovery.js`、`assets/capture_seat.js` | 房间/座位数据采集辅助脚本 | 生产预约不使用它们；改动需同步 Node 测试/fixtures |

### 4.3 数据与网络

| 路径 | 职责 | 修改注意 |
| --- | --- | --- |
| `data/BookingConfig.kt` | 预约配置、时段校验、目标日期、指纹、待执行时间、`webVpnBackup` 开关 | 指纹不包含 `slotsDate` 等展示字段，也**不包含** `webVpnBackup`；切换线路不应取消已排队闹钟 |
| `data/ConfigStore.kt` | `booking_config` SharedPreferences 读写与迁移 | 无效时段用 `-1` 标记；迁移要幂等；`webvpn_backup` 默认开启 |
| `data/RoomProfile.kt` / `RoomCatalogStore.kt` | 房间规则、时段、本地缓存与内置数据合并 | 时间必须来自实际核对；不要用近似分钟 |
| `data/SeatMapCatalog.kt` / `SeatCatalog.kt` / `SeatChoice.kt` | 座位图解析、位置绑定、候选座位 | 布局/编号/重复项校验必须保留；无编号座位不可选 |
| `data/CredentialStore.kt` | AES/GCM + AndroidKeyStore 保存图书馆账号 | 只预填账号，绝不把密码放入 saved state；`clear()` 会删密钥 |
| `data/RuntimeStore.kt` | 运行状态、最近三次记录、测试状态 | 每次最多保留 3 次运行、每次 50 行；不要无限增长 |
| `data/LoginStatusStore.kt` / `data/LegacyFeatureMigration.kt` | 直连与 WebVPN 登录状态分开保存；WebVPN 恢复中的临时状态；旧版迁移 | 迁移不能清除直连账号、座位、时段；v17 一次性清理旧 WebVPN 自动登录存储和 `login_vpn_*` 后不得再次执行，否则会误删用户新保存的两层账号 |
| `network/LibraryRoute.kt` | 两条预约线路：校园网直连与学校 WebVPN；AES-CFB 代理前缀；`WebVpnPages` 登录页白名单 | 不要增加第三条线路或放宽主机；WebVPN 前缀算法必须与已核对值一致 |
| `network/FallbackPolicy.kt` | 当前线路与提交一次性锁 | 只有未提交的直连网络故障才可单向切到 WebVPN；确认下一批后才可重置提交标记 |

### 4.4 更新域

| 路径 | 职责 | 修改注意 |
| --- | --- | --- |
| `update/UpdateEngine.kt` | 检查、下载、安装状态机；每日自动检查与手动检查 | 不自动安装；下载后才进入 Ready |
| `update/UpdatePolicy.kt` | 预约安全闸门 | 15 分钟窗口和 60 秒最近触发窗口是硬约束 |
| `update/UpdateManifest.kt` | schema 1 解析与字段校验 | 1–4 个 APK URL；大小/说明/URL 限制必须保留 |
| `update/UpdateSource.kt` | Gitee manifest 优先、GitHub 回退，HTTPS 下载与重定向 | 不要把回退扩成任意镜像 |
| `update/UpdateUrlPolicy.kt` | 发布地址/重定向白名单 | 新增 CDN 必须有充分理由并补测试 |
| `update/UpdateVerifier.kt` | 大小、包名、版本、单签名、与已安装签名一致 | 不能只校验哈希；不能接受多签名/未知签名 |
| `update/UpdateInstaller.kt` / `UpdateStore.kt` / `UpdateViewModel.kt` | FileProvider、系统安装器、偏好与 UI 状态 | 安装失败要保留可重试状态，不自动绕过系统授权 |

### 4.5 测试、发布与数据

| 路径 | 职责 | 修改注意 |
| --- | --- | --- |
| `tests/*.test.cjs` | Node/jsdom 回归测试 | 覆盖 JS 行为、发布脚本和 workflow 约束；不得访问真实服务 |
| `app/src/test/java/...` | JVM/Robolectric/Compose 测试 | 覆盖调度、提交保护、登录、权限、更新和主要 UI 状态 |
| `scripts/version.mjs` | 版本/发布配置、URL、tag 校验、固定别名名 | 所有版本相关脚本的唯一入口 |
| `scripts/prepare-dist.mjs` | 检查 APK、生成 `update.json`/校验和/发布说明 | 会删除并重建 `dist/`；manifest 最后写入 |
| `scripts/publish-gitee.mjs` | Gitee Release/资产/manifest 幂等发布 | 只接受内容相同的已有资产；不得覆盖不同内容 |
| `scripts/verify-release.mjs` | 校验两份 manifest、版本化 APK、两个固定下载链接 | 发布后必须通过 |
| `scripts/make-qr.mjs` | 生成 Gitee/GitHub 固定下载二维码 | 仅在仓库地址变化时重新生成 |
| `.github/workflows/android.yml` | 测试、签名构建、Gitee/GitHub 发布 | 不要把版本号硬编码进 workflow |
| `.github/workflows/sync-gitee-manifest.yml` | 手动修复 Gitee raw manifest | 只同步 manifest，不创建/修改 Release |
| `.github/workflows/backfill-download-alias.yml` | 手动补固定别名 | 只增不改；已存在且内容相同才复用 |
| `.github/workflows/repair-release.yml` | 发布失败后复用已签名产物修复发布 | 只能手动触发；校验 tag、版本、签名和 dist 哈希；不重新构建、不移动 tag、不覆盖已有不同内容 |
| `version.properties` | 版本唯一真相源 | `versionCode` 必须递增；tag 必须匹配 |
| `distribution.properties` | 公开分发配置 | 只放公开信息；不放 token/密码 |
| `release-notes/<version>.md` | 每个版本的发布说明 | 最多 8000 字符；不含密钥/账号/内部路径 |
| `docs/download-qr*.png` | README 二维码 | 与固定下载链接对应 |
| `seat-maps/` / `diagnostics/` | 采集依据与核对证据 | 修改内置座位/时段时保留可追溯证据 |

---

## 5. 三条主流程

### 5.1 定时预约（00:58）

1. 用户在预约页选房间、候选座位、使用日期和使用时段，点击“预约 · 下次 00:58 启动”。
2. `BookingScheduler.schedule()` 调用 `FixedBookingPlan.prepare()`，计算下一次本地 00:58（今天已过则明天），写入待执行队列并用 `setExactAndAllowWhileIdle` 安排闹钟。
3. `BookingAlarmReceiver` 触发：检查任务仍启用、`expected_at` 匹配；`consume()` 只消费当前时间并把剩余队列继续武装；记录 `TRIGGERED`；带 `booking_fingerprint` 启动 `BookingService`。
4. 服务校验指纹、房间/座位/座位图和当前配置；启动前台通知和 WakeLock；创建 WebView。
5. `SessionCoordinator` 先检查所选线路登录：直连过期时用保存的直连账号自动重登；WebVPN 的 SSO 或图书馆会话过期时，只有用户保存并开启了对应账号才启动两层自动重登，否则停止并说明。若直连在未提交阶段发生网络故障且 `webVpnBackup` 开启，`FallbackPolicy` 单向切换到 WebVPN 并重新开始该批次。登录成功后注入按线路配置的 `transport.js`。
6. 定时任务注入 `release-wait.js`，最多等待 10 分钟；学校开放后进入 `selection.js`，再进入 `automation.js`。等待期间或进入座位图前 WebVPN 再次过期，可在未开始自动化时重登一次并重新加载入口；开始自动化后只停止，不重登。
7. 提交前 JS 调用原生 `beginSubmission()`，由 `FallbackPolicy` 锁定当前线路并只放行一次；成功/失败/未知结果按严格分类记录。提交后绝不换线路、重新登录或重发。
8. 服务写 `RuntimeStore`、发结果通知并 `stopSelf()`；闹钟已在消费时处理，不会每天循环。

### 5.2 直接预约

1. 用户点击“直接预约”，`MainActivity` 生成 `ImmediateBookingPlan` 的不可变快照并启动服务。
2. 服务解码并校验房间、候选座位、座位图、日期和时段；不使用等待开放逻辑。
3. 当天/明天的基准以点击日为准；快照不覆盖已保存的定时计划。
4. 运行中拒绝第二个任务；提交保护和未知结果停止规则与定时任务相同。直连网络故障且尚未提交时可单向切换 WebVPN，快照中的 `webVpnBackup` 决定是否允许。
5. 结果通过应用内弹窗显示，同时写入记录；弹窗只关联本次直接预约，不弹旧记录。

### 5.3 应用内更新

1. 应用启动/回到前台且 `UpdatePolicy.isSafe()` 为真时，按每日频率自动检查；手动检查不受每日频率限制。
2. `UpdateSource` 先读 Gitee raw `update.json`，失败再读 GitHub `releases/latest/download/update.json`；`UpdateUrlPolicy` 限制 HTTPS 主机、路径和重定向。
3. `UpdateManifest` 校验 schema、版本、`apkUrls`、SHA-256、大小、说明和页面地址。
4. 用户点击下载后，`UpdateSource` 下载并校验大小/哈希；`UpdateVerifier` 再校验包名、版本、单签名和与已安装签名一致。
5. 校验通过进入 `Ready`；用户点击安装，交给系统安装器；未授权“安装未知应用”时先打开系统设置。
6. 任务运行中、即将运行、登录页/权限引导页可见时不检查、不下载、不安装；预约结束后恢复。

### 5.4 登录与 Session

- `EntryLoginScreen` 负责登录门禁和本地账号密码输入；`MainActivity` 在登录成功后进入三页主界面。
- 密码默认提示是“默认密码是学号”，但不要把它写成学校承诺；这只是输入提示。
- 输入校验错误同时标红输入框和状态文字；网络/存储/学校返回错误只标红状态文字，不把输入框误标红；用户一编辑就清除输入错误。
- 密码不进入 `rememberSaveable`；只预填已保存的账号。
- 自动重登是可选能力：直连图书馆账号的自动重登与 WebVPN 两层自动重登都只在用户明确保存并开启后运行；失败时回退到手动登录，不吞掉错误。
- WebVPN 使用原生应用表单，分别填写统一认证与图书馆两套账号。验证码仅手动输入；密码仅在明确勾选后用本机 AES/GCM + AndroidKeyStore 保存。原生 HTTP 只连接学校 WebVPN 已核对的代理路径，动态字段和 Cookie 不输出到日志或诊断。
- 原生表单最多使用一次，超过 120 秒或会话改变时要求重新获取；明确拒绝后用户可手动再次提交新表单，提交结果不明不自动重发。仅图书馆内层保留原来的有界后台恢复和冷却；统一认证过期一律提示应用内手动验证码。
- `SessionCoordinator` 直连优先；只有直连网络故障且开关开启时才检查 WebVPN，已有 WebVPN 会话可用于进入应用和预约备用。可见的 WebVPN 登录页存续期间必须阻止隐藏的自动恢复。

---

## 6. 修改指南

### 6.1 只改 UI/文案

- 优先改 `EntryLoginScreen.kt`、`LibraryAppPages.kt`、`UpdateCard.kt`、`UpdateDialog.kt`、`PermissionsPage.kt`、`RunHistoryPage.kt` 及独立组件。
- 不要为了布局方便改 `MainActivity` 的状态语义、`UpdatePolicy` 调用条件、预约并发保护或调度逻辑。
- 保持 Material 3、深色模式、字体缩放和底部导航状态保留。
- 新增/修改可交互控件时补 Compose 测试；至少跑目标测试类 + 全量 `testDebugUnitTest`。

### 6.2 登录提示和错误标红

- 提示放在密码框的 `supportingText`；文案为“默认密码是学号”。
- 本地输入校验（空账号/空密码等）设置 `fieldError = true` 和 `detailIsError = true`。
- 网络、Session、存储、学校返回等非输入错误只设置 `detailIsError = true`，保持 `fieldError = false`。
- 用户编辑账号或密码时调用清除逻辑，移除红色边框和输入错误文案。
- 不要把密码改成 `rememberSaveable`，不要记录密码。
- 现有回归测试在 `EntryLoginUiTest`；修改文案/行为时同步更新断言。

### 6.3 房间、时段和座位图

- 时段和布局必须有真实采集依据；`diagnostics/` 与 `seat-maps/` 是证据来源，不要凭感觉改分钟或座位号。
- 修改 `RoomProfile`、`SeatMapCatalog` 或 `assets/seat-maps.json` 后，补 `RoomProfileTest`、`SeatMapCatalogTest`、`RoomRulesDraftTest` 等测试。
- 保留无编号座位不可选、重复/异常位置禁选、精确身份匹配和 fail-closed 行为。
- 不同房间规则不能互相套用；过刊的双批规则只属于过刊。

### 6.4 更新逻辑

- 任何改动先问：会不会在预约前 15 分钟、任务运行中或登录页显示时触发？如果会，改回 `UpdatePolicy` 保护。
- 不要削弱 URL 白名单、哈希、包名、版本或签名校验。
- 新增 CDN/镜像必须同时更新 `UpdateUrlPolicy` 和 `UpdateUrlPolicyTest`/`UpdateSourceTest`。
- 更新 manifest 字段变化要考虑旧客户端兼容；当前 schema 1 不应随意变更。
- 修改 `distribution.properties` 后必须重新构建，因为 `BuildConfig` 的 manifest/fallback/page URL 是构建时生成的。

### 6.5 预约、调度和提交逻辑

这是最高风险区域。除非维护者明确要求，不要改。必须改时：

- 先写清楚要解决的问题和现有保护为什么不够；
- 保留一次性消费、指纹校验、并发拒绝、提交保护、未知结果停止、冲突才重试；
- 保留直连优先、单向回退和“提交后不换线路”；回退只能发生在 `markSubmission()` 之前且原因是网络故障；
- 补对应单元测试和 Node/jsdom 测试；
- 跑全量 Node + Android 测试，并至少 `assembleRelease`；
- 在交付说明中明确“未做真机预约验证”。

### 6.6 依赖和构建配置

- Compose 使用 BOM 管理版本；新增依赖要确认 `minSdk 26`、JDK 17 和 R8 兼容。
- 不要随意升级 AGP/Kotlin/Gradle 大版本；升级需要同时验证 `assembleRelease`、Robolectric 和 CI。
- 不要新增 Gradle wrapper 来替代 CI 的 Gradle 8.9，除非维护者要求。
- 不要把 `local.properties`、`dist/`、`node_modules/`、keystore、token 纳入版本控制。

### 6.7 数据迁移

- 旧版迁移集中在 `data/LegacyFeatureMigration.kt` 和 `ConfigStore`/`CredentialStore`。
- 迁移必须幂等、可重复执行；不能删除直连账号、座位、时段或学校的预约记录。
- v0.16 及更早的旧 WebVPN 自动登录存储只在 v17 一次性迁移中清理；新版 `WebVpnCredentialStore` 的两套账号必须在用户重新明确保存后才会存在。迁移不得在每次启动时清掉新版的 `webvpn_sso_credentials_v1` / `webvpn_library_credentials_v1` 或对应 AndroidKeyStore 别名。
- `LegacyFeatureMigration` 的 v17 一次性标记防止重复清理；改迁移时必须保证旧的 `login_vpn_*` 清理逻辑不会在每次启动时抹掉新的 WebVPN 状态。
- 改迁移必须补 `LegacyFeatureMigrationTest` 和升级场景测试。

### 6.8 测试写法

- JS 行为测试放 `tests/*.test.cjs`，使用 jsdom/fixtures；不要请求真实站点。
- Kotlin 逻辑/Compose 测试放 `app/src/test/java/cn/edu/cppu/libraryautobooker/` 对应包下。
- UI 测试用 `createComposeRule`/Robolectric；它可以证明语义树和交互，不证明真机权限、锁屏闹钟或 WebView 行为。
- 发布相关改动必须让 `tests/release-workflow.test.cjs`、`tests/distribution.test.cjs` 等继续通过；这些测试会检查“不覆盖历史 Release”“只在 tag 发布”“不硬编码版本号”等约束。

---

## 7. 发布新版本

**只在维护者明确要求发版时执行。** 发布是不可逆的公开操作；不要因为“代码写完了”就自动打 tag。

应用内更新的完整设计（清单字段、URL 白名单、校验与状态机）以及 Windows 本地的预检/发布方案，见 [`docs/in-app-update.md`](docs/in-app-update.md)；本节只列发版必须遵守的步骤与红线。

### 7.1 发版前检查

- [ ] 变更范围明确，没有未授权的预约/登录/更新安全改动。
- [ ] `git status` 中没有无关文件、密钥、`local.properties`、`dist/` 或构建产物。
- [ ] 全量 Node 测试通过。
- [ ] 全量 Android 测试通过。
- [ ] 至少 `assembleDebug` 成功；涉及签名/混淆/JS Bridge 时 `assembleRelease` 成功。
- [ ] 维护者已知晓：真机安装、锁屏唤醒和真实预约仍需人工验证。

### 7.2 版本号与说明

修改 [`version.properties`](version.properties)：

```properties
versionCode=52
versionName=0.20.0
minSupportedVersionCode=50
```

规则：

- `versionCode` 必须递增，不能复用。
- `versionName` 必须是 `X.Y.Z`，tag 将是 `vX.Y.Z`。
- `minSupportedVersionCode` 通常保持 `50`（v0.19.0 起的最低支持版本）。只有确实要让旧客户端强制更新时才提高；提高会让低于该版本的客户端进入 `updateRequired` 流程。不要为了“提醒更新”随意提高。
- 新增 `release-notes/<versionName>.md`，例如 `release-notes/0.20.0.md`；最多 8000 字符，写用户可见变化和已知限制，不写密钥、账号、内部路径或未经验证的“已修复”。
- README 顶部的版本号和用户可见说明按需更新；固定下载链接不随版本变化。
- 发布 WebVPN 相关版本时，说明中要写明“原生应用表单、仅手动验证码、可选本机加密记住两套账号；统一认证过期需用户补输验证码，仅内层保留自动重登；提交后不重发；尚未完成真机与隔夜验证”。

### 7.3 构建与检查

```bash
npm ci
npm test

export JAVA_HOME=/d/android-build/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/d/android-build/gradle-home
GRADLE=/d/android-build/gradle-8.9/bin/gradle.bat
python scripts/prepare_ocr.py
"$GRADLE" --no-daemon testDebugUnitTest
"$GRADLE" --no-daemon assembleRelease
```

本地如果配置了四个签名环境变量，`assembleRelease` 会产出签名 APK；否则是 unsigned，仅用于编译/混淆验证。

### 7.4 CI 预检

在 GitHub Actions 手动运行 `Android CI and Release`：

- `publish=false`：仍然需要签名 Secrets，会构建签名 APK 和 `dist/` 产物，但**不会发布**。用于检查签名、版本号、`dist/update.json`、`dist/sha256sums.txt`。
- 如果缺少 secrets，这一步会失败；不要通过删除校验步骤来绕过。

CI 在 `main` push 时只运行：

```bash
gradle --no-daemon testDebugUnitTest assembleRelease
```

且不签名、不发布。不要期待 main push 产生 Release。

### 7.5 打 tag 并发布

确认预检产物无误后，只有在维护者授权下执行：

```bash
git tag v0.20.0
git push origin v0.20.0
```

规则：

- tag 必须与 `version.properties` 的 `versionName` 完全一致，否则工作流失败。
- 不要移动、删除或重打已有 tag；不要强推。
- 发布工作流会：
  1. 校验 tag/版本、签名证书 SHA-256；
  2. 构建签名 APK，生成版本化 APK、固定别名、`update.json`、`sha256sums.txt`；
  3. 创建 GitHub **草稿** Release（已存在则拒绝修改）；
  4. 先发布 Gitee Release 和资产，校验 Gitee 固定下载链接；
  5. 把 GitHub 草稿转为公开；
  6. 最后更新 Gitee raw `update.json`；
  7. 做完整公开校验。
- 失败时只允许删除**本次新建的 GitHub 草稿**；不得删除/覆盖历史 Release 或 tag。

### 7.6 发布后验证

至少确认：

- Gitee 固定链接：`https://gitee.com/whytree/cppu-library-auto-booker-release/releases/download/latest/library-auto-booker.apk`
- GitHub 固定链接：`https://github.com/whytree222/cppu-library-auto-booker/releases/latest/download/library-auto-booker.apk`
- 两个发布页都存在且版本正确；
- 两个 `update.json`（Gitee raw + GitHub latest）版本、大小、SHA-256、URL 一致；
- 版本化 APK 与固定别名内容完全相同（SHA-256 相同）；
- `sha256sums.txt` 指向版本化文件名；
- 客户端能从 Gitee 读取新 manifest，Gitee 不可用时能回退 GitHub。

固定别名路径两者不同，不能互换：

- Gitee：`/releases/download/latest/library-auto-booker.apk`
- GitHub：`/releases/latest/download/library-auto-booker.apk`

### 7.7 固定别名、二维码和分发配置

- 固定别名名由 `scripts/version.mjs` 的 `DOWNLOAD_ALIAS_NAME` 决定，必须是 `library-auto-booker.apk`。
- Gitee 返回的 MIME 可能让浏览器保存为 `.apk.zip`；README 已说明删除末尾 `.zip` 后安装。不要重新压缩 APK。
- 修改 Gitee owner/repo 或 GitHub repo 后：
  1. 更新 `distribution.properties`（只放公开信息）；
  2. 重新构建，因为 `BuildConfig` URL 会变；
  3. 运行 `npm run release:qr` 重新生成二维码；
  4. 更新 README 中的下载链接和图片。
- 仅修改 `giteeBranch` 会改变 raw manifest 地址，需要重新构建客户端；二维码是固定下载链接，不受 branch 影响。

### 7.8 Secrets 和签名

GitHub Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GITEE_TOKEN`（需要 `projects` 权限）

Repository Variable：

- `RELEASE_CERT_SHA256`（证书 SHA-256，CI 用来校验签名）

本地签名使用 `ANDROID_KEYSTORE_PATH` 而不是 base64，且四个变量必须同时设置或同时不设置。

硬规则：

- keystore、密码、token 不提交、不粘贴到聊天、不写日志、不放进 `distribution.properties` 或 Release 说明。
- keystore 必须离线备份；丢失后无法覆盖升级，只能让用户卸载重装。
- 不要重新生成发布密钥来“修复”签名问题；先核对 keystore、别名、密码和 `RELEASE_CERT_SHA256`。

---

## 8. 故障与恢复

### 8.1 main CI 失败

- 只影响测试/未签名构建，不影响已发布版本。
- 看日志修复代码或测试，重新提交；不要删除历史 Release。

### 8.2 tag 工作流在发布前失败

- 例如签名失败、版本不匹配、Secrets 缺失。
- 先看日志定位；修复后由维护者决定是否重新运行。
- 不要为了重跑而删除或移动 tag；如果 tag/版本不匹配，修正版本并重新走发版流程，或由维护者明确处理。
- 如果尚未创建任何 Release，重跑通常安全；如果已经创建草稿或 Gitee Release，先进入 8.3。

### 8.3 发布到一半失败

不要盲目重跑，也不要删除历史 Release。按状态处理：

- **只有 GitHub 草稿，Gitee 未发布**：检查草稿是否属于本次运行、内容是否完整；由维护者决定清理或继续。代理不要自行删除非本次草稿。
- **Gitee 已发布，GitHub 未公开**：检查 Gitee 固定链接和资产；Gitee 发布脚本是幂等的，只接受内容相同的已有资产，不会覆盖不同内容。
- **manifest 过期或缺失**：手动运行 `Sync Gitee update manifest`，输入对应 `v*` tag；它只下载已发布资产并更新 Gitee raw manifest，不创建/修改 Release。
- **固定别名缺失**：手动运行 `Backfill fixed download alias`，输入对应 tag；它只新增固定别名并校验哈希，不创建、修改或删除 Release，也不能覆盖已有不同内容的资产。
- **Gitee 附件上传超时/发布任务在构建成功后失败**：手动运行 `Repair release from verified artifact`，输入对应 tag 和包含 `release-dist` 的 run id；它复用已签名产物并重新走发布，不重新构建、不移动 tag。发布脚本使用 1 小时的 Gitee 大文件上传/校验超时，绕过 Node fetch 默认 300 秒限制；51 MB APK 从 CI 上传到 Gitee 实测约 13 分钟。
- **GitHub Release 已存在但需要修复**：工作流会拒绝修改已有 Release。不要用 `--clobber` 或手动删除历史 Release；由维护者决定是否发布新版本。

### 8.4 客户端更新失败

- 先确认 manifest、URL、HTTPS、重定向主机和证书未变。
- 再确认 APK 的包名、versionCode/versionName、SHA-256、大小和签名与已安装应用一致。
- 不要通过放宽 `UpdateVerifier` 或 `UpdateUrlPolicy` 来“修复”。
- 如果用户在 v0.18.0：必须卸载重装 v0.19.0+；签名不同，应用内更新无法覆盖。

### 8.5 Gitee 仓库/分支问题

- Gitee 发布仓库必须存在、公开、默认分支为 `master`，且至少有一个提交；空仓库无法写 raw `update.json`。
- Gitee Release 只存发布资产和 `update.json`，不镜像源码；源码以 GitHub `main` 为准。
- Gitee token 失效会导致发布/同步失败；更新 token 只能由维护者在 GitHub Secrets 中完成，代理不得索取或记录 token。

---

## 9. 常见坑

1. **没有 `gradlew`**：不要运行 `./gradlew`；用 Gradle 8.9 或 CI。
2. **`java` 不在 PATH**：Git Bash 里先设置 `JAVA_HOME` 并把 `$JAVA_HOME/bin` 加到 PATH。
3. **当前目录错误**：在 `app/build/...` 子目录执行 Gradle 会报 “not part of the build”；回到仓库根目录。
4. **`local.properties` 未跟踪**：新环境需要自己创建并指向 SDK；不要提交。
5. **签名变量只能全有或全无**：只设置一半会让构建直接失败；本地不签名时四个都不要设置。
6. **Debug 不是 Release**：debug 包名带 `.debug`、更新 URL 为空、签名不同；不能用来验证应用内更新或覆盖安装。
7. **unsigned release 不可安装**：`main` CI 的 release 产物用于检查，不是给用户下载的 APK。
8. **Windows/Git Bash 路径**：Node 的 `/tmp` 与 Windows 临时目录可能不一致；需要临时文件时用项目内路径或 `$TEMP`。
9. **`prepare-dist` 会删除 `dist/`**：不要在那个目录放未备份的文件；manifest 最后写入是故意的。
10. **Gitee/GitHub 固定路径顺序不同**：Gitee 是 `/releases/download/latest/...`，GitHub 是 `/releases/latest/download/...`；写反会 404。
11. **Gitee 可能返回 `.apk.zip`**：这是 MIME 行为，不是 APK 被压缩；不要解压，删除文件名末尾 `.zip` 后安装。
12. **tag/版本必须一致**：tag 是 `v0.20.0` 时 `versionName` 必须是 `0.20.0`；不能移动历史 tag。
13. **不要硬编码版本到 workflow**：`ReleaseWorkflow` 测试会检查 workflow 不含硬编码的 `vX.Y.Z`，版本从 `version.mjs` 读取。
14. **不要用 `--clobber`**：会覆盖历史资产；发布链路故意拒绝已有 Release/不同内容资产。
15. **Gitee 分支是 `master`，GitHub 源码分支是 `main`**：不要把两者混用。
16. **`minSupportedVersionCode` 不是“版本号”**：提高它会让低于该版本的客户端强制更新；通常保持 `50`。
17. **HTTP 只对 `mlib.cppu.edu.cn` 放开**：不要全局放开明文；WebVPN 必须使用 HTTPS，也不要给学校站点加自签证书绕过。
18. **WebVPN 自动登录是有边界的可选功能**：默认手动；只有用户明确保存并开启两层账号后才自动重登。验证码只允许本机离线识别 4 位数字，不得上传或接入在线 OCR；不得用保存的直连密码登录 WebVPN。
19. **WebVPN 只在提交前回退或重登**：直连网络故障且尚未提交才能切换；`automationStarted` 或本批已重登后不得再试；一旦调用 `markSubmission()`，结果不明时只能停止并核对学校记录，不能换线路、重新登录或重发。
20. **WebVPN 会话时长未验证**：能否整夜保持有效由学校决定；自动重登也不能绕过学校额外验证/风控。不要承诺隔夜自动成功。房间/座位采集页仍只走直连。
21. **OCR 资源不入库**：`common_old.onnx` 和 `charsets.json` 由 `scripts/prepare_ocr.py` 按固定 SHA-256 准备，已 gitignore；构建前必须存在，`verifyOcrAssets` 会校验。不要提交模型或删除校验。
22. **`webVpnBackup` 不进指纹**：切换“直连失败时自动切换”不得取消已排队的 00:58 闹钟或改变预约目标。
23. **不要在测试中访问真实学校站点**：jsdom/fixtures/mock 是硬要求；真实预约只能由维护者明确授权后在真机进行。
24. **Robolectric 通过 ≠ 真机通过**：精确闹钟、厂商后台限制、锁屏网络、WebVPN 会话、验证码 OCR、系统安装器都需要真机验证。
25. **Release 混淆**：新增 `@JavascriptInterface` 方法时确认 ProGuard keep 规则生效，并跑 `assembleRelease`。
26. **WebVPN 的 wengine 会改写表单 action，但不改写 `formaction`，也不拦截 submit**：代理页上 `form.action` 的 getter 显示上游地址、活动 DOM 属性却是根相对路径，原生 `button.formAction` 会解析成未加代理前缀的门户地址，于是默认提交打不到统一认证端点。唯一可靠做法是把提交按钮的 `formaction` 绑成精确代理 URL（`assets/webvpn-sso-login.js` 的 `bindSubmitAction()`），并在每次提交前用 `submitTargetIntact()` 复核。
27. **学校 `checkPassLogin()` 只做校验和 RSA 加密，不提交**：不要“自己实现学校登录”，也不要为了绕过它而直接构造 POST；必须让学校按钮的原始处理器跑一次。
28. **未登录时 WebVPN 的 `/isLogin` 返回的是代理自己的 JSON 信封**（`{"success":false,"url":"/login"}`，没有 `ReturnValue`），它是“外层 VPN 会话需要重登”，不是“学校返回格式不符”。分类逻辑在 `SessionChecker.kt` 的 `vpnSessionEnded` 分支，不要放宽成只看 `success==false`。

---

## 10. 交接检查清单

### 10.1 任何改动

- [ ] 已阅读本文件第 2 节，确认没有触碰安全红线。
- [ ] 已定位相关实现和现有测试，没有只改表面文案。
- [ ] 变更范围与用户要求一致；没有顺手重构无关模块。
- [ ] 没有提交密钥、token、密码、`local.properties`、`dist/` 或构建产物。
- [ ] `npm test` 通过。
- [ ] `gradle testDebugUnitTest` 通过。
- [ ] 必要时 `assembleDebug` / `assembleRelease` 通过。
- [ ] UI 改动有 Compose 测试；逻辑改动有对应单元测试。
- [ ] 交付说明用中文，列出修改文件、测试结果、未验证项。

### 10.2 UI/登录改动

- [ ] 密码提示/错误颜色符合当前约定：输入错误标红输入框，网络/存储错误只标红状态文字。
- [ ] 用户编辑后清除输入错误。
- [ ] 密码没有进入 saved state、日志或明文存储；WebVPN 两套密码只有在用户明确勾选后才用本机加密保存，默认不保存、不自动填写到直连页面。
- [ ] 验证码图片不上传、不写日志；自动识别可关闭，手动输入优先。
- [ ] `EntryLoginUiTest`、`LoginActivityUiTest`、`LoginActivityOcrUiTest`、`LoginStatusTest`、`WebVpnCredentialStoreTest`、`WebVpnStatusRecoveryTest` 等相关测试通过。

### 10.3 预约/调度改动

- [ ] 保持 00:58 一次性、精确闹钟、指纹校验、`consume()` 一次性。
- [ ] 保持直接预约快照不覆盖定时计划。
- [ ] 保持并发拒绝、10 分钟等待、45 秒确认、150 秒批次、提交保护和未知结果停止。
- [ ] 直连→WebVPN 回退只在未提交且直连网络故障时发生，并且只发生一次；提交后未知结果绝不换线路重发。
- [ ] WebVPN 重登只在提交前、自动化未开始、本批未重登时发生；`webVpnReloginDecision` 的 `AFTER_SUBMISSION` 优先；提交后不重新登录、不重发；不会用保存的直连密码登录 WebVPN。
- [ ] 只有明确座位冲突才重试候选。
- [ ] 全量 Node + Android 测试通过；明确告知维护者仍需真机验证（含 WebVPN 登录与隔夜会话）。

### 10.4 更新改动

- [ ] `UpdatePolicy` 闸门未被放宽。
- [ ] URL/哈希/包名/版本/签名校验未被削弱。
- [ ] 更新失败可重试，不自动安装。
- [ ] 相关 `Update*Test` 通过。
- [ ] 明确 debug 无法验证应用内更新。

### 10.5 发版

- [ ] 维护者已明确授权发版。
- [ ] `version.properties`、`release-notes/<version>.md`、README 已同步。
- [ ] 全量测试和签名构建通过。
- [ ] CI `publish=false` 预检通过。
- [ ] tag 与版本一致；没有移动/删除历史 tag。
- [ ] 发布后两个固定链接、两个 manifest、两个发布页、版本化 APK 与别名 SHA-256 一致。
- [ ] 已通知维护者进行真机安装和预约回归。

---

## 11. 协作约定

- 维护者使用中文沟通；交付说明用中文，简洁说明“改了什么、怎么验证、还有什么没验证”。
- 只在明确要求时提交、推送、打 tag 或发布；不要把“本地测试通过”当成“可以自动发版”。
- 维护者偏好直接推 `main` 和端到端发布，但这仍然需要每次明确授权；不要在未授权时操作共享状态。
- 维护者负责真机安装、锁屏唤醒、厂商权限和真实预约验证；代理不要声称完成这些。
- 不主动创建新的规划/分析文档；用户文档放 `README.md`，代理交接信息放本文件，版本变更放 `release-notes/`。
- 发现历史 Release、tag、keystore 或 token 状态异常时，先停止并说明，不要用删除/覆盖来“清理”。
- 如果必须在安全约束和“让测试通过”之间选择，选择安全约束，并说明测试为什么需要调整。

---

## 12. 当前交接状态（截至 2026-10-26）

### 12.1 用户最终决定与当前范围

此前独立观察代次、三次只读检查修复后，维护者仍反馈 WebVPN 网页自动登录失败。因此明确要求：**WebVPN 登录全部改成应用内原生表单，验证码也在应用内输入**；并明确选择**仅手动输入验证码，不保留自动识别选项**。

本轮以本地 `2457cb1` 及既有改动为基础完成实现。维护者已于 2026-10-26 明确授权发布 `v0.20.1 / versionCode 53`，最低支持仍为 50；签名预检在 main 执行，成功后才推新 tag。不要把旧版自动 SSO/OCR 的产品说明当成当前要求，实际发布状态以远端为准。

### 12.2 已完成的实现

- `LoginActivity` 的 WebVPN 分支创建 `WebVpnNativeForm`，不创建或显示学校 WebView；直连的手动网页入口保留。
- 原生第一步填写统一认证学工号、密码、四位验证码，可选学校“五天内自动登录”和本机加密记住账号；学校验证外层会话后再显示第二套图书馆账号密码。
- 保存账号的“已验证”只归属于本轮确实提交、表单已消费且 revision 未变的那套凭据；读取已有会话不验证保存密码，刷新、退出、明确拒绝或未发出清除归属，未知提交可保留供手动只读核对。
- 验证码图片、动态 execution 和表单内容只在内存；密码和验证码输入框关闭 Android 自动保存与自动填充。仅明确勾选后使用原有 AES/GCM + AndroidKeyStore 分别保存账号。
- `NativeWebVpnSession` 严格核对正常学校表单、service、隐藏字段、脚本、公钥和图像来源；按学校公开 `security.js` 的 16 位字与无填充 RSA 编码，以及图书馆 Base64 字符编码提交。保留公开脚本对非 ASCII 字符首次平方的 JS 32 位截断行为，已用真实公开脚本的合成密码输出核对。
- `NativeVpnHttp` 只请求学校 WebVPN 的明确路径，使用平台 TLS。通过 Android CookieManager 共享正常会话，等待 Set-Cookie 完成后才跟随下一跳；凭据 POST 只允许 301/302/303 转为 GET，拒绝 307/308 凭据重发与外部跳转。
- 每个验证码表单绑定当前 Cookie、身份和创建时间（本地最多 120 秒），原子消费一次。过期、换图、会话改变或重复点击时不能再次使用。明确验证码拒绝可显示返回的新表单，但仍由用户点击下一次提交；结果不明只提供检查状态/明确重新获取，不自动重发。
- 生产后台 `WebVpnAutoLogin.canRecover` 不再恢复统一认证；其旧驱动固定 `allowSso=false`、OCR 关闭。已有 WebVPN 外层会话有效时可继续预约，仅图书馆内层过期时保留已授权的内层自动重登。不要偷偷恢复后台 OCR。
- 预约、00:58 调度、线路切换和提交后禁止重发保护没有放宽。
- UI 中入口和账号设置文案同步为应用内登录、手动验证码。旧 OCR/驱动源码和模型暂留作兼容，未在本轮删除；仍有历史测试覆盖它们，但生产统一认证入口不会调用。

### 12.3 公开协议核对依据

本轮只 GET 读取了未登录的学校公开登录页与脚本，未使用任何账号或提交登录：

- 统一认证代理路径为 `WebVpnPages.SSO_PROXY_PREFIX + /tpass/login`，form 为 `#fm1`，字段为 username/password/authcode，隐藏域 execution/encrypted/_eventId/loginType；rememberMe 值为 true。
- service 精确为 `https://webvpn.cppu.edu.cn/login?cas_login=true`；密码公钥取自学校当前公开 login.js，指数为 010001。
- 验证码为同会话的 `/tpass/captcha.jpg`，通常 60×20；不另开 Cookie 会话获取。
- 图书馆主表单是 `#fromuser`，user/passwd/url，原 submitForm 只将密码经其 Base64 编码后正常提交。
- 公开 security.js 的 SHA-256 为 `de4ce8fd9327b150289a30e949739ea9b5f475ed009434a3139be79c6241f6d7`；合成密码英文、中文及分块输出用于兼容核对，不含真实账号数据。
- 使用 jsoup 解析 HTML，依赖遵循 [jsoup 官方说明](https://jsoup.org/download)；启用 Android 核心库 desugaring，见 [Android 官方配置](https://developer.android.com/studio/write/java8-support)。

### 12.4 测试与待验证项

发布前必须通过 Node 全量 444 项与 Android 全量 345 项；本地结果和签名预检以实际日志、产物摘要与远端工作流为准。新增测试覆盖原生两层状态、真实编码向量、验证码校验/拒绝/过期、Cookie 变化、重复点击、提交超时、字段或目标变化、只读核对、共享 Cookie 与重定向规则、原生控件、敏感输入不保存以及本轮提交的凭据验证归属。测试全部使用模拟连接和合成数据，不访问学校业务。

**仍需真机验证**：首次原生两层登录、验证码刷新、学校当前业务响应、五天登录实际有效期、锁屏及隔夜预约。本轮没有执行真实登录或预约。遇到未知新增页面、字段、额外认证或网络结果不明，应显示明确错误并停止，不放宽白名单或自动重发。

### 12.5 接手注意

当前源码位于 `C:\Users\20571\Documents\Qoder\2026-10-24\9ff79a09`。本轮诊断和可安装调试包在原工作区 `diagnostics/native-webvpn-login-2026-10-26/`。调试应用标签仍为“图书馆自动预约（调试）”，包名带 .debug，可与正式版共存。维护者应测试这个包。

既有未提交的代理 formaction 修复、会话结束 JSON 分类、调试阶段和标签改动仍保留。不要覆盖既有工作，不要移动历史 tag，也不要在未经明确授权时发布；不要索取或保存聊天中的真实账号密码、Cookie 或 ticket。

### 12.6 2026-10-27 真机反馈后的本地排查

v0.20.1 已发布，补发工作流 40412278065 成功；标签保持 80abc183 不变，main 的 f55508f 仅修复发布依赖安装。用户随后报告：首次点击统一认证登录后出现笼统的表单读取错误，重新获取仍失败。根因尚未确认，不能声称已修好登录，也不要直接归因于校园网。

本地新增固定阶段与安全错误类别（入口 L、表单/脚本/验证码 S、提交 P、状态 V、图书馆 B、界面 U）。提交后解析错误仍保留“结果未确认，不会自动重发”，不记录或显示异常原文、账号、Cookie、执行参数或响应正文。生产协议和白名单未放宽；未执行真实登录。调试 APK 在原工作区 diagnostics/native-login-feedback-2026-10-27/library-auto-booker-login-diagnostic.apk，应用名称带“调试”，与正式版分开安装。此处改动用于取得用户手机的准确失败阶段，未提交或发布新版本。

用户已确认在调试包中得到 L01 / IllegalArgumentException 类别错误，尚未取得实际跳转目标，不能把潜在缺口当作已证实的真机根因。本地修复 NativeVpnHttp：遇到不在登录请求白名单中的同源 HTTPS 门户/业务页跳转，不跟随、不发送任何新页面请求，返回终止跳转交给 NativeWebVpnSession 使用原有 /isLogin 接口验证；只有确认会话有效才完成登录。请求白名单不变；外部来源、异常地址和 POST 307/308 仍停止。新增 H01—H08 固定分类，不显示目标 URL、查询参数或响应内容。模拟测试覆盖业务落地、门户/片段终止、未认证不成功、密码不重发及外部跳转仍被阻断。修复调试包路径为 diagnostics/native-login-redirect-2026-10-27/library-auto-booker-login-redirect-fix.apk；仍需用户真机验证，未发布新正式版。

后续用户报告验证码消失，完整提示为“统一认证尚未完成，请重新获取验证码并在应用内登录”。已确认该文案来自入口/只读检查的 verifiedOrFail 分支，并非学校明确的密码拒绝消息。C 版补上 load 的有界恢复：只有明确确认 VPN 未登录，才通过原有 /login 与已适配的同源 CAS service 入口各一次 GET 获取可填写表单，最后仅核对状态，不循环，不自动提交密码。check 不启动此恢复；对尚未提交、120 秒内、Cookie 绑定未变的挑战保留图片与已输入验证码，失效或消费过的表单绝不复活。学校返回 SSO 表单且确认仍未认证、又无明确错误文字时，显示新的手动验证码，不再只有空白错误页。

C 版界面增加验证码不可用说明、禁用无图片的验证码输入，以及提交未完成的安全结果弹窗；弹窗确认不会发请求。调试页顶部标明“登录修复检查版 C · 10-27”。本地 Android 357 项测试与 debug/release 构建通过；包为 diagnostics/native-login-recovery-2026-10-27/library-auto-booker-native-login-C.apk，SHA-256 为 0ca5da0c19e6b00f3a0e02396a2ab64c7dbcb7f39dcd833cc9141c4fc273f1ab。仍未执行真实学校登录，不能承诺服务器接受认证；未提交、推送或发布这些本地改动。

用户继续反馈同一“统一认证尚未完成”提示，真实失败点仍未定位。电脑未找到可用手机投屏窗口；未在学校系统执行真实登录。D 版修正 NativeVpnHttp 只等 Cookie 回调、不检查 Boolean 是否接受的缺口：必须所有 Cookie 回调成功才继续，拒绝/超时分别报 H09/H10，不通过放宽规则解决。此缺口尚未被证实就是用户手机根因，不能宣称登录已修好。

D 版新增限长的内存脱敏诊断：固定请求类别、方法/状态码、是否携带 Cookie、Cookie 接受数量、最近操作/当前阶段、登录提交尝试次数和会话分类。不记录目标 URL/查询参数、响应正文、账号、密码、验证码或 Cookie 内容；关闭后清空传输事件。只有调试页面显示诊断并提供“复制脱敏诊断”，登录失败弹窗有“复制诊断”，仅由用户点击复制。目的是取得真实失败链路，不自动重发登录。新包位于 diagnostics/native-login-session-2026-10-27/library-auto-booker-session-diagnostic-D.apk；仍是独立调试包，未提交或发布正式版。

用户提供 D 版真实脱敏链路：SSO POST 302、Cookie 1/1 接受，紧接“业务落地跳转未打开”，没有任何认证回跳 GET，随后 /isLogin 200 JSON 报外层 expired。已确定应用在 SSO 后中断跳转，但原 Location 未记录，不能单凭 302 声称密码/验证码已被学校接受。

E 版补全受限 CAS 回跳：只有来源是受信 SSO 入口、其 service 精确绑定既定 WebVPN /login?cas_login=true、返回目标同源 HTTPS 且有唯一 cas_login=true 和唯一非空 ticket 时，才将代理改写的回跳还原到原有固定服务入口并以 GET 跟随。请求白名单不增加未知路径；外部目标、错误 service、重复参数、异常票据及 POST 307/308 仍被拒绝；同次链路的认证回跳最多一次，重复时报 H11。票据只在请求内存使用，不输出或保存。另修正 Java URI 对仅查询参数的相对地址解析，并仅对已有允许的传输地址去除不发送到 HTTP 的片段。

E 新增完整模拟 HTTP/Cookie/两层登录链路，验证 SSO POST 一次、认证回跳 GET、外层验证后进入图书馆登录；仍未做真实学校登录。365 项 Android 测试与 debug/release 构建通过。首次测试有一项旧门户 fixture 假定片段 URL 始终停在 302，现按实际 HTTP 不发送片段补全允许的根路径 GET，同时保留会话必须未认证的断言。包为 diagnostics/native-login-callback-2026-10-27/library-auto-booker-callback-fix-E.apk，SHA-256 a5158042a5088314a697060557f53cb78e28ce0aaee040923178825cc9ab16c2。界面顶部“认证回跳修复版 E · 10-27”，脱敏复制功能保留；未提交、推送或发布新正式版。CAS 正常回跳依据：https://apereo.github.io/cas/development/protocol/CAS-Protocol-V2-Specification.html 。

### 12.7 v0.20.2 发布准备（2026-10-27）

用户在安装 E 版后反馈“没问题了”，确认此前登录问题已解决；不要扩大为五天会话、锁屏、隔夜预约或正式签名包均已验证。已在本地准备 v0.20.2 / 54（最低支持 50）的版本、用户说明、发布说明和检查。用户随后于 2026-10-27 明确回复“发布”，已授权本次提交、推送、签名预检、tag 及 Gitee/GitHub 发布，不要重复索要同一次发布确认。

本地准备检查已通过：Node 445、Android 365 项全部通过，未签名 release 混淆构建通过，aapt2 确认正式包名 / versionCode 54 / versionName 0.20.2。签名预检须在 main 上以 publish=false 运行，不在 tag 上做 publish=false 预检；本段仅记录已获授权和本地结果，实际发布以远端运行结果为准。

正式版与调试版包名、签名和本地数据独立，E 版账号/会话不会自动迁移。发布后让用户先更新正式版并在正式版内重新完成 WebVPN 登录，确认可用再移除调试包；不要直接卸载其仍在使用的应用。当前未跟踪的 docs/in-app-update.md 并非本轮新增，不擅自纳入发布提交或覆盖。

### 12.8 WebVPN 选日卡住修复（2026-10-28，未发布）

v0.20.2 已完成 Gitee/GitHub 发布及公开校验，tag 指向 65296fa929605daa4f810e7b159c269048cfa0e5，不要移动 tag。维护者随后提供真实记录：直连 UnknownHostException 后正确切换 WebVPN，登录检查通过、找到过刊阅览室，停在选择使用日，150 秒后总任务超时。维护者已授权修复本问题，并在测试修复包后反馈“可以了，发布吧”，授权 v0.20.3 / 55 的提交、推送、签名预检与 Gitee/GitHub 发布，不重复询问同一次发布权限。

离线复现发现 transport.js 通过 form.action 属性比较代理网址，若代理将该属性虚拟化为原始地址，MutationObserver 会反复写入同一个 action，阻塞网页计时器。已改为比较 DOM action 属性，并在扫描写入期间暂停自身观察，避免代理同时虚拟化 getAttribute 时继续自触发。真实手机上的具体代理钩子尚未采集，不把模拟复现描述为已确认的学校现场原因。

selection.js 为轮询及学校原有点击处理补充脱敏异常提示，阶段固定为 room/date/slots/navigate。BookingService.selectionStage 是只接收上述固定阶段的 JS Bridge，Android 主线程独立执行每阶段 25 秒超时，不依赖网页事件循环；阶段推进使旧定时器失效，同阶段重复通知不能续期，进入座位图或切换批次后清理，旧代次不能干扰新任务。原有 10 分钟等待、150 秒批次、45 秒确认、精确身份校验、直连优先和提交后不重发保护保留。

新增代理属性虚拟化、真实 MutationObserver 不自触发、WebVPN 下日期与时段流程、脚本错误脱敏、缺少日期弹窗，以及四项 Android 独立超时测试。Node 452、Android 369 全部通过，debug 与 release 构建成功；维护者确认独立调试 APK 可用。正式版与调试版会话不共享，新正式签名包及隔夜预约仍待维护者验证；代理未执行真实学校登录或预约。v0.20.3 发布预检结果以 diagnostics/release-v0.20.3、本地日志及远端工作流为准。

### 12.9 原生 WebVPN 全流程自动登录（2026-10-29，调试交付）

维护者明确授权实施原生全流程自动登录，取代第 12 节历史版本的“统一认证验证码仅手动”限制。仅复用 NativeWebVpnSession 正常协议和本机 DdddOcrEngine，不恢复隐藏 WebView、网页点击或 JavaScript 验证码填写驱动。图片、识别结果及密码不上传第三方、不落盘。

两套 WebVPN 账号均保存并启用时，总开关默认开启；显式关闭后再次保存账号也不得恢复开启。原有账号暂停状态和图书馆内层开关独立保留。每轮 90 秒、最多三张图片、至少 1.5 秒换图；每表单只提交一次，图书馆每轮最多提交一次。只有低置信识别或类型化验证码拒绝可换图，账号拒绝、限流、额外认证、提交结果不明不得重发。验证码耗尽冷却五分钟，不暂停密码；账号拒绝或不明提交只暂停对应当前版本账号。

自动恢复、手动原生表单和状态探测共用认证所有权。取消后必须等工作线程、OCR 关闭和真实 Cookie 回调收尾后释放；手动接管异步最多等十秒，不得阻塞主线程，不得在预约任务运行中开始手动认证。旧结果必须核对所有权和账号版本。有效会话直接复用；不增加常驻探测。后台失败仅记录和通知，不主动打开登录页。预约提交后不重登、不换线路、不重发，十分钟截止时间不重置。

本轮仅授权实现、离线模拟测试、debug 与 unsigned release 构建，并先交付独立调试 APK。禁止自动提交、推送、打 tag 或发布此功能；需维护者真机确认两层登录与恢复预约后另行授权正式发布。版本号暂维持已发布 v0.20.3，不宣称锁屏或隔夜已验证。不清除账号、会话、预约任务和记录。


本轮离线验证：Node 452 项与 Android 435 项测试全部通过，assembleDebug / assembleRelease 成功（正式包未签名）。新增覆盖类型化错误、三张验证码上限、双层恢复、有效会话免提交、五分钟边界、开关记忆、账号版本变更、共享订阅、Cookie 延迟与重复回调、手动接管、排队任务关闭及预约期间禁止手动认证。未执行真实学校登录或预约。调试包签名校验通过，包名 cn.edu.cppu.libraryautobooker.debug，显示“图书馆自动预约（调试）”，登录诊断标识“原生自动登录调试版 · 10-28”；版本号仍为 0.20.3 / 55，不将它误认为已发布的正式 APK。调试交付位于维护者当前工作目录 diagnostics/native-auto-login-2026-10-28/library-auto-booker-native-auto-login-debug.apk，SHA-256 为 67ae3d8b1bded498eb32dc172de9af97954e0ba2080400e334958d2192a6ab3e。

维护者随后明确要求‘发布’，授权 v0.20.4 / 56 的提交、推送、签名预检及 Gitee/GitHub 发布，取代前述暂不发布要求。最低支持保持 50；未宣称真机或隔夜已验证。
