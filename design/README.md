# 岐黄课表 · Logo 设计规范

![岐黄课表 Logo](logo-lockup-light.png)

「玉叶课历」将课历装订、展开的书页与负形草叶合为一个图形。深玉绿配暖白，保留学习与
中医药院校的气质；形象依靠轮廓与留白成立，换成 Monet 单色后仍可识别。

## 概念

| 元素 | 含义 |
| --- | --- |
| 圆角方块底 | 应用图标本体，半径 112/512（约 21.9%） |
| 顶部双装订环 | 课历与时间安排 |
| 展开的书页 | 学习、课程与积累 |
| 负形草叶与叶脉 | 岐黄、草本与生长 |
| 深玉绿与暖白 | 稳定、温和的品牌配色 |

正式资源是可编辑矢量重绘，不使用生成预览图里的阴影和多色折页；Android、Windows、鸿蒙与
应用内标志使用同一组路径，避免单色化时丢失图形含义。

## 调色板

| 用途 | 色值 |
| --- | --- |
| 背景渐变起点（左上） | `#124F46` |
| 背景渐变终点（右下） | `#267363` |
| 图形 | `#FFF7E2` |
| 深色文字 / 单色正形 | `#124F46` |
| 辅助文字 | `#4E7F6C` |

渐变方向约 135°，端点在设计网格 `(40,16) → (472,496)`。

## 几何规范（512 × 512 设计网格）

- 背景方块：`0,0,512,512`，圆角 `112`
- 图形边界约为 `112..400 × 96..416`，由五条绝对坐标贝塞尔路径构成。
- Android 自适应前景绕中心缩放 `0.82`，所有有效像素位于中央 `66/108` 安全圈内。
- 几何源文件是 `design/tools/logo_spec.py` 的 `GLYPH_PATHS`；生成 SVG、Android vector 和 Compose 路径。

## Monet 取色

沿用同一套课表几何，启动图标按系统版本适配：

- Android 8–11：普通自适应图标使用深玉绿渐变与暖白图形。
- Android 12+：普通自适应图标的渐变与图形通过 `values-v31` / `values-night-v31`
  引用系统 `system_accent1_*` / `system_neutral1_*` 调色板，跟随系统壁纸取色与深浅模式。
  浅色使用 accent1 100→200 与 neutral1 900，深色使用 accent1 800→700 与 neutral1 100。
- Android 13+：`mipmap-anydpi-v33` 额外声明 **monochrome 图层**，引用
  `drawable/ic_launcher_monochrome.xml`。它只提供透明背景上的白色图形遮罩；开启主题图标且
  启动器支持时，最终前景和背景颜色由启动器决定。`mipmap-anydpi-v26` 仅声明彩色前景与背景。
- 启动图标的配色跟随系统，与应用内手动选择的主题色独立；启动器可能缓存图标，实际刷新行为
  需要在对应设备上验证。Windows 的静态 `icon.ico` / `icon.png` 继续使用品牌色。
- 自适应图标前景为 `0.82` 缩放版本，保证图形落在 66dp 安全圈内，圆形 / 圆角方形
  启动器遮罩下都不会被裁切（见 `adaptive-preview.png`）。
- 应用内「暂无课表」空状态使用同一几何的 `BrandMark`，颜色直接取
  `MiuixTheme.colorScheme.primary / onPrimary`，因此 Android 与 Windows 的 Mon­et
  动态配色都会生效。

下图为单色主题图标的示意配色，不代表设备实际 Monet 调色板或普通彩色图标的实时截图。

![Monet 取色预览](monet-preview.png)

![自适应图标遮罩预览](adaptive-preview.png)

## 小尺寸

![尺寸预览](sizes-preview.png)

最小可用尺寸 16 px。小于 32 px 时细叶脉会弱化，书页与装订轮廓保留；不要在小尺寸下添加细节。

## 文件清单

| 文件 | 用途 |
| --- | --- |
| `logo.svg` | 主标志（圆角方块，1024 画布） |
| `logo-512.png` / `png/logo-*.png` | 16–1024 px 位图导出 |
| `logo.ico` | Windows 多尺寸图标（16–256） |
| `logo-mark.svg` | 仅图形、透明背景，用于自定义底色场合 |
| `logo-monochrome.svg` / `.png` | 单色正形，供系统染色的场景 |
| `logo-adaptive-background.svg` | Android 自适应图标背景层 |
| `logo-adaptive-foreground.svg` | Android 自适应图标前景层（0.82 缩放） |
| `logo-lockup-light.png` | 横向组合标志（浅色底） |
| `logo-lockup-dark.png` | 横向组合标志（深色底） |
| `sizes-preview.png` / `adaptive-preview.png` / `monet-preview.png` | 设计检查图 |

已接入位置：

- Android：`androidApp/src/main/res/drawable/ic_launcher_*.xml`、
  `mipmap-anydpi-v26/ic_launcher*.xml`、`mipmap-anydpi-v33/ic_launcher*.xml`、
  `values*/ic_launcher_colors.xml`、`mipmap-*/ic_launcher*.png`
- Windows：`composeApp/src/desktopMain/resources/icon.ico`（安装包与 exe 图标）、
  `icon.png`（窗口图标）

## 重新生成

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File design\tools\build-logo.ps1
```

流程：`build_logo.py spec` 生成 SVG/矢量 XML 与无头浏览器页面 → `render.ps1` 用
Microsoft Edge 栅格化 → `build_logo.py export` 生成 PNG、ICO、Android mipmap、
组合标志与预览图。依赖 Windows 自带微软雅黑 / 等线字体，以及已安装 Pillow 的 Python 3。所有几何参数集中在 `design/tools/logo_spec.py`，改颜色或间距后重跑即可。
同一生成器也生成版本化配色资源及 adaptive / monochrome 声明，重新生成会保留 Monet 适配。

## 使用约束

- 保持等比缩放，不要拉伸、加描边、加投影或改变课程块间距。
- 标志四周至少保留图标宽度 1/8 的留白。
- 深色底用 `logo-lockup-dark.png`，浅色底用 `logo-lockup-light.png`。
- 需要单色印刷或系统染色时使用 `logo-monochrome.svg`，不要把彩色版去色。
- 主色只用于品牌与强调，不要替换应用内 Monet 主题色。
