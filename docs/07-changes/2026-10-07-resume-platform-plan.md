# 简历模板平台与 AgentForge 子域名迁移计划

- 日期：2026-10-07
- 状态：Implemented（仅技术计划文档交付；产品与迁移仍为 Proposed）
- 阶段：独立产品规划；不属于 V2/V3 Node，不授权实施或生产迁移
- 交付目标：当前分支 `codex/v3-full-impact-audit` 的纯文档提交
- Git base：`e385ff78d361085b7c101565ca0deca4cf71c081`

## 背景与目标

用户确认先制作免费的模板简历网站，不接入站内 AI 内容生成；新增后台用于模板动态发布。要求交付可独立复制到新项目、供 AI 实施的网站与后台技术计划，同时包含 AgentForge 迁至 `agentforge.<主域名>` 的方案，并保留后续支付、AI 生图和多轮对话生成 HTML 的扩展边界。

## Git preflight 与已有内容

- `git fetch origin` 已实际执行，exit 0；`git rev-list --left-right --count 'HEAD...@{upstream}'` 为 `0 0`，upstream 为 `origin/codex/v3-full-impact-audit`。
- 用户已有修改：`docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md`。
- 用户已有未跟踪内容：`.worktrees/`、`review-fixes-worktree/`、`AgentForge_产品规划与三阶段迭代路线.docx`；全部保留并排除本次提交。
- `git status --short` 报既有 `services/agent-service/.pytest-p304/` 权限 warning；不改变 ACL 或清理该目录。
- 默认终端启动失败：`helper_unknown_error: apply deny-read ACLs`；标准 apply_patch 亦因写入失败未生效。改用提升权限、限定文件范围的命令完成文档读写，不读取生产环境文件。

## 计划文档

- `docs/01-product/resume-platform-implementation-plan.md`：自包含交接文档，覆盖产品、后台、模板契约、API、数据、实时更新、迁移、扩展及验收。
- `docs/02-architecture/decisions/ADR-0037-independent-resume-site-and-shared-edge.md`：Proposed 决策，不替换 AgentForge 的 Java/Python 边界。
- 文档中心、ADR 索引、架构总览、生产运维与变更索引：只增加 Proposed 计划入口，保留现行部署事实。

## 非目标

- 不创建新项目、实现功能、修改路由或 Compose/TLS 脚本。
- 不访问服务器、不改 DNS、不申请证书、不购买资源、不调用模型或收款。
- 不把新产品的 Python 业务服务方案套用到现有 AgentForge。

## 风险与验证计划

- 本次为 L0 / Docs only；涉及安全、数据库和部署的内容均为未来设计，实际实现应重新评估为 L2/L3。
- 先执行 `scripts/validation/plan-change-gates.ps1 -Paths ... -Json`；最终对暂存区重跑。
- 亲自验证文档链接、示例 JSON 可解析、内容一致性、`git diff --check` 和敏感信息模式扫描。
- L0 默认不调用 Pi；前一提交为 L3 审计，本次为其后首个 L0/L1 文档任务，未触发连续五次累计门禁。
- 不运行 Java/Python/Web 产品测试，因为没有实现改动；不将未执行的迁移或新产品测试写成通过。

## 验证与交付回填

### 完成范围

- 新增自包含实施蓝图和 Proposed ADR-0037；更新 5 个文档入口，共 8 个本次文件。
- 蓝图覆盖免费首版、真实后台、SQLite 单实例边界、统一 JSON、受控模板协议、版本/原子发布、ETag/30 秒更新、编辑保护、隐私、安全、API、备份、迁移回滚、未来 PostgreSQL/支付/AI 扩展与逐项验收。
- 代码、Compose、TLS、服务器和 DNS 均未改动；不创建新项目，不调用收费服务。
- 人工交叉核对：新站与旧站的身份/业务/数据分离；模板动态数据与引擎代码发布分离；旧站外部入口的端口、www 证书派生、SSE/MCP/Grafana、issuer 与部署脚本生命周期均已纳入计划。

### 当前机器验证

所有命令工作目录：`C:\Users\86134\Documents\ChatGPT\AgentForge`。

