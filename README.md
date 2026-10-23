# 安中课表 · AHUCMSchedule

安徽中医药大学课程表应用，支持 Android 与 Windows，使用 Kotlin、Compose Multiplatform 和 miuix Monet 配色。Android 包名为 `com.yuzakikokuban.ahucm`。

![安中课表](design/logo-lockup-light.png)

## 标志与 Monet 取色

标志由「学期标题栏 + 2×3 课程块」组成，落在薄荷玉色渐变圆角方块上。完整几何、调色板、导出与重新生成方式见 [design/README.md](design/README.md)。

- Android 自适应图标带 monochrome 图层：系统开启主题图标后，启动器用 Monet 调色板对标志重新取色。
- 应用内空状态使用同一几何的标志，颜色取 `MiuixTheme.colorScheme.primary / onPrimary`，Android 与 Windows 都跟随动态主题。
- Windows 使用同一图标作为窗口图标与 exe / 安装包图标。

## 已实现

- 直接通过学校统一身份认证登录，读取当前学期个人课表和已读、未读通知。
- 每次同步重新请求官方全部周次 XLS，核对实际学期、日期与课程结构，保存成功后才替换课表。
- Android 支持在官方 WebView 登录，加密保存教务 Cookie，会话恢复通过权限校验后自动同步；不读取网页登录密码。
- 学号密码登录可加密保存在本机：Android Keystore / Windows 当前用户 DPAPI；可清除本地登录。
- 登录后立即同步，应用在前台时每 5 分钟检查一次，返回前台和点击“同步”也会重新检查。
- 调课、停课、补课通知显示原文，合并重复消息；不标记学校消息已读，不清除消息，不用通知改写课表。
- Android / Windows 共用七天、十四节课表网格，左侧显示学校实际上下课时间；点击课程查看完整详情。
- 作息时间从教务课表页同步，与课表一并校验、保存；旧缓存和手动 XLS 无作息时明确显示待同步。
- 支持手动导入学校 XLS；文件导入会明确标示来源，不作为在线同步成功。
- 每个账号独立缓存课表和通知，可离线查看；失败保留该账号上次成功结果并提示。
- 页面与课表周切换带动画，顶栏始终为同步按钮，只有课表同步时旋转。
- Android 独立本地日历幂等同步，保留其他日历及未标记事件；Windows 导出 ICS。
- 本 APK 内置小爱自动管理 Xposed 功能，需在 LSPosed 启用并勾选小爱助手，不改装原外部模块；校验回执后才标记完成。
- Android 0.3.1 使用现代 libxposed API 102：`XposedModule` / `onPackageReady` 入口、拦截器 Hook 与 `META-INF/xposed` 模块声明，最低框架 API 为 102。升级后重新打开小爱助手；当前持续运行的同步任务不支持模块热重载。

“上次同步时间”表示成功获取的时间，不能保证离线期间学校没有修改。网络失败、会话过期时需重新连接后确认。

## 发布包

正式 Release 提供固定签名的 Android APK 和带运行环境的 Windows ZIP；密钥及口令不入库，需另外离线备份。正式包与旧调试包签名不同，切换时请按 Release 说明处理。Windows 解压完整 ZIP 后运行 `AHUCMSchedule.exe`；下载页同时提供 `SHA256SUMS.txt`。

签名配置读取忽略提交的 `local.properties` 中的 `ahucm.release.*` 项，缺失时 release 构建回退为调试签名，保证干净检出仍可构建：

```powershell
.\gradlew.bat :androidApp:assembleRelease
```

图标与品牌资源由 `design/tools/build-logo.ps1` 生成（见 `design/README.md`），桌面端 `icon.ico`、Android 自适应图标（含 Monet 单色图层）均为同一标志。

Android 0.3.0 已在小米 17 Pro（Android 17）安装。匿名设备测试 3 项通过，覆盖凭据/网页登录会话加密持久化、篡改拒绝、日历重复同步、更新教室、保留未标记事件与测试数据清理。
Windows DPAPI、核心解析和真实 Compose 界面测试通过。小爱驱动通过模拟 API 与取消策略测试。
Android 0.3.1 已安装，LSPosed 已识别为 API 102 模块并保留小爱助手作用域。5 项现代入口测试和原有取消策略测试通过，APK 已核验现代模块声明且未打包框架 API。
教务已恢复：真机重新取得 193 条课程、14 节作息和 17 条消息，应用重启后可恢复已保存的教务会话。
Android 0.3.2 修复小爱页面未就绪时过早领取任务、写入请求课表 ID 类型错误及课程样式 JSON 格式问题。实机自动流程已完成课程写入、作息更新和选中课表，随后独立读回核对 43 组课程与全部作息一致，其他两张课表保留。课程随机生成可读的文字/背景色，同名课程共享颜色，后续覆盖保留已有颜色；颜色也纳入服务器读回校验。驱动 22 项测试通过。当前学校实际调课后产生新版本的自动更新还需在真实变更发生时验证。
新旧包名分别安装；旧版缓存不会自动删除。
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

## 持续集成与发布

版本号统一位于 `version.properties`。Android 的 `versionCode` 必须在新版本递增；Android 与 Windows 共用 `versionName`。

- 向 `main` 推送、提交 Pull Request 或手动运行 CI：执行核心、Android、Windows 界面及小爱驱动测试，生成调试 APK、带 Java 运行环境的 Windows ZIP 和校验文件。
- 创建并推送与版本号一致的标签（例如 `v0.4.0`）：再次构建并使用固定发布签名生成正式 APK，校验两个平台产物后创建草稿 Release。检查草稿附件和说明后在 GitHub 发布。
- Release 工作流也支持手动重试已有标签；已存在的 Release 不会被静默覆盖。
- Actions 依赖固定到提交 SHA；Pull Request 默认只读权限，正式签名不用于 PR 构建。

发布签名由以下 GitHub Actions Secrets 提供：

| 名称 | 内容 |
| --- | --- |
| `AHUCM_SIGNING_STORE_B64` | PKCS12 发布密钥库的 Base64 |
| `AHUCM_SIGNING_PASSWORD` | 密钥库及私钥密码 |
| `AHUCM_SIGNING_ALIAS` | 签名别名 |

密钥库只恢复到临时构建目录，任务结束删除；请另外保管离线备份，后续升级必须使用同一签名。
发布构建还会核对 `release-signing-certificate.sha256` 中的公开证书指纹，防止误换密钥导致无法升级。
正式 APK 和调试 APK 使用不同签名，不能相互覆盖安装；切换安装前应确认能重新登录同步，卸载会删除对应应用的本地数据。
CI 不包含学生凭据或真实课表，不运行需要账号的实网测试。

Windows 构建须在 Windows 上运行：

```powershell
.\gradlew.bat -PandroidEnabled=false :core:test :composeApp:desktopTest :composeApp:createDistributable
python tools/ci/package_build.py windows
```

Android 本地签名发布时通过环境变量 `AHUCM_KEYSTORE_PATH`、`AHUCM_KEYSTORE_PASSWORD`、`AHUCM_KEYSTORE_ALIAS` 指定密钥，再执行 `:androidApp:assembleRelease`。

## 许可证

本项目原创代码与图标采用 [Apache-2.0](LICENSE)。依赖声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
