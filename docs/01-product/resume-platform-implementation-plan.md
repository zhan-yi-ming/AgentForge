# 简历模板网站与管理后台实施蓝图

- 文档版本：1.0
- 日期：2026-10-07
- 状态：Proposed（交付的是技术计划；网站、后台和域名迁移均未实施）
- 使用方式：将本文件完整复制到一个新的、独立的简历项目，作为实现需求与验收依据；无需读取 AgentForge 全部源码即可开发新站。
- 当前授权：只编写计划。未来在新项目发起实施时，默认只实现本文 P1；收费、AI 生图、AI 对话/HTML 为后续范围。
- 现有 AgentForge 核查基线：`e385ff78d361085b7c101565ca0deca4cf71c081`。仓库状态不是生产部署状态，迁移执行前必须重新盘点真实服务器。
- 临时项目名：Resume Studio（仅为开发代号，可通过站点设置改显示名称）。
- 主域名以 `example.com` 举例，必须替换为用户拥有的域名；仓库历史部署记录为 `zhanyiming.cloud`，不能据此断言当前 DNS、证书或生产版本仍一致。

## 0. 给实施 AI 的约束

1. 在新仓库开发简历产品；不得把它作为 AgentForge 新模块，不复制旧仓库密钥、业务数据库或 Java 服务。
2. 先交付可运行的免费模板网站、真实持久化后台、动态发布能力及操作文档。不要只做静态演示页面、假登录或模拟保存。
3. P1 不调用任何 AI API、不收费、不做普通用户注册、不保存访客简历正文到服务端。后台只有维护者账号。
4. 后台发布模板必须无需重建前端；新渲染组件的开发仍需发布代码。二者边界见第 5 节。
5. 预留模块边界和版本字段，不提前安装 LangGraph、支付 SDK、Redis、消息队列、向量库或实现空壳付费页面。
6. 开发、自动化测试和本地容器运行属于未来实施范围；实际生产迁移、DNS、真实管理员初始化和收费/模型开通需另有用户明确授权。
7. 本文中的服务名、接口和运维命令除标注“现有”者外，均为待实现契约，不得宣称已经存在或验证通过。
8. 如需改变本文核心取舍，先写新项目 ADR；不得悄悄把浏览器本地简历处理改成上传服务器。
9. AgentForge 原有 Java 负责权限/审批/业务写入、Python 负责 Agent 的边界继续有效。新站 FastAPI 是独立产品的确定性业务后端，不改变旧项目规则。
10. 不依赖模板或 AI 文本执行指令：模板 JSON 仅是受校验数据，不能访问文件、执行脚本或决定后台权限。

## 1. 产品目标与第一阶段边界

目标：帮助真实求职者将已有经历整理成排版稳定、可修改、可打印的简历；帮助维护者积累发布模板、收集反馈、排查导出问题的运营经验。P1 免费，运营成本主要为服务器/域名，不产生站内模型费用。

### 1.1 P1 必须交付

- 首页：用途、开始制作、隐私说明、常见问题及 AgentForge 外链。
- 模板库：缩略图、标签、适用场景、筛选、预览、选择；初始 6 个模板，覆盖至少 3 种可辨识版式（可组合不同布局类型与组件变体），不能只换颜色。
- 编辑器：表单输入、JSON 导入/导出、所见即所得预览、字段校验、分区排序、主题选项、打印为 PDF。
- 外部 AI 提示词页：复制固定版本提示词与字段规范，用户自己选择第三方工具；站内无模型调用。
- 本地草稿：当前浏览器 IndexedDB 保存、恢复与一键清除；明显提示换设备不会自动同步，应下载 JSON 备份。
- 后台：登录、模板元数据与声明式配置编辑、预览、发布、下架、撤销当前发布并重新发布历史内容、排序/标签、站点设置、提示词维护、反馈查看、基本统计、审计。
- 发布同步：活跃模板库页面在正常联网条件下 30 秒内发现变化；编辑中的简历不被静默替换模板。
- 运维：持久存储、备份/恢复、健康检查、部署说明、管理员恢复流程及真实测试证据。

### 1.2 P1 明确不做

- 站内聊天、文案改写、生图、模型调用或代用户向第三方 AI 传输信息。
- 普通用户账号、云端简历、公开简历链接、协作编辑、个人简历上传解析。
- 支付、充值、订阅、优惠券、发票集成、订单或余额数据库。
- Word/DOCX 导出、服务器 PDF 渲染、承诺所有招聘系统均能解析的 ATS 评分。
- 任意 HTML/CSS/JavaScript 模板上传、插件市场、用户自行发布模板。
- WebSocket、微服务拆分、多租户后台、可视化任意网页设计器。

### 1.3 成功指标

首批邀请 10–20 位用户试用。观察模板选择次数、进入编辑器次数、JSON 导入成功次数、打印按钮点击数和自愿反馈。打印点击不等于保存 PDF 成功，不把匿名事件数称为真实人数或完成单数。

验收目标：不经过开发者手工修数据，用户能完成“填内容 → 换模板 → 校验 → 打印 → 再次导入继续编辑”；维护者能独立发布模板并看到前台变化。

## 2. 用户与页面

| 角色 | 可做什么 | 禁止做什么 |
| --- | --- | --- |
| 访客 | 看公开模板、在本地制作/保存简历、复制提示词、提交文本反馈 | 写模板、访问草稿版本、读后台审计 |
| 管理员 | 管模板、发布、站点设置、处理反馈、看汇总统计 | 读取用户本地简历；站点不收集这些正文 |
| 未来普通账号 | 后续可拥有云端文档/付费权益 | P1 不实现、不复用管理员身份 |

前端路由：

| 路径 | 行为 |
| --- | --- |
| `/` | 产品首页，导航包含 AgentForge 外链 |
| `/templates` | 公开模板库 |
| `/templates/:slug` | 模板介绍与使用虚构样例的预览 |
| `/editor` | 本地编辑器；地址可带 template ID/version，但禁止放姓名、简历 JSON 或联系方式 |
| `/guide/import` | JSON 示例、第三方提示词和导入说明 |
| `/privacy`、`/terms` | 实际数据处理和服务限制 |
| `/feedback` | 无附件反馈；提示不要填写简历或敏感资料 |
| `/admin/login` | 管理员登录 |
| `/admin/templates`、`/admin/templates/:id` | 列表、草稿、版本、预览和发布 |
| `/admin/settings`、`/admin/prompts` | 公共站点配置和提示词版本 |
| `/admin/feedback`、`/admin/metrics`、`/admin/audit` | 运营与审计 |

