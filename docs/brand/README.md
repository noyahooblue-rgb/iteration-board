# 迭代小组平台 · 品牌规范

> 定稿口径：设计由 Claude 出图、用户审核拍板（任务 ajenttde）。实现严格照定稿，不自行改造型、改配色。

## 设计说明：迭代之芯 · 彩带交织

- 图形：∞ 是一条白色彩带，中间一条从另一条上面压过去（带一点阴影），从左到右由纯白渐变到浅蓝 `#B3DBFF`；右上角一颗暖金色 AI 星芒（`#FFE36E → #FF9F0A`，带柔光）。
- 底色：天空蓝竖向渐变 `#5AC8FA → #0A6CFF`；深色版 `#2A5BB8 → #0B1F4D`。圆角 `rx=116/512`（≈22.6%），上方白色光泽层 + 内描边。
- 含义：∞ = 迭代持续推进；交织 = 团队协作；星芒 = AI。

## 源文件（仓库内定稿存档）

定稿 4 个源 SVG 与派生矢量统一存放在 **`frontend/public/brand/`**（随前端一同发布）：

| 文件 | 用途 |
|---|---|
| `brand-master.svg` | 标准版矢量母版（浅色场景默认，512 画布） |
| `brand-small.svg` | ≤32px 专用简化版：去阴影、线条加粗、星芒放大 |
| `brand-dark.svg` | 深色模式版（深海蓝底；与标准版仅底渐变不同） |
| `brand-ai-spark.svg` | AI 星芒单独符号（24 画布）；前端内联组件为 `frontend/src/components/AiSpark.vue` |
| `brand-mono-white.svg` / `brand-mono-dark.svg` | 单色版（纯白 / 深藏青 `#0B1F4D`，第二轮由纯黑改色并改名），用于打印、水印等单色场景 |
| `favicon-badge-64.png` / `favicon-badge-dark-64.png` | 未读版图标（浅/深，第二轮定稿图形：本体自带大红点 + 白圈、右上极角压星芒） |
| `source-round2/` | 第二轮定稿源 SVG（未读浅/深、apple-touch、maskable）存档 |
| `lockup-horizontal-light.png` / `lockup-horizontal-dark.png` | 横版组合标：图标 + 「迭代小组平台」字标（浅/深背景各一） |
| `lockup-stacked-light.png` / `lockup-stacked-dark.png` | 竖版组合标（浅/深背景各一） |

站点图标（`frontend/public/`）：

| 文件 | 用途 |
|---|---|
| `favicon.svg` | brand-small 简化版 + `prefers-color-scheme` 媒体查询（浅色天空蓝 / 深色 brand-dark 配色） |
| `favicon.ico` | 16/32/48 三档（16/32 用 brand-small 渲染，48 用标准版） |
| `apple-touch-icon.png` | iOS 主屏图标 180 |
| `manifest.webmanifest` + `icons/icon-192.png` / `icon-512.png` / `icon-maskable-512.png` | PWA 清单（`theme_color #0A84FF`，`background_color #FFFFFF`；maskable 主体缩至 80% 安全区） |

字标字体口径：Inter + 系统中文字体，不引入新字体文件；lockup 为 PNG 成品图直接使用。

## 运行时切换规则

- 主题：`frontend/src/utils/favicon.ts` 的 `setAppIcon(theme, unreadCount)` 统一切换——`document.documentElement.dataset.theme` 为 `dark`/`graphite` 用深色版，`light` 或未设置用标准版。
- 未读：未读数 > 0 时 favicon 换第二轮定稿未读版图标（浅色 favicon-badge / 深色 favicon-badge-dark），标题加 `(n) ` 前缀承载真实计数；归零恢复。计数角标与缺陷角标已整类删除（第二轮裁定），界面不要再引用；第一轮叠加合成的 badge-unread 角标去留待确认，运行时不再使用。
- 桌面通知（Notification API）的 `icon` 使用 `/icons/icon-192.png`。

## 最小尺寸与留白

- 图标本体：最小 16px（favicon）；低于 16px 不使用。
- 组合标：横版最小宽 240px，竖版最小宽 120px；再小只用图标本体。
- 角标版：最小 48px 使用（角标在更小时不可读）。
- 留白：图标四周至少留出图标宽度 12.5%（1/8）的空白，不与其他图形贴边叠放。

## 禁用示例

- 不得拉伸：不得非等比缩放、旋转、镜像。
- 不得改色：不得更改渐变、彩带、星芒的任何颜色（含派生新色）。
- 不得去掉交叉：∞ 彩带的压叠交叉是核心识别点，不得简化为普通双环。
- 不得把星芒移回中间：星芒固定在右上角，不得居中或移至其他位置。
- 不得加故事卡片：不得给图标加边框卡片、底纹、额外阴影/描边/滤镜或文字说明框。
- 不得拆解：不得单独抽取无穷环改作他图（星芒元素文件 `brand-ai-spark.svg` 除外，其用途已单列）。
- 不改组合标的字体与字距；文字内容固定为「迭代小组平台」。

## 重新生成

改了源 SVG 后，在仓库根目录运行 `python3 tools/brand/build_assets.py` 一键重出 PNG/ICO/manifest 图标（依赖 Playwright Chromium 渲染 + Pillow；渲染器不要换 cairosvg——实测它丢弃投影滤镜且环交叉处裁剪出错位）。
