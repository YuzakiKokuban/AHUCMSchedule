# Android Update Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Android 关于、可靠的应用内 APK 更新与 0.4.1 正式发布。
**Architecture:** Android 专属 AboutActivity / Compose 页面；ViewModel 保存网络任务状态；纯 Kotlin ReleaseUpdate 解析与校验规则。共享 UI 仅新增可选平台入口。
**Tech Stack:** Kotlin、Compose miuix、HttpURLConnection、kotlinx.serialization、Android FileProvider。
**Spec:** docs/superpowers/specs/2026-10-24-android-update-design.md

## Global Constraints
版本 0.4.1 / versionCode 7；仅用户主动检查、下载、安装；官方正式发布源；不发送学校数据；保留发布签名。

## Review Focus
无 APK/无摘要时拒绝下载；同版和降级拒绝；恶意资源 URL 拒绝；下载截断/超限拒绝；安装授权返回不能绕过 APK 身份核验。

### Task 1: 更新协议
- [x] 在 androidApp/src/test/kotlin/com/yuzakikokuban/ahucm/update/ReleaseUpdateTest.kt 先添加版本比较、正式 Release 解析、非官方地址、缺少摘要与非法资源测试。
- [x] 运行 :androidApp:testDebugUnitTest，确认新符号缺失导致失败。
- [x] 创建 androidApp/src/main/kotlin/com/yuzakikokuban/ahucm/update/ReleaseUpdate.kt，定义 ReleaseUpdate.parse(json, currentVersion): ReleaseUpdate?，严格解析 GitHub 官方资源和 SHA-256。
- [x] 重跑测试通过。

### Task 2: Android 关于与下载安装
- [x] 新增 AboutActivity.kt、UpdateViewModel.kt，显示版本版权及随包许可；更新状态含检查、可下载、进度、可安装与失败。
- [x] 更新 MainActivity、PlatformActions、Manifest、FileProvider paths 与 APK 本地许可；启用 BuildConfig。
- [x] 下载限制大小和时间、校验摘要及包名/版本/签名，安装前重复核验；未知来源授权后由按钮重新发起安装。
- [x] 新增共享 Compose 入口测试，运行核心、Android 与桌面测试并构建 APK；ADB 可用时运行设备验证。

### Task 3: 审查与发布
- [x] 更新版本、README、Release Notes，修复 Release 工作流标题乱码。
- [x] 独立审查完整 diff，检查安全边界、错误路径、测试与安装行为。
- [ ] 构建校验签名 APK，提交、合并、推送标签；等待发布 CI 双端成功，核对附件及签名，再发布正式 Release。

## 验证记录
- 新增单元与界面入口测试先观察到缺失符号失败；实现后核心、Android、Compose 套件通过。
- 4 个依赖 PR 单独检查 CI 与官方变更说明，#9 补齐 Kotlin 版本对齐后 CI 通过，再逐个合并。
- 集成依赖后完整套件通过；Node 共 27 项通过。
- 真机 3 项通过：版权与版本、左上角返回图标、FileProvider 权限边界、损坏缓存恢复。Compose 文本需遍历虚拟无障碍节点，不能依赖原生 findAccessibilityNodeInfosByText。
- 原包名的小爱通道是固定协议；临时独立包名不具备此能力，隐藏该测试变体的模块入口。正式版维持原包名和通道。
- 错误证书构建已实际拒绝。CI 的 PR 路径不继承签名 Secrets；可信构建使用既有官方证书。
- 本地 JDK21 下载受网络阻断，使用仅本地 init script 选择 JDK25；CI 仍采用 JDK21。
