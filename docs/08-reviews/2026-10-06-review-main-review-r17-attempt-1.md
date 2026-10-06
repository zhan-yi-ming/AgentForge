# Pi 代码审查报告：main-review-r17 / Attempt 1

- 日期：2026-10-06
- 审查阶段：main-review-r17
- 审查对象：INDEX@2a6379d（基线：2a6379d9959982cad67f91c3a554b5dab6e1e3b7）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒
- 处置状态：RESOLVED（Codex 已复现并修复阻断项，进入 Attempt 2）

---

REVIEW_RESULT: NEEDS_FIX

# R17 Web 异步响应作用域隔离 · Diff Review 报告

## 一、概述与总体结论

- 审查阶段：main-review-r17 / Diff / 第 1 轮
- 审查范围：`apps/web/src/App.tsx`、`apps/web/tests/app.test.tsx`、`docs/03-features/web-workspace.md`、`docs/07-changes/2026-10-04-r17-web-async-scope.md`、`docs/07-changes/README.md`
- 总体结论：**需修复后交付（NEEDS_FIX）**
- 核心判断：workspace generation + token + projectId 的作用域设计方向正确，A→B→A、注销重登录、旧 Action 收口等主要污染路径已被覆盖并有延迟 Promise 测试。但本次把 `sendChat` 的 `finally` 也纳入 scope 守卫，导致**项目切换取消 Chat 流后新项目的 `streaming` 永久为 true**，发送按钮被禁用，属于明确的功能回归，必须修复。另有一处 `deleteBusy` 复位遗漏（可达性依赖弹层是否阻断操作，列为建议）。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| R17-01 | 必须修改 / High | apps/web/src/App.tsx | ≈L619-L620、≈L662-L666；≈L254-L285 | 项目切换取消 Chat 流后 `setStreaming(false)` 被 scope 守卫跳过，新项目 `streaming` 常真，发送按钮永久禁用 |
| R17-02 | 建议修改 / Low | apps/web/src/App.tsx | ≈L254-L285、≈L520-L545 | `deleteBusy` 的 finally 被 scope 守卫，项目切换后未复位，可能残留禁用删除按钮 |
| R17-03 | 建议修改 / Low | apps/web/tests/app.test.tsx | 新增用例组 | 缺少“项目切换期间 Chat 流进行中”的回归用例，未能捕获 R17-01 |

---

## 三、Issue 逐个展开

### R17-01（必须修改 / High）项目切换后 Chat `streaming` 未复位，发送按钮永久禁用

- Severity：High（功能阻断/回归）
- File & Line：`apps/web/src/App.tsx` sendChat 的 `finally`（≈L662-L666）与项目切换 effect（≈L254-L285）

**Evidence（本次 diff）**

```tsx
// sendChat
const isCurrentChat = () => streamAbort.current === controller &&
  !controller.signal.aborted && isWorkspaceScopeCurrent(scope);
...
} finally {
  if (streamAbort.current === controller && isWorkspaceScopeCurrent(scope)) {
    streamAbort.current = undefined;
    setStreaming(false);   // ← 项目切换后 scope 已失效，不会执行
  }
}
```

```tsx
// projectId effect（切换项目时）
streamAbort.current?.abort();
formatAbort.current?.abort();
let active = true;
setBusy(false);            // 只复位 busy
setError("");
...
// 没有 setStreaming(false)
```

对照被删除的旧实现：

```tsx
} finally {
  if (streamAbort.current === controller) {   // 旧代码只校验 controller identity
    streamAbort.current = undefined;
    setStreaming(false);
  }
}
```

**Description**

1. `chooseProject` 同步调用 `advanceWorkspaceScope(...)`，generation 立即递增；随后 effect 执行 `streamAbort.current?.abort()`。
2. 被中止的 `chatStream` 走 `finally`：`streamAbort.current === controller` 成立，但 `isWorkspaceScopeCurrent(scope)` 因 generation 已变化而为 false，因此 **`setStreaming(false)` 被跳过**。
3. effect 只调用 `setBusy(false)`，**没有任何地方在项目切换时复位 `streaming`**（`resetWorkspaceState` 只覆盖注销/401 路径）。
4. 结果：切到项目 B 后 `streaming === true`，`renderChatComposer` 的发送按钮 `disabled={busy || streaming || !chatMessage.trim()}` 恒为禁用并显示“生成中…”，用户只能先点“新建会话”（`newChat` 里有 `setStreaming(false)`）才能继续对话，属明显回归。
5. 该问题不会被现有新增测试发现（新增测试只覆盖格式化流的 A→B→A，未覆盖 Chat 流在项目切换中的 `streaming` 复位），也未在 `docs/03-features/web-workspace.md` 的测试边界中声明，属测试缺口。

**Suggested Fix**

最小修复是让 `streaming` 的复位只依赖 controller identity（它已经能保证“只有最新流能复位自己”），把 scope 校验留给其它状态写入：

```tsx
} finally {
  if (streamAbort.current === controller) {
    streamAbort.current = undefined;
    setStreaming(false);
  }
}
```

或等价地在项目切换 effect 中显式复位（两者择一即可，建议同时加断言测试）：

```tsx
streamAbort.current?.abort();
streamAbort.current = undefined;
formatAbort.current?.abort();
setStreaming(false);
```

