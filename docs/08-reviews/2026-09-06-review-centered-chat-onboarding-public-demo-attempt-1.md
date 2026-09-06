# Pi 代码审查报告：centered-chat-onboarding-public-demo / Attempt 1

- 日期：2026-09-06
- 审查阶段：centered-chat-onboarding-public-demo
- 审查对象：INDEX@f6e9d51（基线：f6e9d5182bc571372b99966a4d4cddc606771ebe）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立只读代码审查报告

- 审查阶段：`centered-chat-onboarding-public-demo`
- 审查模式：Diff Review（第 1/3 轮）
- 目标：`f6e9d5182bc571372b99966a4d4cddc606771ebe`
- 审查依据：本次 git diff、23 个改动文件、变更记录与 ADR（ADR-0014/0015）

## 概述与总体结论

本次切片目标明确：把登录页和工作区改为居中单栏、Chat 置顶、增加首次新手引导，并把面试 Demo 账号定义为**公开、无秘密属性的受限 USER 凭据**（ADR-0015），同时新增 V2/V3 节点开发治理文档。实现与文档、测试（TDD Red/Green 证据）在本次 diff 范围内相互一致，未发现可证实的可运行性、权限绕过、数据一致性或 API 契约缺陷。

核心安全取舍（公开密码进入源码/浏览器）已由 ADR-0015 显式记录，并有配套缓解（USER-only、project 隔离、日配额、Nginx IP 限速、HITL、可重置、敏感数据隔离），属于用户明确授权的产品决策，不构成需阻塞的秘密泄漏。**总体结论：可通过，建议在交付前采纳下述可操作性改进。**

## 详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| S1 | 建议修改 | Medium | apps/web/src/App.tsx | 33、100 附近 | `localStorage` 无 try/catch，存储被禁用时整个渲染抛异常 |
| S2 | 建议修改 | Medium | apps/web/src/App.tsx；scripts/validation/v2-prep-demo-experience.ps1 | App.tsx: 5-6；ps1: 1-30 | 前端凭据常量未纳入契约校验，前端与服务端漂移会静默破坏 Demo 登录 |
| S3 | 建议修改 | Low | apps/web/src/App.tsx | 173-176 附近 | 引导弹窗缺焦捕获、ESC/遮罩关闭与焦点还原 |
| S4 | 建议修改 | Low | .env.production.example；App.tsx；脚本 | 多处 | 个人真实邮箱进入公开仓库与浏览器，存在垃圾邮件/钓鱼暴露 |
| N1 | 无需修改 | — | App.tsx / .env / ADR-0015 | — | 公开凭据进入源码是文档化决策，非秘密泄漏 |
| N2 | 无需修改 | — | App.tsx / styles-v12.css | 173 | `centered-workspace` 类名无对应 CSS，仅用于测试断言，无害 |

---

## 逐个 Issue 展开

### S1（建议修改）— localStorage 未做异常保护，存储禁用时渲染崩溃

- **Severity**：Medium（可运行性/健壮性）
- **File & Line**：`apps/web/src/App.tsx`
  - `const [onboardingOpen, setOnboardingOpen] = useState(() => localStorage.getItem(ONBOARDING_KEY) !== "true");`
  - `completeOnboarding` 内 `localStorage.setItem(ONBOARDING_KEY, "true");`
- **Evidence**：
  ```tsx
  const [onboardingOpen, setOnboardingOpen] = useState(() => localStorage.getItem(ONBOARDING_KEY) !== "true");
  ```
- **Description**：`onboardingOpen` 的初始化器在组件首次挂载时直接读 `localStorage`，`completeOnboarding` 直接写。若浏览器隐私模式/策略禁用本地存储导致 `getItem` 抛 `SecurityError`，该异常发生在 React 渲染期，会使 **登录页（首屏）整体白屏**，而不仅是引导失效。这是本次新增的运行时路径脆弱点。
- **Suggested Fix**：
  ```tsx
  function readOnboardingDone(): boolean {
    try { return localStorage.getItem(ONBOARDING_KEY) === "true"; }
    catch { return false; }
  }
  const [onboardingOpen, setOnboardingOpen] = useState(() => !readOnboardingDone());

  function completeOnboarding() {
    try { localStorage.setItem(ONBOARDING_KEY, "true"); } catch { /* 内存态已关闭即可 */ }
    setOnboardingOpen(false);
  }
  ```

---

### S2（建议修改）— 前端与服务端 Demo 凭据缺乏单一契约校验，存在漂移风险

- **Severity**：Medium（配置契约一致性，漂移即“演示登录不可用”）
- **File & Line**：
  - `apps/web/src/App.tsx:5-6`（`PUBLIC_DEMO_EMAIL` / `PUBLIC_DEMO_PASSWORD`）
  - `scripts/validation/v2-prep-demo-experience.ps1:1-30`
- **Evidence**：
  ```powershell
  # v2-prep-demo-experience.ps1 只检查：
  # .env.production.example / generate-production-env.sh / validate-env.sh
  foreach ($entry in @("AGENTFORGE_DEMO_FIXED_EMAIL=$expectedEmail", ...)) { ... }
  ```
  前端常量：
  ```tsx
  const PUBLIC_DEMO_EMAIL = "210168y@gmail.com";
  const PUBLIC_DEMO_PASSWORD = "[REDACTED_PUBLIC_DEMO_PASSWORD]";
  ```
