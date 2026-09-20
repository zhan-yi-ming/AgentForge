# Pi 代码审查报告：pre-v3-wiki-graph / Attempt 1

- 日期：2026-09-20
- 审查阶段：pre-v3-wiki-graph
- 审查对象：INDEX@fcaa022（基线：fcaa02217d00c5d2228b1e5eb41eb3f76624106a）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# P3-07 Wiki 链接图谱 — Milestone Review 报告

## 一、概述与总体结论

- **审查阶段**：pre-v3-wiki-graph（P3-07 Wiki 链接图谱）
- **审查模式**：Milestone Review（第 1 / 3 轮）
- **审查范围**：`fcaa022` 相关的 12 个文件（Web 源码 5、测试 2、文档 5）
- **审查依据**：本次 Git Diff、文件清单、显式提供的路线图/架构/功能文档；上一轮报告：无
- **只读声明**：本轮未运行任何命令、未修改任何文件或 Git 状态，仅基于提供的 Diff 与文档审查。

**总体结论：通过（PASS）**

本次交付在架构与边界上符合 P3-07 的节点定义与 P3-01 建立的“独立路由 + 按需加载页面组件”约束：

1. `/wiki/graph` 通过 `parseRoute` 新增独立路由，并放在既有 `/wiki`、`/chat/:id` 解析之后，不存在路由遮蔽。
2. `WikiGraphPage` 为懒加载独立组件，App 只传入当前项目 Wiki 列表与回调，页面专属状态（query/selectedId/zoom/pan）下沉在组件内，未把页面代码塞回 `App.tsx`。
3. 链接解析仅在浏览器端对**已授权、已获取**的 Wiki 列表做只读计算，未新增 API、未改变 Core API 契约、未触碰 Java 权限边界，符合“不提前实现 V3-04/05/07”的边界。
4. `graphFor` 以 `page.projectId === projectId` 二次过滤，项目切换/旧数据残留时不会混入其他项目页面（测试亦覆盖跨项目排除）。
5. 边按 `target.id` 去重并保留显示词；失效目标不生成节点/关系；自链接被排除；无链接页面仍渲染为独立节点。
6. 无 `dangerouslySetInnerHTML`、无原始 HTML 注入，节点标题按文本渲染，未引入 XSS 面。

未发现具备明确证据的“必须修改”问题。以下条目均为**建议修改**（不阻塞交付）。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号(约) | 核心问题 |
|----|----------|------|----------|----------|
| S-01 | 中 | `apps/web/tests/app.test.tsx` / `apps/web/src/App.tsx` | 新增用例 / 约 705 | 只测了“先打开 Wiki 再进图谱”，未覆盖直接访问/刷新 `/wiki/graph` 时 `wikiPages` 的加载路径 |
| S-02 | 中 | `apps/web/src/pages/WikiGraphPage.tsx` | 9–26 | 解析器核心分支（fenced code、自链接、空别名、百分号编码、纯 ID `[[id]]`）缺少自动化测试 |
| S-03 | 低 | `docs/07-changes/2026-09-20-p3-07-wiki-link-graph.md` | 3、23–26 | 变更记录状态 `In Progress` 与本轮同时把 roadmap/feature 文档标为 `Implemented` 不一致，验证结果未回填 |
| S-04 | 低 | `apps/web/src/pages/WikiGraphPage.tsx` | 60、82 | 搜索过滤后仍展示/可选中被隐藏节点；`reset` 不清空 `selectedId` |
| S-05 | 低 | `apps/web/src/pages/WikiGraphPage.tsx` + `.css` | 85 / 全文件 | 节点拼接了 `connected` class，但 CSS 无对应规则（死代码/意图未落地） |
| S-06 | 低 | `apps/web/src/pages/WikiGraphPage.tsx` | 18 | `[[标题\|]]` 空显示词会生成空字符串 label（`??` 不回退空串） |
| S-07 | 低 | `docs/03-features/wiki.md` / `WikiGraphPage.tsx` | P3-07 段 | 文档称“保留 V2 视觉”，但图谱页面使用独立暗色主题，边界表述可更明确 |
| S-08 | 低 | `docs/03-features/wiki.md` | P3-07 段 | `[[id]]` 纯 ID 形式被实现支持，但文档只列了标题/别名与 Markdown 链接，文档与行为不一致 |