后台路由懒加载；前台移动端可用，桌面编辑优先。移动端编辑/预览切换，避免并排挤压；后台至少支持桌面 Chrome/Edge。公开页面提供基本 title/description、canonical 和 sitemap；后台与编辑器 noindex。robots.txt 不是权限控制。

## 3. 技术方案与仓库结构

### 3.1 明确选型

| 层 | P1 选择 | 原因/约束 |
| --- | --- | --- |
| Web | React + TypeScript + Vite，React Router | 单个前端应用覆盖公众页与后台；产物是静态资源 |
| 表单/请求 | React Hook Form；TanStack Query 管服务端数据 | 编辑数据留在本地；模板目录按服务端版本更新 |
| 数据契约 | JSON Schema Draft 2020-12；前端 Ajv 2020，后端 jsonschema | 共用 schema 文件；未知版本拒绝而非猜测转换 |
| 后端 | Python + FastAPI + Pydantic | 管理员认证、模板目录、发布、反馈与统计 |
| 持久化 | SQLAlchemy 2 + Alembic + SQLite | 单机、一个 API worker、低并发后台写入；无需新增数据库服务器 |
| 本地简历 | IndexedDB，带格式版本 | 不用后端保存；支持显式备份导出 |
| 图片资产 | 服务端数据卷，仅管理员上传的模板封面 PNG/WebP/JPEG | MIME/解码/大小检查；不接受 HTML/SVG/ZIP 或外链抓取 |
| PDF | CSS 分页 + 浏览器打印 | 不启动服务器 Chromium；说明浏览器打印设置 |
| 测试 | pytest + Vitest + Playwright | 公共 API、真实浏览器排版与导出 |
| 部署 | Docker Compose + 单一公网 Nginx edge | P1 新站仅需 Web/API 两个服务；生产无 Node 常驻进程 |

实施时选择相互兼容的维护中稳定版本并写锁文件、固定镜像版本；不以未经核验的“latest”作为生产版本策略。Node 仅用于本地/CI 构建和测试。

SQLite 限制必须公开：单 API worker、WAL、启用 foreign_keys、busy_timeout、短事务、持久本地磁盘，不能放在 NFS 或同时启动多个写实例。SQLAlchemy 可减少迁移改动，但 SQLite → PostgreSQL 不会自动完成；见第 12 节。后台认证/发布数据量低，适合第一阶段这一约束。

### 3.2 建议目录

```text
resume-studio/
  apps/web/src/
    app/                  # 路由与装配
    features/catalog/
    features/editor/
    features/admin/
    features/feedback/
    lib/api/
    lib/local-drafts/
  packages/contracts/
    schemas/              # resume、template、document bundle JSON Schema
    fixtures/             # 合法/非法、长内容、中英文样例
  packages/template-engine/
    src/                  # 纯函数布局/组件注册表；禁止网络/AI/支付依赖
  services/api/
    app/
      modules/admin_auth/
      modules/catalog/
      modules/publishing/
      modules/settings/
      modules/feedback/
      modules/metrics/
      modules/audit/
      infrastructure/
    migrations/
    tests/
  infra/
    compose.yaml
    nginx/
  docs/
    product.md
    architecture.md
    api.md
    operations.md
    decisions/
  scripts/                # 待实现的校验、备份、恢复和管理员 CLI
```

前端不能读数据库；路由只协调用例，发布校验/事务置于应用服务。模板引擎只接收受校验的内容和模板，返回布局；不得在引擎中查询登录态或支付状态。

新站与 AgentForge 不共享表、账号、JWT、管理员、Python 虚拟环境或运行目录。共享的只有主机和明确配置的边缘入口。

## 4. 简历数据与本地文档契约

### 4.1 统一 ResumeData v1

```json
{
  "schema_version": "1.0",
  "profile": {
    "name": "示例姓名",
    "target_role": "后端开发工程师",
    "email": "example@example.com",
    "phone": "",
    "location": "",
    "website": ""
  },
  "summary": "这里是用户自己确认的简介。",
  "education": [
    {
      "id": "edu-1",
      "school": "示例大学",
      "degree": "本科",
      "major": "计算机科学",
      "start": "2020-09",
      "end": "2024-06",
      "highlights": []
    }
  ],
  "experience": [],
  "projects": [
    {
      "id": "project-1",
      "name": "示例项目",
      "role": "开发者",
      "start": "2024-01",
      "end": "",
      "url": "",
      "highlights": ["填写真实职责与成果，不自动编造数字。"]
    }
  ],
  "skills": [
    { "id": "skill-1", "category": "开发", "items": ["Python", "SQL"] }
  ],
  "awards": [],
  "custom_sections": []
}
```

契约要求：

- 所有顶层字段必需；列表可为空，文本可为空。非空姓名是导出前检查，不阻止保存草稿。
- profile 字段如示例；experience 项为 id/company/role/start/end/highlights；awards 为 id/title/date/description；custom_sections 为 id/title/items（纯文本数组）。
- 数组项 id 在各自数组唯一；各 section 有稳定代码。未知字段报带路径的错误，不静默丢弃；将来升级 schema 时另写迁移。
- start/end/date 为 `YYYY-MM` 或空字符串；空 end 表示“至今”，起止顺序需要跨字段校验。
- URL 只允许空值或 https/http；禁止 javascript/data/file 等协议，外链使用安全 rel；邮件/电话当文本，不接受 HTML。
- 普通短字段最多 200 字符，summary 最多 2000，单条 highlight/item 最多 1000；每个经历/项目最多 30 条，highlights 最多 20 条，custom_sections 最多 10 组、每组 30 条，技能组最多 20、每组 30 项，教育/奖项最多 30 条。
- JSON 导入上限 256 KiB（UTF-8）；过大/过深/类型错误应有可读提示，禁止 eval 和对象原型污染。
- 导入识别“纯 ResumeData”与“本站导出的 DocumentBundle”；允许移除唯一一层 Markdown json 围栏，不尝试执行或任意修复内容。
- 导入前展示摘要和覆盖确认；解析失败保持现有草稿。支持按字段修改和删除；用户内容渲染为文本，P1 不支持 Markdown/raw HTML。

### 4.2 DocumentBundle v1

本地草稿及下载备份保存：

