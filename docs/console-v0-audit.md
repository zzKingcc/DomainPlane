# 管控台 V0 · 准入与冲突审计

> 执行日期：2026-10-19 · 对应方案 `管控台工程化修补方案.md` 第 9 章 V 轨
> 改动前备份：`.workbuddy/backup/console-20261019-120336`

## 1. 结论速览

| 项 | 状态 | 说明 |
|---|---|---|
| 冲突类改名 | **已完成** | 11 个文件、59 处，`grep` 复核无残留 |
| Tabler vendor 落地 | **已完成** | `assets/vendor/tabler.min.css` + `tabler-props.css`（1.6.1，MIT） |
| token 接入层 | **已完成** | `assets/console-tokens.css`，33 个引用变量、0 缺失 |
| 浏览器准入 | **待确认** | 唯一阻塞项，见第 5 节 |
| 视觉换皮 | **未开始** | 属 V2；V1 只做底座对齐 |

**预期修正**：V1 完成后的实际视觉变化有限（配色、阴影、边框色、字体栈）。Tabler 的「样子」来自组件规则（`.card` / `.btn` / `.table` 的完整样式），必须由 V2 加类才能拿到。此前「V0+V1 就能整站换皮」的说法不成立，已回写方案。

## 2. 改名执行记录

| 原名 | 新名 | 理由 |
|---|---|---|
| `.card` | `.dp-card` | Bootstrap 的 `.card` 是 flex 列容器，内边距在 `.card-body` |
| `.card-body` | `.dp-card-body` | Bootstrap 中是 `flex:1 1 auto; padding` |
| `.row` | `.dp-row` | Bootstrap 中是 flex 行 + 负 margin 栅格 |
| `.nav` | `.dp-nav` | Bootstrap 中是 `flex-wrap:wrap` 导航容器 |
| `.nav-item` | `.dp-nav-item` | Bootstrap 中是列表项 |

逐文件替换数：

| 文件 | 处数 |
|---|---|
| `assets/console.css` | 18 |
| `models.html` | 10 |
| `infra.html` | 7 |
| `mcp.html` | 6 |
| `domains.html` | 5 |
| `knowledge.html` | 4 |
| `account.html` | 3 |
| `prompts.html` | 2 |
| `assets/console.js` | 1（模板串 `class="nav"`） |
| `instances.html` / `login.html` | 各 1 |
| `assets/console.js:151` | 手工 1（`'nav-item'` 无前导点，脚本规则覆盖不到） |

未受影响的近似名（已核对）：`.card-head` `.card-note` `.card-foot` `.card-tune` `.model-card` `.dp-card-body`。

**复核方式**：`grep 'class="(card|card-body|row|nav|nav-item)[ "]|dp-dp'` → 0 命中。

## 3. 元素选择器冲突（V2 处理）

这些裸元素规则会盖住 Tabler 的组件类，V2 按组件推进时逐条收口：

| 位置 | 规则 | 影响 |
|---|---|---|
| `console.css:151` | `button { background: var(--primary) }` | 所有按钮被染成主色，`.btn-secondary` 不生效 |
| `console.css:184` | `input:not([checkbox]):not([radio]), select` | 覆盖 `.form-control` 的内边距与宽度 |
| `console.css:427` | `table / th / td` | 覆盖 `.table` 的边框与内边距 |
| `console.css:630` | `.tune-field textarea` | 仅局部，风险低 |

`console.css:21` 的 `*{margin:0;padding:0}` 保留未删——因为不引 `tabler.min.css`，无 reboot 需要让路。若 V2 决定引 min.css，必须先删这条。

## 4. Tabler 变量实测（替换方案里的「待核对」）