- **Description**：同一组公开凭据在 `App.tsx`、`.env.production.example`、`generate-production-env.sh`、`validate-env.sh` 四处硬编码。新增的契约校验脚本 `v2-prep-demo-experience.ps1` 覆盖了三个部署文件，却**未校验 `apps/web/src/App.tsx`**。一旦前端展示值与服务端 seed 值漂移（改密码/换邮箱/改文案时漏改任意一处），登录页展示的凭据将无法登录，恰好破坏本切片要解决的核心体验。当前四处值一致，不构成现有缺陷，故不阻塞。
- **Suggested Fix**：把前端常量也纳入同一静态契约（在 ps1 中增加对 `apps/web/src/App.tsx` 内容的断言），或抽取共享常量源（如 `apps/web/src/demo-credentials.ts` + 脚本生成同源），并在 CI 中重建该一致性检查。

---

### S3（建议修改）— 引导弹窗可访问性不完整

- **Severity**：Low（体验/可访问性）
- **File & Line**：`apps/web/src/App.tsx:173-176`
- **Evidence**：
  ```tsx
  {onboardingOpen && <div className="onboarding-backdrop"><section className="onboarding-dialog" role="dialog" aria-modal="true" ...>...</section></div>}
  ```
- **Description**：弹窗声明了 `role="dialog"` 与 `aria-modal="true"`，但没有初始焦点管理、焦点圈定（focus trap）、ESC/点击遮罩关闭，关闭后也未把焦点还原到“新手引导”按钮。对键盘用户不够友好；不阻塞功能。
- **Suggested Fix**：打开时聚焦“开始体验”按钮，弹窗内循环焦点，支持 `Escape` 与遮罩点击关闭，关闭后 `guideButtonRef.current?.focus()`。

---

### S4（建议修改）— 个人真实邮箱作为公开 Demo 账号进入公开仓库

- **Severity**：Low（隐私暴露）
- **File & Line**：`.env.production.example:7`、`apps/web/src/App.tsx:5`、`generate-production-env.sh:31`、`validate-env.sh:67` 等
- **Evidence**：`AGENTFORGE_DEMO_FIXED_EMAIL=210168y@gmail.com`（四份文件中公开出现）
- **Description**：该邮箱随源码公开并下发到每位访问者的浏览器，属于用户明确指定的公开凭据（ADR-0015）。公开邮箱可能招致垃圾邮件/定向钓鱼；若这是账号所有者个人常用邮箱，建议未来更换为专用于演示的一次性或别名邮箱。属产品取舍，不阻塞本次交付。
- **Suggested Fix**：后续 Node 可评估改用非个人 `demo@agentforge.local` 类邮箱并同步四份常量；若坚持保留，建议在该邮箱侧启用独立过滤/别名。

---

### N1（无需修改）— 公开凭据进入源码是文档化决策

- **Description**：`ADR-0015` 已明确“该凭据会出现在浏览器和公开仓库，因此不能再按秘密处理”，并配套 USER 权限、project 隔离、日配额、IP 限速、HITL、可重置与敏感数据隔离。`validate-env.sh` 对“仅此组公开凭据放行 8 字符、其他固定密码仍 12–72 字符”的实现与决策一致；`generate-production-env.sh` 仍随机生成 DB/JWT/内部 token 与模型 key 占位。无需修改。

### N2（无需修改）— `centered-workspace` 类名无对应 CSS

- **Description**：`<main className="workspace centered-workspace">` 中 `centered-workspace` 在 `styles-v12.css` 未定义，居中效果实际由直接改写 `.workspace`（`width: min(100%,980px); justify-self: center;`）实现。该类名仅被测试 `toHaveClass` 用作语义断言，无功能影响。无需修改。

---

## 主开发 (Codex) 评估回填区

| Issue ID | 判断（认可/不认可） | 理由 | 处理动作 | 是否需补测 | 状态 |
| --- | --- | --- | --- | --- | --- |
| S1 | 认可 | 禁用存储时渲染期异常可导致白屏 | 已补 Red 测试；读取异常回退为显示引导，写入异常仍关闭当前内存态引导 | 是，12/12 目标测试通过 | 已修复 |
| S2 | 认可 | 当前一致，但四处硬编码存在未来漂移风险 | 契约脚本已显式核对 `App.tsx` 的 email/password 常量 | 是，契约检查通过 | 已修复 |
| S3 | 认可但本次不改 | 属低风险可访问性增强，当前语义和明确关闭按钮已满足本次引导范围 | 记录为后续候选，不扩大本切片 | 否 | 已记录 |
| S4 | 认可风险、维持决策 | 用户明确指定公开邮箱，ADR-0015 已记录垃圾邮件/钓鱼取舍 | 不修改指定值，不得复用于敏感环境 | 否 | 已记录 |
| N1 | 认可 | 与 ADR-0015 和受限 USER 边界一致 | 无 | 否 | 已关闭 |
| N2 | 认可 | 类名是 DOM 语义标记，居中由 `.workspace` 实现 | 无 | 否 | 已关闭 |

> 说明：本次审查为纯只读静态分析，未运行任何命令；测试证据以变更记录中的 Codex 机器结果为参考，不视为 Pi 自行执行结果。

## Review 修复验证

- S1 TDD Red：`npm test -- --run tests/app.test.tsx` 退出码 1；12 tests 中 1 failed、11 passed，`SecurityError: Storage disabled` 从 `App.tsx` 渲染初始化器抛出。
- S1 Green：同一命令退出码 0；12/12 passed，包含读写 `localStorage` 都抛 `SecurityError` 时引导仍可显示和关闭。
- S2：`scripts/validation/v2-prep-demo-experience.ps1` 已增加前端常量校验，执行退出码 0。
- 最终回归：Web 全量 3 files、16/16 tests passed，0 failed/skipped；production build 通过（Vite 7.3.6，283 modules）；公开 Demo 契约检查通过，三项退出码均为 0。
- Pi Attempt 1 已为 PASS；修复仅落实其两项 Medium 建议，未改变 API、安全边界或架构，因此不重复消耗第二轮审核。
