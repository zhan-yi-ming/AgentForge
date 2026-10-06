# R01–R18 合并 main

- 日期：2026-10-06
- 状态：Proposed
- 风险：L3 / Release Gate
- 目标分支：main
- 预检 main：ff1a0a52317c69dba3d51e61e12e56dbf46f2bca
- 来源分支：codex/main-review-fixes
- 预检来源：0f2007c552260fc5fcf6831361f17000c3507b4c

## 背景与目标

R01–R18 已在独立分支逐项核实、修复、测试、只读审核、提交并推送；累计审计未发现经复现确认的严重问题。用户现明确授权把这些提交合并到 main。

本次集成不改写既有提交，不增加产品行为，只把经过审计的来源历史纳入 main，并在隔离工作树上重新执行当前任务的 Release Gate、敏感扫描和 Pi Milestone Review。历史测试只作定位，不能代替本次合并候选的当前机器证据。

## 范围

- fetch origin 并锁定 origin/main 与 origin/codex/main-review-fixes 的精确 SHA。
- 在不触碰根工作树用户改动的隔离工作树中，将 main fast-forward 到来源分支。
- 运行 plan-change-gates、完整 V3 Release Regression、数据库角色、备份恢复与 ASR/Nginx 门禁。
- 清理本轮创建的容器、网络、卷、Junction 和构建生成物。
- 对最终 main 候选执行 diff check、Gitleaks 与 deepseek/deepseek-flash Milestone Review。
- 回填证据、创建独立集成证据提交，再次 fetch；仅当 origin/main 仍为预检 SHA 且是候选祖先时，以非 force 方式推送 HEAD:main，并用 git ls-remote 核验。

## 非目标

- 不 rebase、squash、重写或 force push R01–R18。
- 不修改 R01–R18 的生产实现、迁移、测试或历史审计结论。
- 不触碰根工作树已有 staged、unstaged、untracked 文件。
- 不部署生产、不移动稳定标签、不开始 R19。

## 回滚与停止条件

- 合并候选在推送前可直接丢弃隔离工作树；origin/main 不受影响。
- 若 origin/main 在最终 fetch 后偏离预检 SHA、无法 fast-forward、测试失败、敏感扫描命中或 Pi 出现经确认的严重阻塞项，停止推送并报告。
- main 推送后如需回滚，使用新的显式 revert 提交，不重写远端历史。

## 验证回填

待隔离 main 候选完成 Release Gate 后回填。
