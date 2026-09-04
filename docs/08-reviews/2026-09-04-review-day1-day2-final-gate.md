## 门禁审查结果

我已按门禁要求对工作树相对 HEAD 的差异做了只读审查，执行了指定的只读命令，并额外用 `git diff HEAD`/`git diff --check HEAD` 核验了已暂存的全部变更（注意：本次所有改动都处于 **已暂存** 状态，因此字面的 `git diff ...` 系列命令输出为空，必须用 `HEAD` 变体才能看到实际差异）。

**关键发现：CI 的 `./mvnw` 不可执行（`100644`），Testcontainers 断言步骤无法运行** —— 这是阻断项。

### 执行的命令与退出码

| # | 命令 | 退出码 | 结果 |
|---|------|--------|------|
| 1 | `git diff --check` | 0 | 无输出（改动已全部暂存，字面命令仅检查未暂存区，为空） |
| 2 | `git status --short` | 0 | 24 个条目（9 改 + 15 新增） |
| 3 | `git diff --stat` | 0 | 无输出（同上，改动已暂存） |
| 4 | `git diff -- . ':!docs/08-reviews/*.md'` | 0 | 无输出（同上） |
| 5 | `git diff --check HEAD` | **2** | `docs/08-reviews/2026-09-04-review-v1-day-1-attempt-3.md:49` trailing whitespace |
| 6 | `git diff --stat HEAD` | 0 | 24 files changed, 1039 insertions(+), 24 deletions(-) |
| 7 | `git diff HEAD -- . ':!docs/08-reviews/*.md'` | 0 | 完整差异（源码/脚本/CI/文档，见下） |
| 8 | 敏感信息扫描（private key / gh token / AKIA / sk- / 明文 password= / secret=） | — | 无命中 |
| 9 | `git ls-files -s services/core-api/mvnw` | 0 | 模式 **100644**（不可执行） |
| 10 | 读取 `.gitattributes` | 0 | `mvnw` 无扩展名，未被任何规则赋予执行位 |

未重复执行 Maven / PowerShell 测试（遵循门禁指令）；未执行任何 Git 写操作；未创建任何测试数据，因此本次无需清理。

### 逐项核验

- **文档先行**：✅ 成立。ADR-0006、feature/ops 文档、`docs/07-changes` 变更记录与 `docs/08-reviews` 报告同步描述并约束了 `review-state.ps1`、`run-validation.ps1` 与 CI 断言。
- **第三轮报告问题关闭情况**：
  - A3-2（JWT secret fail-fast 无测试）→ ✅ 已关闭。`JwtSecretKeyValidationTest` 直接调用真实 `SecurityConfiguration.jwtSecretKey(...)` Bean 方法，覆盖非法 Base64、<32 字节、合法 32 字节三条路径，断言与实现一致。
  - F1（状态机三关键分支无覆盖）→ ✅ 已关闭。`review-state.ps1` 纯函数 + `Test-ReviewBridge.ps1` 覆盖 PASS、第三次 NEEDS_FIX、确定性重复应用、锁占用不消耗 attempt。
  - F2（状态容错/STALLED 不可测）→ ✅ 已关闭。`Show-ReviewStatus.ps1` 支持 `-StateDirectory` 注入，损坏 JSON→`INVALID`、STALLED 判断均有回归。
  - F3（UTF-8 mojibake）→ ✅ 已关闭。`run-review.ps1` 显式设置 `[Console]::OutputEncoding` 与 `$OutputEncoding` 为 UTF-8。
  - A3-1（Testcontainers 无执行证据）→ ⚠️ **未真正闭环**：依赖远端 CI 的 Surefire 断言，但该断言步骤因下方阻断项无法运行。
- **review outcome**：✅ 无明显缺陷。PASS 幂等、第三次 NEEDS_FIX→HUMAN_REQUIRED、锁竞争不写状态，逻辑正确。
- **trailer 识别**：✅ 主缺陷已修复（`docs/07-changes` 派生阶段 ID 纳入已知集合，`review-visibility-and-liveness` 可被识别），仍有轻微过度放宽（见问题 4）。
- **状态容错**：✅ 无明显缺陷。
- **UTF-8**：✅ 修复方向正确。
- **JWT 测试覆盖现有实现**：✅ 真实覆盖（非 mock）。
- **真实密钥/敏感数据**：✅ 无真实密钥。测试用 JWT 均为明确标注的测试值。

### 仍需修复的问题

**阻断（必须修复）：**
1. **CI 会因 `./mvnw` 无执行位而失败** —— `.github/workflows/core-api-ci.yml` 将 `run: mvn ...` 改为 `run: ./mvnw ...`，但 `services/core-api/mvnw` 在索引中为 `100644`（不可执行），Linux runner 上 `./mvnw` 将报 `Permission denied`，"Verify Core API" 步骤失败，随后的 "Assert PostgreSQL integration tests ran" 步骤被跳过，A3-1 的 CI 证据链无法闭环。修复建议（由 Codex 执行，我未改动）：`git update-index --chmod=+x services/core-api/mvnw`，或在 workflow 中加 `chmod +x mvnw` 步骤 / 改用 `sh mvnw`。

**轻微（建议修复，非阻断）：**
2. `git diff --check HEAD` 检出 `docs/08-reviews/2026-09-04-review-v1-day-1-attempt-3.md:49` 存在行尾空白（trailing whitespace）。注意：门禁列出的字面 `git diff --check` 之所以通过，是因为全部改动已暂存、该命令只看未暂存区；若按 HEAD 全量检查则退出码为 2。
3. `scripts/agent-bridge/run-validation.ps1`（以及既有 `run-review.ps1`）硬编码了用户私有绝对路径 `C:\Users\86134\Documents\Codex\2026-09-03\bang\outputs\pi.cmd`。虽非密钥，但暴露用户名且不可移植，建议改为从环境变量/配置解析（脚本已有 `Get-Command pi.cmd` 兜底，但硬编码路径仍会被优先采用）。
4. trailer 已知阶段集合的派生逻辑 `-Filter "*.md"` 会把 `docs/07-changes/README.md` 也纳入（得到 stage id `README`），且未要求日期前缀，比 `Find-AutomaticStageDefinition` 的 `\d{4}-\d{2}-\d{2}-.+\.md` 规则更宽。建议复用同一正则，避免 `Review-Fixes: README` 之类被误判为已知阶段。

（可选低风险备注：CI 断言用 `shell: python`，ubuntu-latest 一般可用；如需更稳妥可改 `shell: bash` + `python3 -c`。）

### 清理结果

本次审查仅执行只读命令，未创建测试数据、未写任何文件、未做 Git 写操作，**无需清理**。

---

```
VALIDATION_RESULT: NEEDS_FIX
```
