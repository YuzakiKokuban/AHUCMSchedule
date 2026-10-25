# 岐黄课表 · Qihuang Schedule

安徽中医药大学课程表应用，支持 Android 与 Windows，使用 Kotlin、Compose Multiplatform 和 miuix Monet 配色。Android 包名为 `com.yuzakikokuban.ahucm`。

![岐黄课表](design/logo-lockup-light.png)

## 标志与 Monet 取色

标志以「课历装订 + 展开的书页 + 负形草叶」组成，使用深玉绿与暖白配色；图形在单色下也能识别。完整几何、调色板、导出与重新生成方式见 [design/README.md](design/README.md)。

- Android 自适应图标带 monochrome 图层：系统开启主题图标后，启动器用 Monet 调色板对标志重新取色。
- 应用内空状态使用同一几何的标志，颜色取 `MiuixTheme.colorScheme.primary / onPrimary`，Android 与 Windows 都跟随动态主题。
- Windows 使用同一图标作为窗口图标与 exe / 安装包图标。

## 已实现

- Android 主屏幕「今日课表」小组件显示当天课程、时间和教室，标记正在上课／下一节，支持滚动、调整尺寸及系统深浅色和动态配色。在「连接教务 → 创建桌面小组件」中发起添加，也可从启动器的小组件列表添加。读取当前账号本地课表，查看历史学期不会改动小组件；同步、导入、切换账号及日期变化后重读。缺作息时显示节次和待同步提示；系统可能延迟后台刷新，底部显示更新时间。Android 8–11 使用兼容集合服务，Android 12+ 使用直接集合。

- Android / Windows 提供独立的「校历」页面：展示官方日期、教学周、日期备注与今天高亮，翻月沿用课表的左右箭头和「本月」控件。顶栏同一个同步按钮获取所选学期的课表与校历；两类数据分别校验保存，单项失败保留对应缓存，不根据校历改写个人课表。
- 「连接教务」中的「选择学期」同时控制课表和校历，选择按账号保存；离线可以切换已有缓存，未缓存或未发布的学期明确提示。Android 系统日历和小爱自动管理仅对学校当前学期提供入口，避免历史浏览覆盖当前安排；Windows ICS 导出使用正在查看的课表。
- Windows 使用侧栏导航、中央课表与可并排的课程详情，缩窄窗口后自动收起详情；安卓横屏使用紧凑工具栏和导航轨道，平板宽屏支持双栏。校历宽屏并排展示月份，平板竖屏利用高度连续展示月份；通知宽屏采用列表与原文双栏，登录表单限制宽度。窗口重排保留已选周次、月份和课程，并为安卓系统栏及侧边挖孔留出安全区域。

- Android「连接教务」页的「关于岐黄课表」展示实际版本、版权、Apache-2.0 与第三方声明；支持手动检查 GitHub 正式版、应用内下载并校验 APK，再由系统确认安装。仅同签名且版本递增的安装包可更新，不自动后台下载。