- `document_format_version: "1.0"`、`document_id`（本地 UUID）、`created_at/updated_at`。
- `resume`：ResumeData v1。
- `presentation`：template_id、template_version、已发布模板 definition 快照、engine_schema_version、受限主题覆写、section_order、显式分页点。
- template 快照属于公开设计，不包含管理员草稿/会话或执行代码。
- 本地快照仍需完整 schema 校验；不能因“本站导出”而放宽输入限制。
- 模板内容更新不改变 ResumeData；切换模板不丢字段。模板未显示的字段保留在编辑器并提示“此模板当前未展示”。
- P1 图片简历头像不支持；避免 base64 膨胀与个人图片存储。未来通过文档资产层扩展。

本地草稿写入应有短防抖、失败提示与容量异常处理。清除草稿需确认；退出管理员会话不应删除访客草稿。隐身模式/浏览器清理可能丢数据，页面显式提示下载备份。

## 5. 动态模板协议：后台到底能改什么

### 5.1 采用声明式模板，不远程执行代码

模板 = 元数据 + 版本化 TemplateDefinition + 封面资产。布局引擎在前端随代码发布，模板配置从 API 获取。

允许后台在已支持能力内修改：颜色、字体 token、字号/间距、页边距、单/双栏、栏宽、组件排列、字段是否显示、分区标题、分隔线、列表样式、分页策略。保存并发布后不需要前端重新构建。

不允许后台上传 React 源码、任意 CSS、HTML、JS、模板表达式或远程字体 URL。高阶 AI 预制模板时，应让其输出本协议的 JSON；已有 AI HTML 设计需由开发者转换成受控配置。确实需要新组件时先开发引擎并发布兼容版本，再发布新模板。

示例：

```json
{
  "template_schema_version": "1.0",
  "engine_schema_version": "1.0",
  "required_capabilities": ["layout.single-column", "block.profile", "block.section"],
  "page": { "size": "A4", "margin_mm": 14 },
  "theme": {
    "font": "noto-sans-sc",
    "font_size_pt": 10.5,
    "line_height": 1.45,
    "accent": "#2457A7",
    "text": "#20252B",
    "section_gap_mm": 4
  },
  "layout": {
    "type": "single-column",
    "blocks": [
      { "type": "profile", "variant": "compact" },
      { "type": "section", "source": "summary", "title": "个人简介" },
      { "type": "section", "source": "education", "title": "教育经历" },
      { "type": "section", "source": "experience", "title": "工作经历" },
      { "type": "section", "source": "projects", "title": "项目经历" },
      { "type": "section", "source": "skills", "title": "技能" },
      { "type": "section", "source": "awards", "title": "奖项" },
      { "type": "section", "source": "custom_sections", "title": "" }
    ]
  }
}
```

P1 组件固定为 profile/section/divider；布局 single-column/two-column，双栏用 main/sidebar 两个 block 数组及 sidebar_ratio（0.25–0.4）。字段绑定只能选内置 source，不接受 JSONPath/表达式。section 支持列表/紧凑样式，所有组合仍受 schema 限制。

约束：A4、页边距 8–25 mm、字号 9–14 pt、行距 1.2–1.8、分区间距 2–10 mm；颜色为六位十六进制；字体为随站点部署且许可证允许的白名单。颜色可读性需要发布预览检查，不能只通过 JSON schema 宣称可读。

模板元数据：id（UUID）、slug（稳定且唯一，小写字母数字短横线）、name、description、tags、display_order、cover_asset_id、visibility、active_version。更换显示名称不改变 id/slug。

### 5.2 发布状态与一致性

- `DRAFT`：可修改，只能后台读取；含 revision 整数，用 If-Match 乐观锁防止两窗口覆盖。
- `PUBLISHED VERSION`：不可修改的完整快照，版本号在模板内单调递增。
- `LISTED / UNLISTED`：模板是否在公共目录展示；下架不删除历史版本。
- `REVOKED`：某版本因安全/授权问题禁止新的公开获取；返回 410。已下载的离线快照无法远程追回，后台和文档必须说明。

保存草稿不对公众生效。发布前后端执行 schema/能力校验、封面存在检查、所有标准样例校验；管理员完成桌面与打印预览确认。发布事务内创建不可变版本、切换 active_version、递增 catalog_version、追加审计记录，任何一步失败整体回滚。

发布/下架/恢复历史版本使用 Idempotency-Key；同 key 同请求返回原结果，不同请求内容返回 409。后台 mutation 请求通过服务端事务和唯一约束去重；已认证请求先查询幂等结果，再校验新请求的 revision，确保成功后的重放不会被旧 revision 拒绝。客户端禁用按钮仅是交互辅助。

“回滚模板”含义是把历史 definition 克隆为新版本后发布，保留完整历史；不覆盖原版本、不回退 catalog_version。审计记录操作类型、操作者、对象版本和结果，不存管理员密码或访客简历。

旧浏览器通过 required_capabilities 判断兼容性；不支持的新模板显示“请刷新升级”，不能尝试渲染后崩溃。至少保留当前 engine schema 的兼容支持；删除支持前需要迁移与公告。

## 6. 实时同步与编辑保护

这里的“实时”定义为运营可见的有界延迟，不承诺毫秒推送。P1 不引入 WebSocket/SSE。

1. 模板库首次进入请求公开目录，使用 ETag / If-None-Match。
2. 可见页面每 30 秒重新验证，窗口重新获得焦点时立即验证；隐藏页面暂停轮询。
3. 发布成功递增 catalog_version，后端提交事务后新查询立即可见；Nginx 不缓存 API。
4. 公开 API 响应 `Cache-Control: no-cache`，允许存储但每次使用需重新验证；ETag 必须包含发布目录版本和查询参数，不共用错误的筛选缓存。
5. index.html 使用 no-cache；带内容 hash 的 JS/CSS 长缓存。P1 不注册 Service Worker，避免隐式离线缓存版本问题。
6. 编辑器始终使用开始编辑时绑定的版本/快照。发现新版本只显示通知；用户确认后先保存原草稿再切换，可恢复原版本。
7. 下架只阻止新目录选择，正常历史版本仍可用于已存在草稿；撤销版本返回 410 并提示选其他模板，不删除用户内容。
8. 网络故障使用已有本地快照继续编辑/打印，显示离线提示；没有缓存的首次访问显示重试，不能用空数组冒充“暂无模板”。

验收：正常联网且前台处于可见状态时，管理员发布/下架后 30 秒内目录改变；自动化用可控时钟检验轮询，并做一次真实浏览器计时 smoke。服务故障/后台标签页不计入 30 秒 SLA，恢复时立即重新验证。

