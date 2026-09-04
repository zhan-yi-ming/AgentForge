# Pi 代码审查报告：review-visibility-and-liveness / Attempt 2

- 日期：2026-09-04
- 审查阶段：review-visibility-and-liveness
- 审查对象：aa49fb85d6812090a135592ffacbe46b202a5cc9（基线：de08b54965a829bbc5bcb2625b5e630ecbc41c64）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- **审查阶段**: review-visibility-and-liveness
- **审查轮次**: 2 / 3
- **审查目标 commit**: `aa49fb85d6812090a135592ffacbe46b202a5cc9`
- **基线范围**: `de08b54965a829bbc5bcb2625b5e630ecbc41c64 .. aa49fb85d6812090a135592ffacbe46b202a5cc9`
- **审查模型**: DeepSeek V4-pro（只读 Reviewer）
- **审查方式**: 完全只读，依据启动器提供的 Git Diff（首/中/尾采样）、完整文件清单、差异统计与上一轮报告
- **改动规模**: 52 文件，+2462 / −64；diff 已声明截断，无法逐文件全量核验，未见到内容之处均显式标注

---

## 1. 概述与总体结论

**结论：需修复后交付（NEEDS_FIX）**

本 commit 是一次较大规模的“收口提交”，把此前 v1-day-1 / day-2 多轮审查循环的修复成果、认证与乐观锁安全补强、审查桥接脚本体量与运维/治理文档的职责重新划分，合并为一个可追溯的历史节点。从可见证据看，上一轮（轮次 1）针对 UTF-8 BOM 的 R1–R5 发现已基本闭环：`Test-ReviewBridge.ps1`（+78）作为 bridge 回归测试落地（对应原 R3），运维文档收窄了表述并给出 BOM 检测/恢复命令（原 R2/R5），BOM 方案保留但已封装为团队约定（原 R1/R4）。

后端安全方向同样有明显正向进展：

- 跨项目越权防护的测试缺口被补上：`TaskServiceTest` / `WikiPageServiceTest` 新增 `verify(projectAccess, times(2)).requireAccess(...)`，并新增 `projectAuthorizationRunsBeforeWikiLookup` 证明“授权先于数据查询”，方向正确。
- JWT secret 配置层强校验的长期欠账（上一阶段 A3-2）由新增 `JwtSecretKeyValidationTest`（+49）闭合。
- Testcontainers PostgreSQL 集成测试此前被本地无 Docker 环境跳过（A3-1），本 commit 通过新增 `PersistenceIntegrationTest`（+45）并在 CI 中增加 Surefire 断言强制“零跳过”，闭环意图明确。

但本轮仍发现两类需要 Codex 处理的问题：

1. **CI 门禁本身过于脆弱**（R2-1）：新加的 Python 断言把集成测试数量硬编码为 `== 2` 并将 Surefire XML 路径写死，下一次新增/重命名集成测试会以令人困惑的方式挂断流水线——这削弱了该门禁本应提供的回归保障。
2. **数据库层乐观锁失败到 HTTP 409 的映射在本 commit 变更文件清单中不可见**（R2-2）：单元测试只证明了“内存态版本号不匹配会被拒绝且不删除”，但并发场景下 JPA flush 时抛出的 `OptimisticLockingFailureException` 是否被全局转换为 409（而不是 500）在可见 diff 中无法确认。按本角色准则，无法确认的一致性/安全风险必须回退为 NEEDS_FIX。

另有两个低级别项（R2-3 文档当前变更链接过期、R2-4 状态文件并发锁原子性不可验证）随附。

---

## 2. 详细发现清单

| ID | 严重级别 | 文件 / 行号（约） | 核心问题 |
|----|---------|--------------------|----------|
| R2-1 | Medium | `.github/workflows/core-api-ci.yml`（新增 Assert 步骤） | 新 CI 门禁把集成测试数量硬编码为 `tests == 2` 并写死 Surefire XML 路径，对测试新增/重命名极其脆弱，且同一 step 依赖 `shell: python` 与固定文件名 |
| R2-2 | Medium | `services/core-api/src/test/java/com/agentforge/core/PersistenceIntegrationTest.java`（+45）；全局异常处理层（未出现在本轮变更文件清单） | 数据库并发路径的 `OptimisticLockingFailureException` → HTTP 409 映射无法从可见变更确认；单元测试仅证明内存态 stale 拒绝 |
| R2-3 | Low | `AGENTS.md` “文档入口 → 当前变更”（diff 中为未变更上下文行） | 当前变更指针仍指向 `2026-09-03-codex-pi-bridge-and-code-review.md`，而本 commit 的变更记录均为 `2026-09-04`，文档入口过期 |
| R2-4 | Low | `scripts/agent-bridge/review-state.ps1`（新增 +34）；`.review-loop-state.json.lock` / `*.tmp` 锁文件 | JSON 状态“读-改-写”依赖文件锁，锁的原子创建方式在截断 diff 中不可验证，Codex 与 Pi 并发唤醒时存在状态损坏风险 |