- 直接通过学校统一身份认证登录，读取所选学期个人课表和已读、未读通知；未显式选择时默认学校当前学期。
- 每次同步重新请求官方全部周次 XLS，核对实际学期、日期与课程结构，保存成功后才替换课表。
- Android 支持在官方 WebView 登录，加密保存教务 Cookie，会话恢复通过权限校验后自动同步；不读取网页登录密码。
- 学号密码登录可加密保存在本机：Android Keystore / Windows 当前用户 DPAPI；可清除本地登录。
- 登录后立即同步，应用在前台时每 5 分钟检查一次，返回前台和点击“同步”也会重新检查。
- 调课、停课、补课通知显示原文，合并重复消息；不标记学校消息已读，不清除消息，不用通知改写课表。
- Android / Windows 共用七天、十四节课表网格，左侧显示学校实际上下课时间；点击课程查看完整详情。课表和校历支持左右滑动切换周／月，左滑向后、右滑向前，保留箭头按钮和纵向滚动；校历翻月限制在已缓存范围内。
- 作息时间从教务课表页同步，与课表一并校验、保存；旧缓存和手动 XLS 无作息时明确显示待同步。
- 支持手动导入学校 XLS；文件导入会明确标示来源，不作为在线同步成功。
- 每个账号独立缓存课表和通知，可离线查看；失败保留该账号上次成功结果并提示。
- 页面、课表周次和校历月份使用方向一致的滑动与淡入淡出动画；宽屏限制位移距离。顶栏同步按钮共用于课表与校历，同步进行时旋转。
- Android 独立本地日历幂等同步，保留其他日历及未标记事件；Windows 导出 ICS。
- 本 APK 内置小爱自动管理 Xposed 功能，需在 LSPosed 启用并勾选小爱助手，不改装原外部模块；校验回执后才标记完成。
- 本应用保存学校当前学期的新课表后，立即触发小爱后台同步，不等待校历和通知请求结束；自动同步和手动开启管理均不打开小爱界面。后台通道在小爱进程内使用其原生认证和课程接口，认证信息不传回本应用。
- 小爱执行任务时定期续租，中断后的旧占用最多保留 45 秒；启动、心跳和网络任务分别受超时与旧回执校验保护。相同教务版本也会读回核验并补回被手动删除的课程；同步中再次收到更新时，仅保留最新待处理版本，当前任务结束后立即接续。
- 小爱同步按学校作息生成全部节次与起止时间，划分上午（13:00 前）、下午（13:00–17:10 前）和晚上（17:10 起）；当前作息对应上午 4 节、下午 4 节、晚上 6 节。每次同步均重新核验已有课表，包括当前周数和开学日期；内容未变时只读核验，发现差异才修复，避免同版本缓存掩盖跨周或手动修改。
- 小爱导入依官方流程先选中托管课表、再写课程、最后保存作息与周数；重建已删除的托管表时，在建表回执处更新绑定。跨应用回执在后台线程处理，延迟回执不会清除更新的超时结果。使用后台冻结工具时，请将岐黄课表和小爱助手加入白名单，否则同步会等待解冻。
- 使用现代 libxposed API 102：`XposedModule` / `onPackageReady` 入口、拦截器 Hook 与 `META-INF/xposed` 模块声明，最低框架 API 为 102。升级后需重启小爱进程一次，使新模块生效；不支持同步任务中的模块热重载。后台原生通道已在小爱助手 8.2.65.4714 验证，其他版本需要适配验证，接口不可用时明确显示待处理原因。

“上次同步时间”表示成功获取的时间，不能保证离线期间学校没有修改。网络失败、会话过期时需重新连接后确认。

## 发布包

正式 Release 提供固定签名的 Android APK 和带运行环境的 Windows ZIP；密钥及口令不入库，需另外离线备份。可覆盖升级使用相同官方签名的版本。Windows 解压完整 ZIP 后运行 `QihuangSchedule.exe`；下载页同时提供 `SHA256SUMS.txt`。

Android 所有可安装变体统一使用 `release-signing-certificate.sha256` 对应的官方证书；配置其他证书会拒绝构建。签名配置读取忽略提交的 `local.properties` 中的 `ahucm.release.*` 项，或下文列出的环境变量。缺少官方私钥时只可生成未签名包，不再回退到默认调试证书：

```powershell
.\gradlew.bat :androidApp:assembleRelease
```

图标与品牌资源由 `design/tools/build-logo.ps1` 生成（见 `design/README.md`），桌面端 `icon.ico`、Android 自适应图标（含 Monet 单色图层）均为同一标志。

核心解析、Android 单元与设备隔离测试、Windows DPAPI 与 Compose 界面测试均纳入验证。小爱后台通道已实测无界面冷启动、连续课表变更、覆盖修复及服务器读回核验；测试数据在验证后还原。
当前通知在应用内展示，尚未实现 Android 后台推送、系统通知或 Windows 托盘常驻。
空课表导出目前会拒绝覆盖旧课表并提示失败；需要先验证学校的完整空课表格式，再支持正式清空。