---

### R17-02（建议修改 / Low）项目切换后 `deleteBusy` 可能残留

- Severity：Low（复位一致性；可达性取决于删除弹层是否阻断切换项目操作）
- File & Line：`apps/web/src/App.tsx` `deleteSelectedConversation` 的 `finally`（≈L520-L545）与项目切换 effect（≈L254-L285）

**Evidence**

```tsx
} finally {
  if (isWorkspaceScopeCurrent(scope)) setDeleteBusy(false);  // scope 失效则不复位
}
```

项目切换 effect 中仅有 `setConversationToDelete(undefined)`，没有 `setDeleteBusy(false)`。

**Description**

若删除请求在项目切换/代际推进后返回，`isWorkspaceScopeCurrent(scope)` 为 false，`deleteBusy` 保持 `true`；项目切换 effect 与 `chooseProject` 均未复位。之后历史抽屉的删除按钮 `disabled={deleteBusy}`、`deleteSelectedConversation` 的入口守卫 `if (... || deleteBusy) return;` 都会持续禁用删除，直到注销/刷新。相比旧实现（无条件 `setDeleteBusy(false)`）属退化。删除弹层通常会阻断点击顶栏/项目抽屉，因此实际可达性有限，故列为建议而非阻塞。

**Suggested Fix**

在项目切换 effect（或 `chooseProject`）中补 `setDeleteBusy(false);`；或保留 finally 的无条件 `setDeleteBusy(false)`——写入由 `selectedId` 与 scope 守卫保护的列表本身已足够安全，busy 标志复位不需要 scope 约束。

---

### R17-03（建议修改 / Low）缺少“项目切换期间 Chat 流进行中”的回归测试

- Severity：Low（测试覆盖缺口，直接对应 R17-01）
- File & Line：`apps/web/tests/app.test.tsx`（本次新增的三个作用域用例旁边）

**Evidence**

新增用例覆盖了：Wiki 保存 A→B、注销重登录、格式化 A→B→A、旧 Action 不清除新 pending；但没有“Chat 流未完成时切换项目，随后新项目发送按钮可用”的用例。

**Description**

Chat 流在项目切换时的取消路径与格式化流不同（`isCurrentChat` 同时含 `!controller.signal.aborted`，而 `finally` 只含 identity + scope），新增用例未覆盖，导致 R17-01 未被红灯暴露；文档测试边界写有“项目切换取消旧流”，但只验证了 abort 与历史清空，未验证 `streaming` 状态复位。

**Suggested Fix**

增加用例：让 `chatStream` 返回挂起 Promise，发送后切换到 Second Project，断言 `screen.getByRole("button", { name: "发送" })` 恢复可用（或“生成中…”消失），并断言旧 stream 的 resolve/reject 不写回历史。

---

## 四、无需修改（确认项）

| ID | 项目 | 结论 |
|----|------|------|
| N-01 | `workspaceGeneration` + token + projectId 的 scope 组成与 `advanceWorkspaceScope` 同步推进 | 正确的方向；A→B→A 与注销重登录均能建立新代际，测试覆盖到位 |
| N-02 | 旧 Action 收口自身（`stillOwnsPendingAction` + `replacingActionId`） | 能防止旧决策清除新项目 pending action，测试有效 |
| N-03 | 幂等 key 在请求已发出后不再重放、迟到结果仅丢弃前端写入 | 符合“后端已成功不自动重放”的契约，无数据一致性风险 |
| N-04 | `saveWiki` 的 `wikiSaveSequence` + scope 双重守卫与并发保护 | 与项目切换/重复保存场景一致，finally 不回写 current 视图正确 |

---

## 五、主开发 (Codex) 评估回填区

| Issue ID | 是否认可 | 处理方式（文档先行/修复） | 目标提交/文档 | 备注 |
|----------|----------|---------------------------|---------------|------|
| R17-01 | 是 | 先补项目切换中 Chat stream 的 DOM 红灯，再在新 generation 初始化时中止并释放旧 controller、复位 `streaming` | R17 提交；`web-workspace.md` | 旧实现实际保持“生成中…”且发送按钮禁用；修复后定向与全量通过 |
| R17-02 | 是 | 该 finding 与原 R17 的 stale busy/finally 约束直接相关；补删除中切换项目的 DOM 红灯，并在新 generation 初始化时复位 `deleteBusy` | R17 提交；`web-workspace.md` | 旧实现实际禁用新项目删除按钮；修复后通过 |
| R17-03 | 是 | 已增加 Chat 取消后新项目控件恢复用例，并同时覆盖历史删除瞬态状态 | `apps/web/tests/app.test.tsx` | 两例先 `2 failed`，最小实现后 `2 passed` |

---

## 六、结论

- 存在 1 项“必须修改”（R17-01），触发 **NEEDS_FIX**。
- 修复建议为最小改动：`sendChat` 的 `finally` 恢复仅按 `streamAbort.current === controller` 复位 `streaming`/`streamAbort`，或项目切换 effect 显式 `setStreaming(false)`；同时补一条项目切换期间 Chat 流的回归用例。
- R17-02、R17-03 为非阻塞建议，可与 R17-01 一并收口，不单独触发修复轮次。