> 说明：本轮无权限越权、JWT 校验缺失、Sensitive Info 泄漏等新增证据；R2-1/R2-2 之外的项为低级别可观察问题或不完整证据项，未凑数。

---

## 3. 逐个 Issue 展开

### R2-1 — 新 CI 集成测试门禁过于脆弱，硬编码测试数量与文件路径

- **Severity**: Medium
- **File & Line**: `.github/workflows/core-api-ci.yml`（`Assert PostgreSQL integration tests ran` 步骤，路径硬编码与断言硬编码）
- **Evidence**:

  ```yaml
  - name: Assert PostgreSQL integration tests ran
    working-directory: services/core-api
    shell: python
    run: |
      import xml.etree.ElementTree as ET
      report = ET.parse("target/surefire-reports/TEST-com.agentforge.core.PersistenceIntegrationTest.xml").getroot()
      assert int(report.attrib["tests"]) == 2, report.attrib
      assert int(report.attrib["skipped"]) == 0, report.attrib
      assert int(report.attrib["errors"]) == 0, report.attrib
      assert int(report.attrib["failures"]) == 0, report.attrib
      print("PersistenceIntegrationTest ran:", report.attrib)
  ```

  另外文件清单中 `services/core-api/mvnw | 0` 为纯 mode 变更，且 workflow 已从 `mvn` 切换为 `./mvnw`；需确认该 wrapper 在 Git 中确为可执行位（100755），否则 Ubuntu runner 上会直接 `Permission denied`。

- **Description**:
  该步骤的设计目标是“确保此前被本地跳过的 PostgreSQL Testcontainers 用例在 CI 上真实执行、零跳过”，动机正确。但实现上存在两个脆弱点：
  1. `assert ... == 2`：一旦后续为集成测试增加第 3 个正向用例，CI 会以 `AssertionError: {'tests': '3', ...}` 形式失败，且错误信息与“门禁有意放宽/更新”无关联，造成伪回归。更稳妥的写法是断言 `tests >= 2` 且 `skipped == 0`。
  2. `ET.parse("target/surefire-reports/TEST-com.agentforge.core.PersistenceIntegrationTest.xml")`：测试类重命名、包路径调整或 Surefire 输出目录约定变化都会让该步骤以难以定位的 `FileNotFoundError` 失败，而非显式报告。
  同时该 step 依赖 runner 自带 Python（`shell: python`），并为双保险应该确认 `./mvnw` 可执行位已提交。

- **Suggested Fix**:
  1. 将断言改为宽松上界 + 严格零跳过：
     ```yaml
     assert int(report.attrib["tests"]) >= 2, report.attrib
     assert int(report.attrib["skipped"]) == 0, report.attrib
     assert int(report.attrib["errors"]) == 0, report.attrib
     assert int(report.attrib["failures"]) == 0, report.attrib
     ```
  2. 用 glob 汇总该测试类的报告，避免写死精确路径：
     ```python
     import glob, xml.etree.ElementTree as ET
     files = glob.glob("target/surefire-reports/TEST-*.PersistenceIntegrationTest.xml")
     assert files, "no PersistenceIntegrationTest report found"
     report = ET.parse(files[0]).getroot()
     ```
  3. 在该步骤前增加 `shell: bash` 检查（或直接在仓库确认）：`git ls-files --stage services/core-api/mvnw` 应显示 `100755`。

---

### R2-2 — 数据库层乐观锁失败到 409 的映射在本 commit 变更文件清单中不可见