## Android Studio

1. 克隆仓库并用 Android Studio 打开，Gradle JDK 选择 JDK 21。安装 SDK Platform 37.2、Build Tools 36.0.0 和 Platform Tools。
2. 同步项目，选择 `androidApp`，连接 Android 8.0 / API 26 及以上设备运行。
3. 不需要先下载模拟器镜像，也可直接安装 `outputs` 中的 APK 测试。

`compileSdk` 使用 37.2 满足当前 miuix/Compose 的要求；`targetSdk` 为 36，`minSdk` 为 26。
SDK 路径可由 Android Studio 写入 `local.properties`，或通过 `ANDROID_HOME` 指定；本机路径不进入仓库。

默认使用官方 Google / Maven Central 仓库。如本机下载受限，可在 `local.properties` 配置 `ahu.repositories.mirror=aliyun`；删除此项即可恢复官方仓库。
镜像设置不进入版本控制，不修改全局网络设置。

```powershell
.\gradlew.bat :core:test :composeApp:desktopTest
.\gradlew.bat :composeApp:run
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :composeApp:createDistributable
```

命令行需让 `JAVA_HOME` 指向 JDK 21。没有 Android SDK 时，可加 `-PandroidEnabled=false` 只开发 Windows 与核心逻辑。
Gradle Wrapper 固定版本并验证分发包 SHA-256。

## 结构

- `core`：学校认证与只读接口、XLS 校验、课表和通知原子保存、账号缓存隔离。
- `composeApp/src/jvmSharedMain`：Android / Windows 共用的 miuix 界面。
- `composeApp/src/desktopMain`：Windows 窗口、文件选择和浏览器入口。
- `androidApp`：Android 入口、文档选择器与前台状态；与桌面端编译同一份界面源文件。

两端都是 JVM 目标，共用解析和界面源代码。

## 数据与认证

账号密码和会话不写入源码、测试样本或共享配置。凭据与本应用导出的教务 Cookie 在应用私有目录加密保存，最新显式登录方式决定下次启动恢复来源；清除登录同时清理网页登录状态。
认证和下载只允许 HTTPS 连接学校认证域与教务域，不将会话发送到其他站点。
Android 缓存保存在应用私有目录，Windows 缓存位于 `%LOCALAPPDATA%\AHUCMSchedule`。账号目录使用摘要标识，不保存密码。
应用没有自建云服务，不上传课表。通知只读；课表以重新获取的官方完整导出为准。

真实学生课表只供本机验证，不能加入源码或发布包。常规测试使用生成的匿名 XLS。
可选设置 `AHUCM_SAMPLE_XLS` 验证本地官方样本；设置 `AHUCM_LIVE_ACCOUNT`、`AHUCM_LIVE_PASSWORD` 才启用只读在线验证，缺少变量时跳过相应用例。
这些变量不应写入仓库或共享配置。

miuix: https://github.com/compose-miuix-ui/miuix （Apache-2.0）

## 鸿蒙适配预览

HarmonyOS NEXT 的独立 ArkTS/ArkUI 原型位于 [harmonyApp](harmonyApp/README.md)，提供原生课表页面、学校网页登录、完整官方 XLS 解析与按账号隔离的离线缓存。已在 DevEco Studio 26 自带 SDK 下通过 ArkTS 编译和 HAP 打包，并提供未签名的预览 HAP；尚未完成设备签名、鸿蒙真机登录和运行验证。

小爱自动管理功能区仅在 Android 收到支持 API 102 的框架服务激活信号后显示；未激活、服务连接丢失以及 Windows/鸿蒙平台均隐藏整块入口和说明。是否开启自动管理与模块是否激活分别保存、判断。

## 持续集成与发布