## 7. 后台管理与安全

P1 单管理员角色，可有多个独立维护账号，但不建设角色设计器或公共注册。真实管理员通过服务器 CLI 交互式创建，不在代码、种子或聊天中放默认密码。

认证要求：

- 密码使用 Argon2id；登录错误统一，避免泄露账号是否存在。密码重置为服务器 CLI 操作并撤销原有全部会话。
- 服务端随机 opaque session，数据库仅存 token 哈希。Cookie 使用 `__Host-resume_admin`、Secure、HttpOnly、SameSite=Strict、Path=/，无 Domain 属性。
- 空闲 30 分钟、绝对 8 小时过期；登录后更新 session；登出撤销服务端 session。
- 所有后台写接口执行精确 Origin 校验和 CSRF token 校验，包括站点设置、上传及发布；登录也校验 Origin/JSON 类型并限速。SameSite 不能代替 Origin/CSRF，兄弟子域仍属于同站。
- 登录默认每 IP 10 次/10 分钟、每账号 5 次失败/15 分钟；返回 429/Retry-After，避免永久锁死。计数存 SQLite，TTL 清理，重启不能绕过。
- 后台读写默认拒绝匿名；鉴权在应用服务入口统一执行，不能只隐藏前端按钮。
- CORS 生产默认关闭，浏览器通过同源 /api 调用；开发环境仅允许明确 localhost origin，生产拒绝通配。
- 模板封面最大 2 MiB、最长边 4096 px；真正解码校验像素上限，重新编码剥离元数据，服务器生成文件名；拒绝伪装 MIME、目录穿越、SVG/HTML、远程拉取。
- 后台页和 API 不缓存；CSP 禁止外部脚本与内联执行；只允许引擎需要的受控样式，不能以 unsafe-eval 解决模板渲染。
- 日志默认不记请求正文、Cookie、Authorization；错误只暴露 request_id 和业务错误，不回显栈或配置。
- 不提供后台“查看所有用户简历”：服务端没有这类数据。

后台编辑器采用表单配置 + 可选 JSON 编辑面板，均使用同一 schema 校验。预览只用仓库内虚构样例，不能默认上报维护者粘贴的真实简历。P1 设置页只能修改名称、简介、联系说明、AgentForge URL、首页公告等白名单字段；不能修改数据库连接、密钥或任意注入页面脚本。

## 8. 服务端数据模型与存储

以下是新站待实现表，均与 AgentForge 数据库隔离。时间统一 UTC；公开 ID 用 UUID；布尔/JSON/时间字段通过 ORM 做跨库映射。

| 表 | 核心字段与约束 |
| --- | --- |
| admin_users | id、username unique、password_hash、disabled、created_at |
| admin_sessions | id、admin_id FK、token_hash unique、csrf_secret/hash、created_at、last_seen_at、expires_at、revoked_at |
| auth_attempts | scope/key_hash、window_start、count、blocked_until；TTL 清理，不永久保存原始 IP |
| templates | id、slug unique、visibility、active_version nullable、draft_revision、draft_payload、updated_at |
| template_versions | template_id + version unique、definition、完整元数据快照、engine_schema_version、published_at、revoked_at |
| catalog_state | 单行 id=1、catalog_version；发布/下架事务中递增 |
| assets | id、content_hash、mime、width/height、relative_path、created_at；不得包含访客附件 |
| site_settings | 单行白名单 JSON、revision；含公开配置版本 |
| prompt_versions | id、schema_version、prompt_text、published_at；draft/public 边界同样受控 |
| feedback | id、category、message、可选 contact、status、created_at；无附件 |
| metric_daily | day + event_name + template_id（可空）的计数；不保存用户文档或跨站追踪 ID |
| admin_operations | admin_id + idempotency_key unique、request_hash、result、expires_at；发布事务共同提交 |
| audit_events | id、admin_id、action、object_id、object_version、request_id、created_at、result |

active_version 必须引用本模板不可变版本；应用校验与数据库约束共同保证。草稿封面与已发布版本资产引用分开计算；被任一保留版本引用的资产不得物理清理。

资产写入先进入临时目录并完成校验，再原子移动，最后提交记录；数据库失败留下的孤儿文件由定期、可审计清理处理。不得在请求失败时删除已发布共用资产。P1 不对外支持按传入磁盘路径读文件。

SQLite 数据库、WAL 和资产存放于 `/opt/resume-studio/data` 的持久卷；容器重建不丢数据。每日使用 SQLite backup API 生成一致性备份，资产采用只增不改及引用清单配套备份；禁止直接复制正在写入的单个 sqlite 文件。离线安全删除仅对确认无引用且已过保留期资产执行。

## 9. API 契约