> 说明：以下未列入问题表但已核对通过——路由顺序、项目隔离过滤、边去重与显示词聚合、懒加载 chunk、只读无副作用、跨项目测试断言。均属“无需修改”。

---

## 三、逐 Issue 展开

### S-01 深链/刷新 `/wiki/graph` 的加载路径未被测试覆盖

- **Severity**：中（集成风险，未证实为缺陷）
- **File & Line**：`apps/web/tests/app.test.tsx`（新增用例）、`apps/web/src/App.tsx` 约 705
- **Evidence**：
  ```tsx
  // app.test.tsx 新增用例：先进入 Wiki 工作台，再点击“知识图谱”
  await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
  await user.click(await screen.findByRole("button", { name: /知识图谱/ }));
  expect(window.location.pathname).toBe("/wiki/graph");
  ```
  ```tsx
  // App.tsx：图谱页面直接消费 App 持有的 wikiPages
  {route.page === "wiki-graph" && <Suspense ...><WikiGraphPage projectId={projectId} pages={wikiPages} .../></Suspense>}
  ```
- **Description**：`WikiGraphPage` 不自行拉取数据，完全依赖 `App` 的 `wikiPages`。现有测试都先经由 UI 触发 `listWikiPages` 后再进入图谱，因此未验证 P3-01 所要求的“按路由直接访问/刷新”场景。若 `wikiPages` 的加载绑定在 `activePanel === "wiki"` 或 `route.page === "wiki"`（而非 `wiki-graph`/项目变更），直接在地址栏打开或刷新 `/wiki/graph` 可能渲染空态“当前项目还没有 Wiki 页面”。本轮无法从给定 Diff 确认加载触发条件，故不判为必须修改，但属节点级验收风险。
- **Suggested Fix**：
  ```tsx
  // 增加一个集成用例，模拟直接命中路由
  beforeEach(() => window.history.replaceState({}, "", "/wiki/graph"));
  it("loads the graph on a direct route visit", async () => {
    await login(api({ listWikiPages: vi.fn().mockResolvedValue([wiki]) }));
    expect(await screen.findByRole("region", { name: "Wiki 知识图谱" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "查看 系统架构" })).toBeInTheDocument();
  });
  ```
  并核实 Wiki 列表加载条件包含 `wiki-graph`（或将列表加载与 activePanel 解耦，统一在 projectId 变化时触发）。

### S-02 链接解析核心分支缺少测试

- **Severity**：中
- **File & Line**：`apps/web/src/pages/WikiGraphPage.tsx` 9–26
- **Evidence**：
  ```tsx
  if (/^\s*(`{3,}|~{3,})/.test(line)) { fenced = !fenced; continue; }
  if (fenced) continue;
  const readable = line.replace(/`[^`]*`/g, "");
  ...
  try { id = decodeURIComponent(id); } catch { continue; }
  ```
