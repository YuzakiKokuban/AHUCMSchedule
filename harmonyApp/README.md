# 岐黄课表 · HarmonyOS NEXT 适配原型

**当前状态：已使用 DevEco Studio 26.0.0.851 / SDK 26.0.0.105 通过 ArkTS 编译和 HAP 打包。输出是未签名的预览 HAP，不能直接当作已签名的安装包；尚未进行鸿蒙真机登录和运行验证。**

这是独立的 ArkTS/ArkUI Stage 工程，使用现有学校只读协议和相同数据字段。原生页面包括七天十四节课表、周切换、课程详情、教务通知和每账号离线缓存；没有 Xposed 相关功能区。Android/Windows 项目仍由根目录 Gradle 构建。

## 在 DevEco Studio 中打开

1. 打开本目录 `harmonyApp`，使用 DevEco Studio 自带 SDK。项目保留 Hvigor 5.0 模型，兼容版本为 HarmonyOS 5.0.0 / API 12，实际编译和目标版本随 IDE 的 SDK 确定。
2. 让 IDE 同步 Hvigor/OHPM 依赖。仓库不携带来源不明的 wrapper 二进制，使用 IDE 自带构建工具。
3. 在 Project Structure → Signing Configs 设置本机调试签名，然后用鸿蒙设备或模拟器构建运行。签名证书、口令、设备配置及 SDK 路径不要提交。
4. AppScope 当前版本号仅代表原型，正式发布前要接入根目录版本管理并完成签名、发行和真机验收。

Windows 命令行可在仓库根目录运行 `powershell -ExecutionPolicy Bypass -File tools/build-harmony.ps1`。脚本自动读取 DevEco 安装位置，也支持 `-DevEcoHome`；仅对构建进程设置 Java、Node 与 SDK 路径。构建后运行 `python tools/ci/package_harmony.py` 可将未签名 HAP 和校验文件整理到根目录 `outputs`。

## 数据流

学校网页登录在隐私 ArkWeb 中完成，使用精确 CAS 源的主页面消息端口捕获提交的学号，不读取密码。完成登录后点击“完成登录并同步”，在教务主页面上下文发出同源只读请求，获取完整官方 XLS、14 节作息、已读/未读通知。

XLS 交给独立的本地 ArkWeb + 随包 SheetJS CE 解码。该页面没有原生 JS 代理，CSP 禁止联网；`schedule-core.js` 校验学期、周次、日期、节次和完整性，复用 Android/Windows 的合并教室及空课表保护规则。任意课表校验失败保留原缓存。通知获取失败保留对应账号旧通知，不修改课表。

缓存写入应用私有文件目录，用捕获学号的 SHA-256 分账号；完整快照落盘后原子替换，再切换活动账号指针。原型不存密码或原始 Cookie，Web 会话随进程退出失效，离线课表仍可查看。重新登录重建独立隐私 WebView，不能用手输标签绑定其他人的学校会话。

## 可执行验证

在仓库根目录运行：

```powershell
node --test harmonyApp/tests/*.test.cjs
```

匿名 XLS 由 SheetJS 写成 BIFF8，再走实际读取路径。覆盖课程日期/周次/节次、13–14 节课程、非法/空 XLS、学校地址白名单、作息校验和线上学期一致性。测试不需要账号，不请求学校；ArkTS 编译已另行通过，ArkWeb 与真机仍需实际验证。

## 真机待验

- 消息端口与隐私 WebView 在目标系统中的兼容性（源码和资源已通过 SDK 编译）。
- 学校 CAS 提交学号捕获、验证码、登录重定向、教务同源 XLS 下载及会话失效。
- 校验后缓存、断网/重启恢复、账号切换隔离、同步失败保留旧数据。
- 原生网格排版、手势、无障碍、主题及通知展示。

这是手动同步原型；后台同步、日历写入、文件选择导入、小组件、系统通知和上架不在这一轮验证范围内。后续是否保留 ArkWeb 解析路径，需根据真机性能与稳定性决定。

## 依赖与许可

SheetJS CE **0.20.3** 从官方固定版本 CDN 下载并随包提供，运行时不连接 CDN：

- [官方分发文档](https://docs.sheetjs.com/docs/getting-started/installation/standalone/)
- 原始文件：`https://cdn.sheetjs.com/xlsx-0.20.3/package/dist/xlsx.full.min.js`
- SHA-256：`cc015130aa8521e7f088f88898eba949ccdcbfb38df0bd129b44b7273c3a6f41`
- Apache-2.0 许可：`entry/src/main/resources/rawfile/vendor/SHEETJS-LICENSE`

平台实现参考：[ArkWeb 消息通道](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/web-app-page-data-channel)、[ArkTS](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/arkts-overview)。