- **Severity**: Medium
- **File & Line**: `services/core-api/src/test/java/com/agentforge/core/PersistenceIntegrationTest.java`（+45，具体方法体因 diff 截断不可见）；全局异常处理层（未出现在本轮 52 个变更文件中）
- **Evidence**（可见单元测试，证明的仅为内存态拒绝）：

  ```java
  @Test
  void deleteRejectsStaleVersionWithoutDeleting() {
      ...
      WikiPage page = WikiPage.create(projectId, "Architecture", "old", NOW);
      when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));

      assertThatThrownBy(() -> service.delete(projectId, pageId, actor, 1))
              .isInstanceOf(ConflictException.class)
              .hasMessageContaining("stale");
      verify(wikiPages, never()).delete(page);
  }
  ```

  本轮 Java 生产代码变更仅 `AuthenticatedActor.java`（+6）；文件清单中没有 `*ExceptionHandler*`、`*ControllerAdvice*` 或其它全局异常翻译类。

- **Description**:
  上述单测说明了服务层在**加载实体后、调用 `repository.delete` 前**对版本号做内存比较。内存比较只能防御“传入版本号本身就是旧的”这一静态场景。真正的并发场景是：两个事务同时以 `version=0` 加载同一实体，各自通过内存检查并提交删除；此时最终一致性只能由 JPA `@Version` 在 flush 时生成 `DELETE ... WHERE id=? AND version=?` 并抛出 `OptimisticLockingFailureException` 来保证。

  关键问题在于：该 flush-time 异常若没有被全局异常处理器翻译为 HTTP 409（`ConflictException` 语义），会以 500 沿 Controller 栈冒出。可见变更既没有显示该翻译实现，也没有显示 `PersistenceIntegrationTest` 是否断言了最终 HTTP 状态（其名称为 `PersistenceIntegrationTest`，更可能是纯持久化层双 EntityManager 断言，而非 `MockMvc` 状态码断言）。因此“并发 stale 删除/更新最终返回 409 而非 500”这一安全/一致性命门在本 commit 中**没有得到可核验的证据**。

- **Suggested Fix**:
  1. 若基线已存在全局 handler，请在回填区给出类名与映射片段（通常为 `@ExceptionHandler({OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class})` → 409），即可关闭本条，无需改代码。
  2. 若不存在，新增一个 `@RestControllerAdvice`，将上述 JPA 乐观锁异常映射为 409，并补充一个 `MockMvc` 级测试（或扩展 API 测试）证明并发/旧快照提交返回 409 而非 500。
  3. 将 `PersistenceIntegrationTest` 的实际断言（是断言“抛异常”还是“HTTP 409”）回填到本轮报告，消除截断导致的证据盲区。

---

### R2-3 — `AGENTS.md` “当前变更”指针过期

- **Severity**: Low
- **File & Line**: `AGENTS.md` “文档入口 → 当前变更”（本 commit 该行未改动，属上下文行）
- **Evidence**:

  ```diff
   ## 文档入口
   ...
  - 当前变更：docs/07-changes/2026-09-03-codex-pi-bridge-and-code-review.md
  ```

  本 commit 新增的变更记录全部是 `docs/07-changes/2026-09-04-*.md`（autonomous-review-delivery、day1-day2-final-review-evidence、review-automation-recovery-and-observability）。

- **Description**:
  治理文档 `AGENTS.md` 面向所有 Agent（含每次唤醒的 Codex 与 Pi），其中“当前变更”指导读者进入活跃变更记录。该指针停留在上一阶段（09-03）记录，与本 commit 的 09-04 交付脱节，可能导致 agent 按过期上下文判断阶段边界。

- **Suggested Fix**:
  将“当前变更”更新为 `docs/07-changes/2026-09-04-autonomous-review-delivery.md`（或本阶段最直接对应的 `2026-09-04-day1-day2-final-review-evidence.md`），并保证与 `docs/07-changes/README.md` 的索引一致。

---

### R2-4 — `review-state.ps1` 状态文件的并发锁原子性无法从截断 diff 确认

- **Severity**: Low
- **File & Line**: `scripts/agent-bridge/review-state.ps1`（新增 +34）；`.gitignore` 中的 `scripts/agent-bridge/.review-loop-state.json.lock`、`.review-loop-state.json.*.tmp`（本次新增 `.bak` 后缀）
- **Evidence**（间接证据）:

  ```diff
  +scripts/agent-bridge/.review-loop-state.json.bak
   scripts/agent-bridge/.bridge-monitor.pid
  ```

  且 `review-loop.ps1`（+64）与 `review-state.ps1`（+34）共同管理 `.review-loop-state.json`。