所有新站接口以 `/api/v1` 开头，与 AgentForge 相同路径前缀通过域名分离，不能在网关混转。API 错误统一：

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "请检查模板配置",
    "fields": [{ "path": "theme.font_size_pt", "reason": "必须在允许范围内" }],
    "request_id": "example-request-id"
  }
}
```

### 9.1 公共 API

| 方法/路径 | 约定 |
| --- | --- |
| GET /site | 名称、公告、AgentForge URL、配置版本；只返回白名单公开字段 |
| GET /templates?tag=&q=&cursor=&limit= | 只返回上架 active_version 摘要；limit 默认 24、最大 60；稳定排序 display_order/id |
| GET /templates/{id} | 当前公开介绍与 active_version；下架返回 404 |
| GET /templates/{id}/versions/{version} | 只读已发布快照；草稿 404、撤销 410；ETag 再验证 |
| GET /prompts/resume-json | 当前公开提示词、schema_version 和内容版本 |
| GET /schemas/resume/1.0 | 公开 ResumeData JSON Schema |
| GET /assets/{id} | 已发布版本可引用的封面；草稿资产走后台鉴权接口 |
| POST /feedback | category/message、可选 contact；限长限速，201 |
| POST /events | 事件枚举、template_id（可选）；严格字段白名单、204；失败不阻止编辑 |
| GET /health/live | 存活，响应无配置内容 |
| GET /health/ready | DB 与 schema 就绪；不在公网暴露详细拓扑 |

公共目录响应为 `{catalog_version, items, next_cursor}`；每个 item 包含 id/slug/name/tags/cover_url/active_version/engine_schema_version/required_capabilities。cursor 绑定 catalog_version；翻页中目录发生变化返回 409 CATALOG_CHANGED，前端重新加载首屏。

反馈最长 2000 字符、contact 最长 200；默认每 IP 3 次/10 分钟。事件只允许 template_selected/editor_opened/import_succeeded/print_requested；每次动作最多一条，限速并标注指标可被机器人影响。统计不能传 resume/name/email/phone/page 完整查询串/任意 properties。指标故障 fail-open，认证和发布故障 fail-closed。

### 9.2 管理 API

| 方法/路径 | 约定 |
| --- | --- |
| POST /admin/session | 登录，签发安全 Cookie |
| GET /admin/session | 当前管理员及 CSRF token；no-store |
| DELETE /admin/session | 退出并撤销会话 |
| GET/POST /admin/templates | 列表/创建草稿 |
| GET/PATCH /admin/templates/{id}/draft | 读/写草稿；PATCH 必须 If-Match revision |
| POST /admin/templates/{id}/validate | 校验并返回 errors/warnings；不发布 |
| POST /admin/templates/{id}/publish | draft_revision、preview_checked；必须 If-Match 与 Idempotency-Key |
| POST /admin/templates/{id}/unlist | 下架并递增目录版本；幂等 |
| GET /admin/templates/{id}/versions | 历史版本及撤销状态 |
| POST /admin/templates/{id}/restore | 选历史版本克隆为草稿；仍需预览与发布 |
| POST /admin/templates/{id}/versions/{version}/revoke | 标记不可新获取；必填 reason；若为 active 同事务下架 |
| POST /admin/assets | 图片上传；鉴权/CSRF/限制 |
| GET /admin/assets/{id} | 后台草稿封面访问 |
| GET/PATCH /admin/settings | 白名单设置、If-Match 乐观锁 |
| GET/PUT /admin/prompts/resume-json/draft | 提示词草稿、If-Match |
| POST /admin/prompts/resume-json/publish | 发布提示词；版本化且幂等 |
| GET/PATCH /admin/feedback[/{id}] | 列表/处理状态 |
| GET /admin/metrics?from=&to= | 最大 90 天汇总 |
| GET /admin/audit?cursor= | 审计分页，避免一次导出全部 |

错误：未登录 401，无权限/CSRF/Origin 不通过 403，资源不可见 404，已撤销 410，状态/幂等冲突 409，revision 过期 412，缺少必要前置条件 428，格式错误 422，超限 413，限流 429，依赖不可用 503。后端对每个写操作重新校验，不能相信前端 preview_checked 代替机器校验；该字段只是维护者人工确认记录。

schema 文件是结构校验权威；通过公共契约 fixtures 检查 Ajv 与 Python jsonschema 接受/拒绝结果一致。Pydantic 负责 HTTP envelope，不另写一套冲突的模板字段规则。

## 10. 编辑器、打印与隐私

编辑器使用与后台预览完全相同的引擎。简历输入、JSON 校验、模板渲染和打印在浏览器内完成；API 仅取得公开模板/配置。隐私验收必须抓取网络请求确认没有简历正文上传。

打印要求：

- A4，固定 mm/pt 布局；等待自托管字体加载完成后才启用打印。
- 提供纸张 A4、缩放 100%、关闭浏览器页眉页脚的引导；颜色不作为唯一信息来源。
- 不使用整页截图拼接 PDF；导出的主要文本应可选择/检索。
- 分区标题与首段尽量不分开；单条经历优先不切开，超过整页高度时允许按段落/条目拆分并提示。
- 支持用户显式添加分页点；展示页数与溢出提示，禁止无声裁剪/自动缩到不可读字号。
- P1 核心验收浏览器为桌面 Chrome/Edge；移动端提供编辑与打印引导，不承诺所有手机系统打印布局完全一致。
- 本地自动化用 Playwright 在测试机生成 PDF 并检查页数/文本、截图比对；这不代表生产需要运行 Chromium。

隐私说明准确写明：简历正文留在设备；模板请求/安全访问日志仍可能处理 IP 和浏览器信息；自愿反馈会发往服务器。默认安全日志 7 天、反馈 90 天（处理完可提前删除）、审计 180 天、汇总指标 365 天，后续因适用法律调整时更新公示。不要声称“网站完全不处理任何个人信息”。

香港部署仅减少部分部署手续；P1 免费不等于所有网站义务免除。上线前确认适用的联网备案、隐私告知、模板/字体授权。后续新增收费或面向公众的在线 AI，再单独确认主体、支付准入及适用 AI 登记/标识要求。不得把本技术文档当法律许可证明。

## 11. 域名与 AgentForge 迁移方案

### 11.1 目标路由

| Host | 路径 | 目标 |
| --- | --- | --- |
| example.com | /、/templates、/editor、/admin/... | 简历 Web |
| example.com | /api/... | 简历 FastAPI |
| example.com | /assets/... | Web 构建的带 hash 静态资源；模板封面走 /api/v1/assets/{id} |
| www.example.com | 全站 | 308 到 https://example.com，保留 path/query |
| agentforge.example.com | / | AgentForge Web |
| agentforge.example.com | /api/...、/mcp | AgentForge Core API |
| agentforge.example.com | /grafana/... | 现有 Grafana（如该部署启用） |
| 未配置 Host | 任意 | 拒绝，不回落到任一站后台 |

这里是变更 hostname，不是把旧应用挂到 `/agentforge` 路径。AgentForge 内部页面/API 路径保持不变。根域名不整体跳转到子域名；P1 不迁移已有浏览器 session，用户重新登录。

### 11.2 已亲自核对的现有仓库事实

| 文件 | 当前事实 | 迁移影响 |
| --- | --- | --- |
| infra/compose.prod.yaml | project=agentforge；gateway 发布 80/443，业务容器仅 app 网络 | 新入口不能与旧 gateway 抢相同端口 |
| infra/nginx/production.conf.template | 代理 Core、Web、/mcp、Grafana，含认证/AI 限流与 SSE 设置 | 新边缘必须保留完整路由和策略，不能只转发到 Web |
| apps/web/nginx.conf | Web 还有 /api 和 /mcp 代理，但没有完整生产 gateway 策略 | 不能认为只代理 Web 就等价迁移 |
| scripts/deploy/common.sh | 非 www 开头域名一律派生 www.PUBLIC_HOST | 直接设置 agentforge.example.com 会派生 www.agentforge.example.com |
| scripts/deploy/tls-issue.sh | 按 PUBLIC_HOST/PUBLIC_WWW_HOST 签证书，固定 cert-name | 旧脚本无法直接承担两个独立站点的目标证书管理 |
| scripts/deploy/tls-sync.sh、tls-renew.sh、install-tls-timer.sh | TLS 生命周期由 AgentForge 管理 | 迁移后必须明确唯一续期所有者 |
| infra/compose.prod.yaml | Grafana root URL 使用 PUBLIC_URL_HOST | 子域名切换需重算并重建相关服务 |
| docs/06-operations/production-single-host.md | 生产目录 /opt/agentforge/repo，环境文件在仓库外 | 保留路径和数据卷；不得生成新 env 覆盖旧密钥 |

这些是代码与文档事实，不是对当前线上运行的断言。历史文档超时数值与当前模板可能不同，实施时以锁定的部署 commit 为准，记录实际有效值，不能把旧说明抄成配置。

### 11.3 选定拓扑：独立共享 edge，旧 gateway 退出公网职责

同机只运行一个对公网发布 80/443 的 Nginx edge。新项目维护 `infra/edge/` 下版本化配置，Compose project 为 site-edge；证书/状态放在 /opt/site-edge。Resume 与 AgentForge 保持两个独立应用栈。

```text
Internet :80/:443
  -> site-edge (Nginx, 唯一 TLS/ACME 入口)
       -> resume-web / resume-api       [resume_app 网络]
       -> af-web / af-core / af-grafana  [agentforge_app 网络]
  resume-api -> 独立 SQLite 与封面数据卷
  af-core/af-agent -> 现有 PostgreSQL 等依赖