- Git preflight：`git fetch origin`、`git rev-parse HEAD`、`git rev-list --left-right --count 'HEAD...@{upstream}'`，exit 0；base 与 upstream 相同（0/0）。
- 初始门禁：`& ./scripts/validation/plan-change-gates.ps1 -Paths @('docs/07-changes/2026-10-07-resume-platform-plan.md','docs/01-product/resume-platform-implementation-plan.md','docs/README.md','docs/07-changes/README.md','docs/06-operations/production-single-host.md') -Json`，exit 0，L0 / Docs / Review None。该次为写文件前的初始 5 路径规划，fingerprint=`15d1aee470b5174a4fa2fa21c867f5c7223679963532d0da3ec85ccf58fb6157`；后续增加 ADR/架构入口，最终按 8 文件 INDEX 重跑，不能复用初始 fingerprint。
- 文档初检：`python "$env:TEMP\agentforge-resume-plan-validate.py"`，exit 0；8 files、25 local links/anchors、3 JSON examples、7 sensitive-pattern classes、0 failures。检查脚本原文见下，可另存同名临时文件复现。
- 格式初检：`git diff --check`，exit 0。此命令同时看到用户既有 tracked 修改但没有修改它；最终提交校验只针对 INDEX。
- 环境：`git --version` = 2.23.0.windows.1；`$PSVersionTable.PSVersion.ToString()` = 7.6.5；`python --version` = 3.14.3。
- 已有用户文档 SHA256=`57B7689E35F3676820BA71B1B3D71E4C213DA60806155F39486CD9CADCAF708D`，提交前再次比较；不暂存该文件。
- `Get-Command gitleaks -ErrorAction SilentlyContinue` 未找到 CLI，命令探测 exit 1；未安装或冒称 Gitleaks 通过。替代为上述 7 类模式扫描及人工审阅本次 diff。推送后查看仓库现有 Secret scan 工作流，结果另按实际汇报。
- 初次生成检查脚本的工具调用在 JavaScript 解析阶段报 SyntaxError，未启动 shell、未运行检查；修正代码围栏字符转义后，以上实际检查通过。
- 保留 warning：既有 pytest cache 的 Permission denied；本次编辑的已有文档出现 CRLF 将在 Git 中规范为 LF 的提示。未修改用户 ACL、未清理其缓存。
- 无 Java clean verify / Python 产品 pytest / Web build / TLS smoke：本次只写计划，没有实现或生产改动，相关产品测试不适用。
- Pi 0 次：L0 文档且未触发连续 5 次 L0/L1；不以此判断未来实现风险。
- 最终暂存后的必要命令：`& ./scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Json`、`git diff --cached --check`、`python "$env:TEMP\agentforge-resume-plan-validate.py"`；仅全部成功后提交。最终 fingerprint 含变更记录自身内容，输出留在本次执行证据，不自引用伪造固定值。
- 本次验证不建立测试容器/数据库；交付前只删除自己在 TEMP 创建的检查脚本。用户已有 staged/unstaged/untracked 内容均保留。

### 复现文档检查

将以下内容保存为 `$env:TEMP\agentforge-resume-plan-validate.py`，在上述仓库根目录执行 `python "$env:TEMP\agentforge-resume-plan-validate.py"`。扫描输出仅报告文件、规则与行号，不输出疑似凭据原文。