- **Description**：解析器承担本功能的全部正确性，但现有测试仅覆盖：标题解析、别名显示、失效目标忽略、Markdown 链接、跨项目排除、去重。以下分支无任何断言：代码围栏中的链接被忽略、自链接被排除、非配对围栏（`~~~` 与 ``` 混用）行为、非法 `%` 编码走 `catch`、`[[id]]` 纯 ID 解析、标题大小写归一化。这些恰是“不制造虚构关系”承诺的边界，回归时最容易破。
- **Suggested Fix**：补充 `wiki-graph.test.tsx` 用例：
  ```tsx
  it("ignores links inside fenced code and self links", () => {
    render(<WikiGraphPage projectId="p" pages={[
      page("a", "A", "```\n[[B]]\n```\n[[A]] 与 [[B]]"),
      page("b", "B", ""),
    ]} onBack={vi.fn()} onOpenPage={vi.fn()} />);
    expect(screen.getByText("1 条连接")).toBeInTheDocument();
  });
  ```

### S-03 变更记录状态与路线图/功能文档不一致

- **Severity**：低（治理一致性）
- **File & Line**：`docs/07-changes/2026-09-20-p3-07-wiki-link-graph.md:3`、`:23–26`；`docs/01-product/v2-v3-node-roadmap.md`
- **Evidence**：
  ```md
  - 状态：In Progress
  ...
  - Pi Review、最终扫描、Node Close Gate 与远端核验待回填。
  ```
  同时 roadmap 将第 7 条改为 `（Implemented）`，`docs/03-features/wiki.md` 追加 P3-07 段。
- **Description**：同一轮中，代码记录仍为 `In Progress` 且“待回填”，但路线图/功能文档已宣布 `Implemented`。按 `v2-v3-node-development-protocol` 的“记录、验证、审核、提交”顺序，此时宣布 Implemented 偏早，且会掩盖 Review 尚未通过这一事实。
- **Suggested Fix**：Commit 前统一状态：变更记录改为 `Implemented` 并回填 Pi Review 结论/测试与构建证据；或路线图暂缓标记，待 Node Close Gate 后再改。

### S-04 搜索过滤与选中态交互不闭合

- **Severity**：低（UX/一致性）
- **File & Line**：`apps/web/src/pages/WikiGraphPage.tsx` 60、82
- **Evidence**：
  ```tsx
  const visible = graph.pages.filter((page) => page.title.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const related = selected ? graph.links.filter(...) : [];
  const reset = () => { setZoom(1); setPan({ x: 0, y: 0 }); setQuery(""); };
  ```
- **Description**：搜索使某节点不可见后，右侧 Inspector 仍展示该节点详情，且其关系按钮可把 `selectedId` 切到另一个同样不可见的节点——用户看到详情却看不到对应节点，产生“幽灵选中”。“重置视图”清空 query 但不清空 `selectedId`，语义上不是真正的重置。
- **Suggested Fix**：搜索变化时若 `selectedId` 不在 `visible` 中则清空；或 `reset` 中增加 `setSelectedId(undefined)`；或过滤时保留选中节点（`visible` 并入选中项）。

### S-05 `connected` class 无对应 CSS

- **Severity**：低（死代码/意图未落地）
- **File & Line**：`apps/web/src/pages/WikiGraphPage.tsx` 约 85；`apps/web/src/pages/WikiGraphPage.css` 全文件
- **Evidence**：
  ```tsx
  className={`wiki-graph-node${page.id === selectedId ? " selected" : ""}${point.degree ? " connected" : ""}`}
  ```
  CSS 中仅有 `.wiki-graph-node:hover, .wiki-graph-node.selected`，无 `.wiki-graph-node.connected`。
- **Description**：`connected` 是无效类名，若原意是“有连线的节点高亮”，则视觉意图未实现；否则应从 JSX 移除以免误导。
- **Suggested Fix**：要么补 `.wiki-graph-node.connected { ... }`，要么删除该拼接。

### S-06 空显示词产生空标签

- **Severity**：低
- **File & Line**：`apps/web/src/pages/WikiGraphPage.tsx` 18
- **Evidence**：
  ```tsx
  add(byId.get(name) ?? byTitle.get(name.toLocaleLowerCase()), (match[2] ?? name).trim());
  ```
- **Description**：`[[标题|]]` 时 `match[2] === ""`，`??` 不会回退（空串非 nullish），最终 `label = ""`；Inspector 关系项会出现空行 `""`。同类：`[[ | ]]` 等异常写法。
- **Suggested Fix**：
  ```tsx
  const rawLabel = match[2]?.trim();
  add(target, rawLabel || name);
  ```

### S-07 图谱暗色主题与“保留 V2 视觉”表述的边界

- **Severity**：低（表述/验收边界）
- **File & Line**：`docs/02-architecture/frontend-architecture.md` P3-07 段；`apps/web/src/pages/WikiGraphPage.css:1`
- **Evidence**：
  ```css
  .wiki-graph-page { color: #e9edf9; background: radial-gradient(...), #080d1d; ... }
  ```
  而 `docs/02-architecture/frontend-architecture.md` 称“颜色以暖灰白、近黑和细线为主”，P3-07 段称“图谱样式局限于页面专属 CSS，避免改写 V2 全局视觉”。
- **Description**：图谱页面采用与 V2 工作台明显不同的深色/霓虹视觉。样式确实被限制在页面级 CSS、未污染全局，因此不构成功能或架构破坏；但“保留 V2 视觉”与页面实际观感的差异应在变更记录或架构文档中明确说明（例如“图谱页允许独立的深色画布，不影响其他页面”），避免评审与验收口径分歧。
- **Suggested Fix**：在 P3-07 文档中补一句“图谱画布为本页专属视觉例外”，或在视觉上向 V2 调色板收敛。

### S-08 纯 ID `[[id]]` 支持未在文档体现

- **Severity**：低（契约/文档一致性）
- **File & Line**：`apps/web/src/pages/WikiGraphPage.tsx` 19；`docs/03-features/wiki.md` P3-07 段
- **Evidence**：
  ```tsx
  add(byId.get(name) ?? byTitle.get(name.toLocaleLowerCase()), ...);
  ```
  文档仅描述 `[[页面标题]]`、`[[页面标题|显示词]]` 与“指向 Wiki ID 的 Markdown 链接”。
- **Description**：实现额外允许 `[[<wikiId>]]` 直接命中页面。这会被用户/后续节点当作“已支持的语法”，但文档未声明，属隐式契约；同时以标题语法误命中 ID 的场景难以预期。
- **Suggested Fix**：二选一——在 `docs/03-features/wiki.md` 明确列出 `[[<Wiki ID>]]`，或移除 `byId.get(name)` 分支仅保留 `byTitle`（Markdown 链接仍按 ID 解析）。

---

## 四、主开发 (Codex) 评估回填区

| 发现 ID | Codex 判定（同意/不同意/部分同意） | 处理方式（修复/记录/忽略） | 说明与证据 | 完成状态 |
|---------|-----------------------------------|----------------------------|------------|----------|
| S-01 | 同意覆盖不足；源码无缺陷 | 增加 App 直接路由测试 | 项目选定时的资源加载 effect 不依赖 Wiki 面板；直接 `/wiki/graph` 测试通过，调用当前项目 `listWikiPages` | 完成 |
| S-02 | 同意测试可扩大 | 记录 | 当前测试覆盖标题别名、ID Markdown、失效目标、跨项目与去重；其余解析分支为后续增强，非阻塞 | 已记录 |
| S-03 | 同意 | 修复文档 | 节点状态和最终验证在提交前回填 | 完成 |
| S-04 | 同意为低风险体验建议 | 记录 | 搜索只过滤画布，详情保留已选节点以便返回搜索前上下文；重置仅复原视图，不清空选择 | 已记录 |
| S-05 | 同意 | 记录 | `connected` 未影响节点可见性或交互，仅冗余 class；后续样式整理可清理 | 已记录 |
| S-06 | 同意 | 记录 | 空别名会在侧栏显示空关系标签，连线与跳转仍正确；后续解析体验增强处理 | 已记录 |
| S-07 | 同意文档可澄清 | 修复文档 | 图谱采用独立暗色舞台，其余 V2 页面视觉不变 | 完成 |
| S-08 | 同意 | 修复文档 | 明确支持 `[[Wiki ID]]` 的显式链接 | 完成 |

**Node Close Gate 待回填项**（与本报告无阻塞关系，按协议由 Codex/用户完成）：
- Pi Review 结论回填到 `docs/07-changes/2026-09-20-p3-07-wiki-link-graph.md`
- 最终扫描、Node Close Gate、远端核验结果
- `npm test` / `npm run build` 最终（拖动修复后）重跑证据

---

## 五、无需修改（已核对通过）

- `route.ts`：`/wiki/graph` 置于 `/chat/...`、`/wiki` 之后，无遮蔽；`wiki-graph` 已加入 `AppRoute` 联合类型。
- `App.tsx`：图谱为 `lazy` 独立 chunk；`route.page === "wiki-graph"` 时隐藏工作台 tabs 与 Wiki 编辑器，仅渲染图谱，避免双重渲染；`onBack`/`onOpenPage` 均走 `navigate`，关闭预览后跳转。
- 项目隔离：`graphFor` 以 `projectId` 过滤，测试断言 `project-2` 页面“外部”不出现；满足“项目切换后只使用新项目页面”。
- 边语义：按 `target.id` 去重、聚合显示词；自链接排除；失效目标不生成关系；无链接页面仍可浏览。
- 安全：无 `dangerouslySetInnerHTML`，无新增网络/写入路径，浏览/缩放/拖动仅本地 state。
- 测试：`parseRoute("/wiki/graph")`、链接来源、跨项目排除、打开页面回调均有断言；`npm`“home 配置警告”为既有噪声，非本次回归。