```

- 实施前通过 Docker inspect 获取真实网络名；不能仅猜 project 前缀。给旧业务服务增加唯一网络别名 af-web/af-core/af-grafana，避免两个网络中同名 web 冲突。
- edge 加入两个应用网络；数据库不发布宿主端口。新 API 不加入 AgentForge 网络，也不获取旧数据库凭据。
- 新 edge 直接代理旧业务容器，不串联两层 TLS/gateway，避免重复限流、错误客户端 IP、协议和续期所有权混乱。
- 旧 standalone 部署继续可用。未来在 AgentForge 仓库增加明确 external-edge 部署模式及 override，停用 gateway 端口/自动启动；不能把现有文件永久改成仅适配新站。
- 若采用 Compose 的 !reset/!override 清除 ports，先锁定支持版本并验证最终配置；简单合并 ports 可能保留旧绑定，必须 fail-closed。
- scripts/deploy/common.sh 当前只加载单个生产 Compose；必须让 external-edge 模式覆盖 deploy/update/rollback/health-check 等全部入口，禁止更新脚本再次启动旧 gateway。
- health-check 改为检查新 edge 的完整域名入口和旧业务服务；不能仅删除 gateway 检查后宣称整体健康。
- edge 配置应容忍某个站点暂时未启动，不能因另一栈 DNS 尚未就绪导致 Nginx 整体无法启动。使用锁定版本支持的 Docker DNS 动态解析方案，并测试容器重建换 IP。
- P1 若两个站暂时位于不同服务器，各自可以有 80/443；共机时再启用共享 edge。不要为未恢复的旧应用阻塞新站本地开发。

以上是未来部署代码变更，必须在 AgentForge 原仓库单独走文档/TDD/部署验证门禁；本次仅记录方案，不实施。

### 11.4 TLS、域名与代理契约

- 证书明确覆盖 example.com / www.example.com / agentforge.example.com；可以两张证书（主站+www，AgentForge 单域名），不申请 www.agentforge.example.com。
- 共享 edge 负责 ACME HTTP-01 webroot 与唯一续期 timer；旧 AgentForge timer 在新签发/续期验证通过并切换入口时停用，保留配置用于回退。
- 新证书全链、私钥与 ACME account 位于 /opt/site-edge/tls，权限收紧，不进 Git；证书失败保留原有效证书，不覆盖为自签。
- 新服务器申请 HTTP-01 证书前域名解析需指向新机。接受短暂停机，使用 ACME-only 初始 HTTP 配置签发后再启用 HTTPS，不能用 curl -k 代替证书验证。
- DNS 无 IPv6 时删除错误 AAAA；核查 CAA 与签发限制；更换服务商同时处理适用备案接入/信息变更。
- AgentForge PRIVATE env 仅调整 PUBLIC_HOST 和 URL 类配置。计划将 issuer 改为 https://agentforge.example.com/core-api，则旧 JWT 失效，提前说明重新登录；不重生成数据库/内部通信密钥。
- 外层 edge 覆盖 Host、X-Forwarded-Proto=https、X-Real-IP，并基于真实可信入口重建 X-Forwarded-For，不能信任客户端伪造链。若前面有 CDN，另写可信 IP 配置。
- 保留现有登录限速、AI 限速、请求大小、SSE/NDJSON 禁缓冲、/mcp 流式和 Grafana WebSocket；只复制旧站规则到旧域名 server，不能限制新站普通目录为 AI 调用频率。
- AgentForge 的 /api/v1/.../agent/chat/stream 必须流式；反向代理 read_timeout 按部署 commit 的完整超时链核对。既有模板目前为 360 秒，/mcp 为 300 秒。
- Grafana 的 root URL、子路径和安全 Cookie 随新 Host 验证；其他外部客户端的 MCP endpoint 需改为新域名。
- 不共享根域 Cookie；检查 localStorage key 和跨域登录，不尝试跨域搬运 token。
- 主域名旧 /api、/login 等路径可能与新站冲突，默认不做通配重定向；如要保留书签，仅对确认无冲突的 GET 页面建立白名单跳转。旧 API 客户端显式更新 base URL。
- HTTP 跳 HTTPS；先保守配置 HSTS，不在所有子域 HTTPS 完成前启用 includeSubDomains/preload。

### 11.5 迁移步骤（执行任务必须另行授权）

1. 盘点：旧机到期/释放时间、部署 commit、启用 profiles、端口、域名/DNS、真实卷和目录；不输出 env、密码、token 或用户内容。
2. 备份：PostgreSQL 一致性 dump、上传/挂载数据、启用的 Neo4j 与其他必要状态、部署版本/配置清单；私有 env/证书加密另存。新站如已有后台数据，一并备份 SQLite 与资产。
3. 恢复演练：在隔离环境实际恢复并验证业务表/登录与文件，记录结果；Git 源码不是数据备份。至少一份备份在旧机之外。
4. 准备新机：Docker、时钟、磁盘、日志上限、防火墙；公网仅 edge 的 80/443 和受限 SSH。内存评估考虑 AgentForge 的 Grafana/Loki/可选 Neo4j，不能仅按“一套 Java”估算。
5. 恢复两个应用栈：锁定版本、保持 named volume 身份，先内网健康检查。先不要启动争用 80/443 的旧 gateway。
6. 配置 DNS/证书：可在维护窗口改解析，按 11.4 的 ACME-only 流程取得证书；主域名、www 与 agentforge 都要验证。
7. 启用共享 edge：nginx -t 通过后切换；同步 AgentForge URL 类配置，确认旧续期 timer 不再管理新入口。
8. 验收：按第 14 节的迁移测试清单执行；新后台管理员通过交互式 CLI 初始化。
9. 观察与留存：至少保留一份已验收的备份与配置版本。网站可停机，但旧机释放前不得遗漏数据导出；域名独立续费。
10. 收口：更新两仓运维文档、记录真实测试与故障；新站正常后再另行决定是否降低旧平台资源占用。

回滚：在切换前记录 old/new DNS、证书路径、edge 与应用 commit、旧 timer 状态。新主机不可用时可恢复旧解析与旧 standalone 入口（旧机仍存在时）；旧机已释放则从备份恢复，不能承诺立即回退。共享 edge 配置错误优先回到上一份通过 nginx -t 的配置；应用回滚不删除数据库卷。主站已有后台写入后，回滚应用不得拿旧空库覆盖新数据。

## 12. 未来扩展：留边界，不提前实现

| 领域 | P1 保留 | 未来再实现 |
| --- | --- | --- |
| 内容 | ResumeData / DocumentBundle 版本 | 多轮对话产生结构化内容，经用户确认再保存 |
| 渲染 | RenderStrategy 与 template-engine 边界 | template / ai_html / ai_image 三种 artifact |
| 存储 | repository 接口、迁移脚本、资产 ID | PostgreSQL、对象存储、云端文档与按 owner 鉴权 |
| 认证 | 独立管理员认证模块 | 普通用户体系；不得复用管理员 Cookie |
| AI | 记录未来 provider adapter 设计 | LangGraph、模型网关、会话/checkpoint、成本预算 |
| 收费 | 记录服务器授权入口位置 | 订单、支付通知、权益与账本、退款 |
| 任务 | 当前发布事务同步完成 | AI/渲染持久任务队列与 worker |

### 12.1 收费扩展

增加账户/订单前优先迁至 PostgreSQL：冻结后台写入 → SQLite backup → 转换导入 → 校验行数/约束/模板 hash → 用新库验证 → 切换连接 → 保留旧库只读回退。JSON、时间、布尔和序列差异必须测试；不得承诺改 DSN 即完成。

未来 billing 模块拥有订单与权益。支付回调验签、金额/币种/订单校验、幂等和事务由确定性后端执行；LLM、前端和模板不能增加额度。金额用整数分；订单/权益/任务状态分离。付费生成在服务端创建有预算的任务，失败/超时/重复回调与退款均有明确状态。

已经发送到浏览器的免费模板 JSON 无法靠前端隐藏变为可信付费资源。将来出售模板应单独设计服务端访问控制和商业规则，不能声称用户无法复制已获得的模板。

### 12.2 AI 生图扩展

增加 ImageGenerationProvider、GenerationJob 和私有 Artifact。模型供应商只在服务端接入，限制张数/分辨率/重试与预算；任务幂等，供应商超时先查询避免重复消费。输出保留 provenance/模型版本/标识元数据，图片是独立 artifact，不能伪装成可编辑文本简历。用户先确认姓名、联系方式等内容；不承诺图中文字始终准确。

### 12.3 多轮对话与 HTML 扩展

建议流程：收集经历 → 澄清缺项 → 用户确认 ResumeData → 生成 HTML 版式 → 校验与隔离预览 → 导出。LangGraph 只编排模型/会话，后台授权/计费/最终保存仍由确定性应用服务控制。

AI HTML 与 P1 声明式模板分开存储/渲染，不允许把生成 HTML 塞进已信任模板字段。将来使用独立无业务 Cookie 的预览 origin（优先独立注册域名）、sandbox iframe、严格 CSP，并在独立无密钥 worker 中渲染；限制出网与内网/云元数据访问。不在主站 origin 直接执行生成脚本。

公开启用收费/AI 前增加业务与合规 Gate；不通过“香港服务器”“仅收成本”“API 中转”自动跳过主体、支付准入、适用 AI 登记、标识和隐私要求。

## 13. 新站部署与运营维护契约

- 本地一条文档化 Compose 命令启动 Web/API 与演示模板；本地示例密钥与生产严格隔离。
- 生产数据目录 /opt/resume-studio/data，备份 /opt/resume-studio/backups，私有配置 /opt/resume-studio/env；共享 edge 独立 /opt/site-edge。
- 数据库迁移单独执行并备份，不让多个 worker 启动时争抢迁移；P1 强制一个 API worker。
- 健康检查不输出密钥；日志轮换。模板发布失败告警至少在后台可见，提供 request_id。
- 管理员 CLI 待实现为 `python -m app.cli admin create` / `admin reset-password`，交互读取密码而非命令行明文参数。
- 备份 CLI 待实现为 `python -m app.cli backup create`；文档解释一致性、资产清单、权限和恢复命令。每次正式版本升级前备份，定期实际恢复。
- 每日备份 7 份、每周 4 份作为初始策略；重要备份离机加密，生命周期包含资产和反馈数据，删除策略与隐私说明一致。
- 新站可独立启动/停止。AgentForge 故障不能导致简历 API 失败；edge 中一个 upstream 不可达只影响对应站点。
- 生产示例配置只放占位符，管理员密码与 Cookie/支付/模型密钥不能进入仓库、日志或交付截图。
- 新项目 README 包含架构、启动、首次管理、模板更新、备份恢复、故障排查及已知限制。

## 14. 验收矩阵

实施 AI 必须亲自执行并记录命令/版本/退出码/数量/限制；不能只写“已完成”。

| 范围 | 必测行为 |
| --- | --- |
| 内容导入 | 合法 JSON/代码围栏/本站 bundle；畸形/超大/未知版本/危险 URL 被拒；失败不覆盖草稿 |
| 模板渲染 | 6 模板、至少 3 种版式；中英文、空字段、长经历、多页、长链接；无丢字/截断/重叠 |
| 本地草稿 | 刷新恢复、清除确认、导出再导入等价、IndexedDB 不可用有降级提示 |
| 隐私 | 用可识别虚构文字做网络断言，编辑/打印不向 API、日志或统计发送正文 |
| 后台认证 | 未登录读写拒绝、错误/过期/撤销 session、CSRF、兄弟子域 Origin、限流、重启后限流 |
| 后台资产 | 伪造 MIME、SVG/HTML、路径穿越、解码炸弹与过大文件拒绝；草稿资产不可公开 |
| 发布 | 草稿不公开、校验失败不发布、并发草稿 412、重复发布不产生第二次副作用 |
| 事务 | 模拟发布中间失败，active_version/catalog_version/audit 一致回滚 |
| 动态更新 | 两个浏览器上下文，发布后活跃目录 30 秒内更新；没有重新部署前端 |
| 旧文档 | 更新模板不修改打开的简历；手动升级可恢复；下架不丢内容；撤销返回 410 |
| HTTP 缓存 | ETag 304、发布后 ETag 改变、筛选 ETag 不混用、游标版本变化 409 |
| 故障恢复 | API 断网时已有草稿继续编辑；API/容器重建后模板、管理员和封面仍存在 |
| 打印 | Chrome/Edge 手工抽查，Playwright PDF 文本抽取与分页检查；无截图式整页 PDF |
| 备份 | 从新备份实际恢复到隔离目录/容器，模板版本/hash、账号和资产完整 |
| 运营 | 反馈限速/删除；非法埋点拒绝；指标名称不冒称真实人数或成功导出 |
| 迁移 | 两站域名正确、TLS 链有效、www 跳转、未知 Host 拒绝、旧端口不再公网绑定 |
| 旧平台回归 | 登录、项目、Chat SSE 增量、/mcp、Grafana（启用时）与 JWT issuer 变化 |
| 入口生命周期 | nginx -t、证书续期 dry-run、上游换 IP、某站停机不拖垮另一站、回滚演练 |

P1 新站 API/认证/Schema 属于高风险实现，不能因本文是文档而降级未来测试；AgentForge 迁移至少做部署/TLS/契约验证，改变认证边界按 L3，按原仓库门禁决定完整回归范围。

## 15. 实施顺序与交付物

| 阶段 | 内容 | 阶段交付 |
| --- | --- | --- |
| P1-A | 新仓库、技术锁定、schema、样例与受控模板引擎 | 能用本地固定样例渲染/打印，契约测试红绿闭环 |
| P1-B | 表单/JSON 编辑、本地草稿、6 模板 | 访客制作完整闭环与 PDF 验收 |
| P1-C | FastAPI、SQLite/Alembic、管理员认证、草稿/资产 | 真实持久化后台和安全测试 |
| P1-D | 发布/版本/ETag/30 秒更新/下架恢复 | 多浏览器动态发布验收 |
| P1-E | 站点配置/提示词、反馈/汇总指标、隐私、备份 | 可运营 MVP，完整本地验证与部署手册 |
| M1（独立授权） | 旧站备份、共享 edge、子域名/DNS/TLS 切换 | 两站实际可访问、回滚与续期证据 |
| P2（以后授权） | 普通账号/云端文档/支付与 PostgreSQL | 以真实需求与合规路径确定范围 |
| P3（以后授权） | AI 生图 | 有成本上限、可靠交付与失败处理 |
| P4（以后授权） | 多轮对话 + HTML | 用户确认内容、隔离渲染、版本化产物 |

P1-A 至 P1-E 是同一首版的实施顺序，不要求每一步都重新向用户确认；重大范围变更/凭据/真实生产写入才单独处理。实施 AgentForge 的 M1 时遵守其原仓库治理，不把 M1 视为已授权 Node。

交付必须包括：可运行源码、锁文件、数据库迁移、虚构种子模板、共用 schemas、测试、env 示例、README、管理员与备份操作手册、容器配置、已知限制和真实验证摘要。禁止交付“后台按钮都有但请求是 mock”的半成品。

## 16. 开工默认值与必须现场确认的参数

可直接采用的默认值：React/FastAPI/SQLite；首版 6 模板；免费；单管理员角色；30 秒目录更新；本地简历；浏览器打印；无站内 AI。没有业务名称/Logo 时用 Resume Studio 与文字标识，不为这些阻塞本地实现。

只在生产部署前确认：实际主域名、服务器地域/到期时间/资源、目标代码版本、现有 DNS/证书、管理员由谁初始化、备份存储位置、必要网站登记与模板字体授权。所有真实凭据由用户在受控环境输入，不要求粘贴到聊天。

可复制给新项目 AI 的起始请求：

> 请依据本文件实现 P1-A 至 P1-E 的免费简历模板网站与真实管理后台。先建立项目文档、数据契约和验收计划，再逐步完成并亲自验证。核心约束是模板通过后台发布动态生效、用户简历正文留在浏览器、P1 无站内 AI 和收费。保留第 12 节的扩展边界，但不提前实现支付、生图或对话。使用本文默认技术选型，只有重大矛盾或确实缺少的信息才提问。域名迁移只准备配置与操作方案，未获生产授权前不要操作 AgentForge、DNS 或服务器。

## 17. 依据与相关文件

新项目复制本文件即可理解方案；下列 AgentForge 文件只供迁移实施者核对，不是新站运行依赖：

- 现有部署：`infra/compose.prod.yaml`、`infra/nginx/production.conf.template`、`apps/web/nginx.conf`。
- 现有脚本：`scripts/deploy/common.sh`、`tls-issue.sh`、`tls-sync.sh`、`tls-renew.sh`、`install-tls-timer.sh`、`health-check.sh`。
- 运维背景：`docs/06-operations/production-single-host.md`。
- 本次 Proposed ADR：`docs/02-architecture/decisions/ADR-0037-independent-resume-site-and-shared-edge.md`。

外部资料于 2026-10-07 核查；实现时重新核对所用版本：

- [FastAPI 容器部署](https://fastapi.tiangolo.com/deployment/docker/)。
- [Nginx 按域名分流](https://nginx.org/en/docs/http/server_names.html)。
- [HTTP ETag](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag)。
- [香港/非内地节点的备案说明](https://help.aliyun.com/zh/icp-filing/basic-icp-service/support/for-the-record-process-faq)。
- [生成式人工智能服务管理暂行办法](https://www.cac.gov.cn/2023-07/13/c_1690898327029107.htm)。