```python
import json, pathlib, re, sys
root = pathlib.Path.cwd()
paths = [
"docs/01-product/resume-platform-implementation-plan.md",
"docs/02-architecture/decisions/ADR-0037-independent-resume-site-and-shared-edge.md",
"docs/02-architecture/decisions/README.md",
"docs/02-architecture/system-overview.md",
"docs/06-operations/production-single-host.md",
"docs/07-changes/2026-10-07-resume-platform-plan.md",
"docs/07-changes/README.md",
"docs/README.md",
]
fence = chr(96) * 3
def without_code(s):
    return re.sub(r"(?ms)^" + fence + r"[^\n]*\n.*?^" + fence + r"[ \t]*$", "", s)
def heading_ids(s):
    ids, seen = set(), {}
    for line in without_code(s).splitlines():
        m = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not m: continue
        slug = re.sub(r"[^\w\- ]", "", m.group(1).lower(), flags=re.UNICODE).replace(" ", "-")
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        ids.add(slug if n == 0 else slug + "-" + str(n))
    return ids
failures, link_count, json_count = [], 0, 0
patterns = {
"private-key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
"api-key": r"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}",
"github-token": r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})",
"aws-key": r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
"jwt": r"\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b",
"bearer-value": r"(?i)\bBearer\s+[A-Za-z0-9_-]{20,}",
"credential-url": r"(?i)(?:postgres(?:ql)?|mysql|https?)://[^/\s:@]+:[^/\s@]+@",
}
for rel in paths:
    p = root / rel
    body = p.read_text(encoding="utf-8")
    if "\ufffd" in body: failures.append((rel, "replacement-character"))
    if len(re.findall(r"(?m)^" + fence, body)) % 2: failures.append((rel, "unclosed-fence"))
    for block in re.findall(r"(?ms)^" + fence + r"json\s*\n(.*?)^" + fence, body):
        try: json.loads(block); json_count += 1
        except ValueError as e: failures.append((rel, "invalid-json", str(e)))
    for target in re.findall(r"\[[^\]]+\]\(([^)\s]+)(?:\s+[^)]*)?\)", without_code(body)):
        if re.match(r"^[a-z]+://", target) or target.startswith("mailto:"): continue
        link_count += 1
        dest, _, anchor = target.partition("#")
        target_path = (p.parent / dest).resolve() if dest else p
        if not target_path.exists():
            failures.append((rel, "broken-link", target))
        elif anchor and target_path.suffix == ".md":
            if anchor not in heading_ids(target_path.read_text(encoding="utf-8")):
                failures.append((rel, "missing-anchor", target))
    for name, pattern in patterns.items():
        for m in re.finditer(pattern, body):
            failures.append((rel, "sensitive-pattern", name, body[:m.start()].count("\n") + 1))
for result in failures: print(json.dumps(result, ensure_ascii=False))
print(json.dumps({"files":len(paths),"local_links_checked":link_count,"json_examples_parsed":json_count,"sensitive_pattern_classes":len(patterns),"failures":len(failures)},ensure_ascii=False))
sys.exit(1 if failures else 0)

```

## 风险与后续

本次只增加 Proposed 规划引用，对运行中产品无直接影响；需要回退时只撤销这 8 个文档的本次变更，不触碰用户内容。后续先在独立新项目实现 P1，生产 M1 与收费/AI 阶段另行授权。主文档可单文件交接；本 ADR 与当前仓库上下文只是迁移实施的辅助证据。

## 提交前复核结果

- 最终 8 文件首次 INDEX 门禁已实际运行，L0 / Docs / None，exit 0；当时 fingerprint 为 `c2df1c26d1fd3656614507da6edf64f26fa1e0d8b00b5cb2c331a289e38480a1`。本节是随后证据回填，提交前会再次重跑，不将该 fingerprint 冒称为回填后的值。
- `git diff --cached --check` 和文档检查均 exit 0；8 files / 25 local links / 3 JSON examples / 7 sensitive-pattern classes / 0 failures。
- `git ls-remote origin refs/heads/codex/v3-full-impact-audit` 核验远端仍为本次 base，没有意外历史变化；暂存区只有上述 8 个 Markdown 文件。
- 首次回填/暂存工具请求因自动审批服务额度不足而未执行；用户明确要求继续后，经正常自动审批重试成功。不是动作安全判定失败，未绕过审核。
- 自建 TEMP 检查脚本已按精确路径删除，未递归删除目录；检查程序完整保存在本记录中。提交前可直接用以下命令提取执行，无须重新生成临时文件：

```powershell
python -c 'import pathlib,re; s=pathlib.Path("docs/07-changes/2026-10-07-resume-platform-plan.md").read_text(encoding="utf-8"); exec(re.search("(?ms)^"+chr(96)*3+"python\n(.*?)^"+chr(96)*3,s).group(1))'
```

- 文档交付为 Proposed 方案；不是新网站或线上迁移完成证明。提交/推送结果以实际 Git 引用和本次最终汇报为准。