版本由发布标签决定，`version.properties` 只是开发分支上的当前版本快照（由发布工作流自动写回，不在发版前手工递增）：

- `versionName` 为 `MAJOR.MINOR.PATCH[-alpha.N|beta.N|rc.N]`，每个部分 0–999，禁止前导零与构建元数据；候选编号 1–99。
- `versionCode` 按 `(major*100000 + minor*1000 + patch)*1000 + slot` 计算：正式版 slot 为 999，候选版为 `stage*100 + 序号`（alpha 1xx、beta 2xx、rc 3xx），因此候选版始终低于它对应的正式版，Android 只会面向更高 code 提供升级。
- `tools/ci/check_version.py` 校验 `version.properties` 与上述编码一致；`tools/ci/test_release_version.py` 覆盖解析和排序规则，CI 与发布前都会运行。

- 向 `main` 推送或手动运行 CI：执行核心、Android、Windows 界面及小爱驱动测试，生成官方签名 APK、带 Java 运行环境的 Windows ZIP 和校验文件。Pull Request 执行相同测试与未签名 Android 编译，不使用发布私钥、不分发可安装 APK。
- 创建并推送任意 `v` 开头的标签（例如 `v0.6.1`、`v0.7.0-rc.1`）会自动触发 Release 工作流：`validate` 从标签解析出 `versionName`、`versionCode` 和 `prerelease`，格式不合法时直接失败；构建阶段把标签版本写入 `version.properties` 后再打包，因此不需要先提交一次版本号。产物校验通过后创建草稿 Release，候选标签的草稿会标记为 pre-release，应用内更新只接受正式版。
- 草稿创建后，Release 工作流会把标签版本写回 `main` 的 `version.properties` 并提交 `chore(release): sync version <tag>`，使下一次发布继续以新标签为准。发版前请更新 `.github/RELEASE_NOTES.md` 的发布说明；该写回需要 `main` 允许 GitHub Actions 机器人直接推送（当前分支未受保护），若以后启用分支保护，请为 Actions 放行或改成自动创建 PR。
- Release 工作流也支持手动重试已有标签；已存在的 Release 不会被静默覆盖。
- Actions 依赖使用版本标签、不锁定到提交 SHA，由 Dependabot 每周检查并提交升级 PR；Pull Request 默认只读权限，正式签名不用于 PR 构建。

发布签名由以下 GitHub Actions Secrets 提供：

| 名称 | 内容 |
| --- | --- |
| `AHUCM_SIGNING_STORE_B64` | PKCS12 发布密钥库的 Base64 |
| `AHUCM_SIGNING_PASSWORD` | 密钥库及私钥密码 |
| `AHUCM_SIGNING_ALIAS` | 签名别名 |

密钥库只恢复到临时构建目录，任务结束删除；请另外保管离线备份，后续升级必须使用同一签名。
发布构建还会核对 `release-signing-certificate.sha256` 中的公开证书指纹，防止误换密钥导致无法升级。
0.4.1 起，本地 debug/release 和 CI 的可安装 APK 统一使用同一官方签名。早期其他证书签署的安装仍受 Android 签名限制，无法直接覆盖为官方版；迁移前需保留课表并确认可重新登录，不能通过更改配置绕过系统限制。
CI 不包含学生凭据或真实课表，不运行需要账号的实网测试。

Windows 构建须在 Windows 上运行：

```powershell
.\gradlew.bat -PandroidEnabled=false :core:test :composeApp:desktopTest :composeApp:createDistributable
python tools/ci/package_build.py windows
```

Android 本地签名发布时通过环境变量 `AHUCM_KEYSTORE_PATH`、`AHUCM_KEYSTORE_PASSWORD`、`AHUCM_KEYSTORE_ALIAS` 指定密钥，再执行 `:androidApp:assembleRelease`。

## 许可证

本项目原创代码与图标采用 [Apache-2.0](LICENSE)。依赖声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