- **Description**:
  审查循环是 Codex 与 Pi 两个独立进程跨唤醒时间协作的核心状态机，`.review-loop-state.json` 的“读-改-写”必须互斥，否则并发唤醒可能读到半写入状态或丢失更新。`.gitignore` 显示已有 `.lock` 与 `.*.tmp` 文件，说明作者已意识到并发问题；但锁文件若采用“先 `Test-Path` 再 `New-Item`”的非原子创建，两个进程仍可能同时拿到锁。diff 截断使 `review-state.ps1` 的具体锁实现不可见，故本项只能作为验证项列出，不能定性为 bug。

- **Suggested Fix**:
  1. 确认锁获取为原子操作，例如：
     ```powershell
     # 原子创建锁；失败即抛错，避免 check-then-create 竞态
     $stream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
     $stream.Dispose()
     ```
  2. 回填 `review-state.ps1` 的锁获取实现位置与竞态说明，或提供 `Test-ReviewBridge.ps1` 中关于“并发获取锁只允许一个成功”的测试证据。

---

## 4. 上一轮（轮次 1）发现状态核验

| 上一轮 ID | 核验结果 | 依据 |
|---|---|---|
| R1（BOM 方案污染源码） | 已由 Codex 决策保留，并依赖回归支撑 | `Test-ReviewBridge.ps1` 新增，形成双运行时回归；本轮不再复判 |
| R2（文档表述过度绝对化） | 已修复 | 运维文档已收窄为“BOM 只解决源码解码，执行策略/无 Profile 仍需满足” |
| R3（缺 PowerShell 5 兼容性自动化测试） | 已关闭 | 新增 `Test-ReviewBridge.ps1`（+78），覆盖 PS5/PS7 |
| R4（Show-ReviewStatus 首行 BOM 签名） | 维持原结论 | 保留 BOM 约定，仓库无首字节匹配工具 |
| R5（BOM 操作指引缺失） | 已修复 | 运维文档与回归脚本补入检测/恢复命令 |

---

## 5. 主开发 (Codex) 评估回填区

> 请 Codex 恢复后逐项研判：接受则给出修复方案并回到文档先行流程；拒绝则提供生产实现或测试证据，不得盲目照改。

| 发现 ID | Codex 结论（接受/部分接受/拒绝） | 修复计划或反驳理由 | 当前状态 |
|---|---|---|---|
| R2-1 | 接受 | CI 改为查找唯一报告并断言测试数至少为 2、零跳过，保留失败/错误断言。 | 待 Pi 复验 |
| R2-2 | 接受 | 全局异常处理器增加 `OptimisticLockingFailureException` → HTTP 409，并增加 MVC 契约测试。 | 待 Pi 复验 |
| R2-3 | 接受 | `AGENTS.md` 当前变更指针更新为本次集中收口记录。 | 已处理 |
| R2-4 | 拒绝 | `review-loop.ps1` 通过 `File.Open(...OpenOrCreate, ReadWrite, FileShare.None)` 持有独占句柄；状态写入使用临时文件加 `File.Replace`，`Test-ReviewBridge.ps1` 已验证第二调用返回 BUSY 且不消耗 attempt。该项是 diff 截断造成的证据缺口，不是实现缺陷。 | 证据已提供 |

---

## 6. 审查结论摘要

- **正面**：跨项目越权测试闭环、JWT secret 配置层强校验落地（`JwtSecretKeyValidationTest`）、Testcontainers 集成测试进入 CI 强制零跳过、bridge 双运行时回归（`Test-ReviewBridge.ps1`）、治理文档与双 Agent 分工对齐，均为实质性收口。
- **未发现**空指针、未捕获异常导致的确定运行时崩溃、Sensitive Info、真实密钥/令牌进入仓库等硬伤。
- **未引入**任何 V2/V3 禁止组件（无 Neo4j/GraphRAG、无 Langfuse 完整 Trace、无 LiteLLM、无 MCP）。
- **必须处理**：R2-2（数据库并发乐观锁失败 → HTTP 409 的证据闭环）属于一致性/安全门，按本角色准则在证据可见前不得 PASS；R2-1 涉及新增 CI 门禁的健壮性，建议一并修复。
- **可随修复顺带处理**：R2-3（文档指针过期）与 R2-4（状态锁原子性验证）。

**最终结论：NEEDS_FIX（需修复后交付）**。