| 意图 | 实际变量 | 实测值 | 出处 |
|---|---|---|---|
| 主色 | `--tblr-primary` | `oklch(54.6% 0.1724 254.2deg)` | props |
| 次要文字 | `--tblr-secondary` | `oklch(55.55% 0 0deg)` | props |
| 成功/警告/危险 | `--tblr-success` / `warning` / `danger` | `oklch(67.38% .1885 145.43deg)` / `oklch(77.02% .1658 71.02deg)` / `oklch(58.29% .1941 25.59deg)` | props |
| 卡片阴影 | `--tblr-shadow-card` | `0 1px 2px 0 color-mix(in oklab, var(--tblr-shadow-color) 12.5%, transparent)` | props |
| 输入框阴影 | `--tblr-shadow-input` | 同上 | props |
| 卡片圆角 | `--tblr-border-radius-lg` | 8px | props |
| 基础圆角 | `--tblr-border-radius` | 6px | props |
| 字体栈 | `--tblr-font-sans-serif` | `system-ui, -apple-system, …, Segoe UI, Roboto, …` | props |
| 正文字号 | `--tblr-body-font-size` | 0.875rem | **min.css** |
| 页面底色 | `--tblr-body-bg` | `light-dark(oklch(98.51% 0 0), …)` | **min.css** |
| 正文色 | `--tblr-body-color` | `light-dark(oklch(26.86% 0 0), …)` | **min.css** |
| 边框色 | `--tblr-border-color` | `light-dark(var(--tblr-gray-200), …)` | **min.css** |
| 半透明边框 | `--tblr-border-color-translucent` | `light-dark(color-mix(in oklab, var(--tblr-gray-800) 11.9%, transparent), …)` | **min.css** |
| 卡片/表单底色 | `--tblr-bg-surface` / `--tblr-bg-forms` | `light-dark(...)` 取 light 值 = `#fff` | **min.css** |

**关键结论**：`tabler-props.css` 只有基础令牌，**不含 body-bg / border-color 这类语义层变量**（它们在 `tabler.min.css` 里）。因此 `console-tokens.css` 按 light 模式取值补齐了 6 个，这是本项目「只引 props、不引 min.css」的代价，也是它的收益——不引 min.css 就没有 reboot 副作用。

## 5. 浏览器准入确认单

**只引 `tabler-props.css`（当前做法）时**，依赖的 CSS 特性与下限：

| 特性 | 用途 | 最低版本 |
|---|---|---|
| `oklch()` | 全部色板 | Chrome 111 / Safari 15.4 / Firefox 113 |
| `color-mix()` | 半透明边框、`--primary-dim` | Chrome 111 / Safari 16.2 / Firefox 113 |

→ **实际下限：Chrome/Edge ≥ 111、Safari/iOS ≥ 16.2、Firefox ≥ 113**

**若 V2 改为引 `tabler.min.css`**，会追加 `light-dark()` / `:has()` / `@property`，下限抬到 **Chrome 123 / Safari 17.5**。

需要确认的项（交付环境）：

- [ ] 目标客户/运维使用的浏览器与版本
- [ ] 是否存在 IE 或国产双核浏览器的兼容模式
- [ ] 若低于上述下限：退回「不引 props，只把 Tabler 的色值手抄进 `console-tokens.css`」

## 6. 本轮实际改动文件

| 文件 | 改动 |
|---|---|
| `assets/vendor/tabler.min.css` | 新增（635 KB，未引，留给 V2 决策） |
| `assets/vendor/tabler-props.css` | 新增（23.7 KB，已被 `console.css` 引用） |
| `assets/console-tokens.css` | 新增（token 映射层） |
| `assets/console.css` | 顶部 `@import` ×2；`:root` 变量外移；卡片加阴影 + 半透明边框；输入框加阴影；表格边框改半透明；字体栈改用 `--tblr-font-sans-serif` |
| 11 个页面 HTML + `console.js` | 冲突类改名（见第 2 节） |

## 7. 下一步

1. 确认第 5 节的浏览器准入
2. 起 9527 逐页看一遍 V1 效果，比对 P0 基线截图
3. 决定 V2 范围：按钮 / 表单 / 表格 / 徽标按组件推进，卡片维持自写
