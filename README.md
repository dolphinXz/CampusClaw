价值主张：为校园教学提供安全的班级化知识库协作。
核心场景：教师/学生登录、教师上传、班级隔离、材料入库与列表、可追溯检索与有依据问答、可配置智能体（角色提示词 + 技能文件 + 材料绑定）与锁定智能体的持久化会话、SSE 流式回答（meta→delta→done）、Swagger 文档、接口自动化测试、Compose 启动和 /health。
本学期不做：作业提交、作业批改、异步任务队列、开放式无边界长对话（会话历史仅帮理解追问，最近 6 条 ×500 字进上下文）、语义技能路由（本课为确定性词法路由）、任意工具执行/MCP。

## 技术栈

- 后端：Java 21 + Spring Boot 3（Spring Security + JWT 无状态认证，Bearer 头携带；BCrypt 密码哈希）、MySQL 8、Flyway、springdoc（OpenAPI / Swagger UI）
- 检索：MySQL ngram 全文索引 + Qdrant 向量索引（余弦），支持关键字 / 向量 / 混合（RRF）三种模式；问答为“先检索、后生成”，无依据时固定回答不编造
- 智能体会话：教师为本班配置智能体（角色提示词 + 技能文件绑定 + 材料绑定），学生选定智能体建会话连续追问；服务端每轮重新读取绑定、词法路由至多一个技能、只在绑定材料内检索，SSE（`text/event-stream`）按 `meta → delta × n → done` 返回并持久化
- 前端：Vue 3 + Vue Router + Vite（会话页用 fetch + ReadableStream 解析 SSE）
- 测试：`apitest/` 按 URL 组织的接口自动化测试（`run_all.py` 一键执行 + HTML 报告）
- 部署：Docker Compose（前端 nginx + 后端 + MySQL + Qdrant），数据、材料与向量均挂载持久化 volume

## 快速启动（Docker Compose）

```bash
cp .env.example .env      # 按需修改 DB_PASSWORD、JWT_SECRET 及 EMBEDDING_*/CHAT_* 网关配置
docker compose up --build
```
 
启动后访问 http://localhost （前端 nginx 会把 `/api`、`/health` 反向代理到后端）。
重建容器后，数据库数据（`db-data` volume）、已上传材料（`material-data` volume）和向量索引（`qdrant-data` volume）仍然保留。

> 提示：`EMBEDDING_BASE_URL` 只写到 `/v1`，程序自动拼 `/embeddings`；对话走同一网关的 `/chat/completions`。`EMBEDDING_DIM` 需与向量接口实际返回维度一致（应用启动时与 Qdrant collection 核对）。

## 本地开发

1. 准备 MySQL 8：建库 `campusclaw`，并创建同名用户（密码与环境变量一致）。
2. 启动后端（需要 JDK 21 + Maven）：

   ```bash
   cd backend
   DB_URL="jdbc:mysql://localhost:3306/campusclaw?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC" \
   DB_USERNAME=campusclaw DB_PASSWORD=change-me \
   JWT_SECRET="$(openssl rand -base64 48)" \
   mvn spring-boot:run
   ```

3. 启动前端（另开终端，需要 Node 20+）：

   ```bash
   cd frontend
   npm install
   npm run dev     # http://localhost:5173，/api 与 /health 已代理到 8080
   ```

首次启动会写入种子数据（两个班级、教师/学生账号、每班一条示例材料）。

## 环境变量（只放服务端）

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DB_URL` | 数据库 JDBC 连接串 | `jdbc:mysql://localhost:3306/campusclaw…` |
| `DB_USERNAME` | 数据库用户名 | `campusclaw` |
| `DB_PASSWORD` | 数据库密码 | `change-me` |
| `JWT_SECRET` | JWT 签名密钥，**必填**，≥32 字节；缺失或过短时后端拒绝启动 | 无（必须设置） |
| `JWT_EXPIRATION_MINUTES` | token 有效期（分钟） | `720`（12 小时） |
| `QDRANT_URL` | Qdrant 向量库地址（仅服务端访问） | `http://qdrant:6333` |
| `QDRANT_COLLECTION` | 向量集合名 | `campusclaw_chunks` |
| `EMBEDDING_BASE_URL` | 嵌入网关（OpenAI 兼容，写到 `/v1`） | 无（必须设置） |
| `EMBEDDING_API_KEY` | 嵌入网关密钥（仅服务端） | 无（必须设置） |
| `EMBEDDING_MODEL` | 向量模型名 | 无（必须设置） |
| `EMBEDDING_DIM` | 向量维度（与网关返回一致，启动时校验） | `1536` |
| `CHAT_BASE_URL` | 对话网关（OpenAI 兼容，写到 `/v1`） | 无（问答需要） |
| `CHAT_API_KEY` | 对话网关密钥（仅服务端） | 无（问答需要） |
| `CHAT_MODEL` | 对话模型名 | 无（问答需要） |
| `MATERIAL_STORAGE_DIR` | 材料文件存储目录 | `./data/materials` |
| `SEED_ENABLED` | 是否写入种子数据 | `true` |
| `FRONTEND_ORIGIN` | 允许的前端来源（CORS） | `http://localhost:5173` |

密码只保存 BCrypt 哈希，密钥只从服务端环境变量读取，不写入代码仓库。本地启动前先生成密钥，例如 `openssl rand -base64 48`。

## 测试账号（种子数据）

| 账号 | 密码 | 角色 | 班级 |
| --- | --- | --- | --- |
| `teacher-a` | `Teacher@123` | 教师 | A 班 |
| `teacher-b` | `TeacherB@123` | 教师 | B 班 |
| `student-a1` | `Student@123` | 学生 | A 班 |
| `student-b1` | `StudentB@123` | 学生 | B 班 |

## 运行测试

```bash
cd backend
mvn test
```

- 单元测试：登录签发 JWT 与解析校验（含过期/篡改/短密钥）、错误密码拒绝、学生上传拒绝、跨班材料访问拒绝。
- 端到端测试（`EndToEndFlowTest`）：健康检查免登录、未登录 401、教师/学生登录签发 token、Bearer 头认证、学生上传 403、教师上传入库、本班列表可见、跨班访问 403、无效/过期/篡改 token 401。
  需要可用的 MySQL（本地默认 `campusclaw` 库，或先 `docker compose up -d db`）；数据库不可用时该测试会跳过。

## 检索与问答

- 教师上传材料后自动切分并建立索引（MySQL 全文 + Qdrant 向量）；历史/种子材料在启动时自动补索引（`auto` 策略）。
- 「知识检索」页支持三种模式：`keyword`（仅全文索引，向量库不可用时仍可工作）、`vector`（语义检索，阈值 0.35）、`hybrid`（默认，两路 RRF 融合排序）。
- 问答（`POST /api/ask`）先用混合检索取本班前 4 条切片；**没有依据时直接返回“资料中未找到相关内容”，不调用生成模型**；回答中的 `[1]`、`[2]` 对应引用列表顺序，可回到原材料。
- 教师可对单个材料重建索引：`POST /api/materials/{id}/reindex`（可选 `strategy=auto|custom|hierarchy`），先删旧切片与旧向量再重建；学生调用返回 403。
- 索引失败不影响材料本身：切片标记为 `failed`（不写半截向量、不参与检索），可稍后重建恢复。
- 班级隔离贯穿检索全链路：关键字查询、向量过滤、回表校验三处都使用会话 `class_id`，他班内容表现为无命中。

## 可配置智能体与流式会话

一个智能体规定三件事：**助手是谁**（角色提示词，空值回退服务端默认）、**可用哪些技能**（技能文件绑定）、**可检索哪些材料**（材料绑定，允许为空）。新建会话必须选定本班已启用智能体并整个会话锁定；智能体停用后新会话不能再选，但已有会话仍可继续。

技能是教师上传/编辑的单一文本文件（元数据区 + 空行 + 说明正文），服务端解析后持久化并保留原文：

```text
名称：数学解题引导
适用条件：方程,去括号,分数,通分
资料：数学
工具：knowledge_search
启用：是

先判断学生卡在哪一步，只指出下一步应该检查什么。
```

- 必填：`名称`（≤32 字，班内唯一）、`适用条件`（≤120 字，按逗号/顿号/分号/竖线切词做路由）、空行后的正文（≤2000 字）；`工具` 只允许 `knowledge_search`。
- 缺字段、重复/未知字段、非法布尔值、非法工具、缺正文均返回可读 400；删除技能同步删除绑定；写操作仅教师（学生 403，服务端校验）。

每轮回答的固定顺序：读会话并核对班级（他人/他班 404）→ 读锁定智能体 → 只取**当前绑定且启用**的技能做词法路由（关键词子串计分，至多选一个；改绑定后下一轮立即生效）→ 首轮更新标题并先落库学生消息 → 只在绑定材料内检索（检索词 = 上一条学生消息 + 本轮问题，帮助指代型追问命中正确材料；≤4 条，学科过滤、连续编号）→ 发 `meta` → 加载最近 6 条历史（每条 ≤500 字）→ 组装消息（材料正文只进用户消息，系统消息含信任边界声明）→ 流式 `delta` → 落库含引用与技能名的助手消息 → `done`。

两条问答链路并存，无依据时的行为不同（旧链路口径不变）：

| 链路 | 检索无命中时 |
| --- | --- |
| `POST /api/ask`（第 4 课） | 返回固定「资料中未找到相关内容」，**不调用模型** |
| `POST /api/sessions/{id}/messages`（SSE） | 仍按智能体生成；`meta.status=no_evidence`、引用为空、正文不得出现 `[n]` |

SSE 协议：提问用带 JSON body 的 POST，响应 `text/event-stream`，前端用 `fetch` + `ReadableStream` 解帧（原生 `EventSource` 不能带 Authorization 头和请求体）。事件顺序 `meta → delta × n → done`：`meta` 含 `{status, skill, citations}`，`delta` 只含新增文本，`done` 在持久化后发 `{messageId}`，`error` 用于流开始后的异常收尾。流开始**前**的失败返回真实 HTTP 状态（空问题 400、会话/智能体 404、检索依赖故障 503）。Nginx 关闭 `proxy_buffering` 且服务端返回 `X-Accel-Buffering: no`，增量不被代理攒批。

接口一览：智能体 `GET/POST /api/agents`、`GET/PUT /api/agents/{id}`、`GET /api/agents/{id}/bindings`、`PUT /api/agents/{id}/skills|materials`（全量替换，任一越界 400 且无部分写入）；技能文件 `GET/POST /api/skills`、`PUT/DELETE /api/skills/{id}`（只收 `{rawText}` 重解析全量覆盖）；会话 `POST /api/sessions`（必带 `agentId`）、`GET /api/sessions`、`GET /api/sessions/{id}`（刷新恢复）、SSE 发消息。单个资源越权/不存在统一 404，不泄露存在性。无新增密钥类环境变量，沿用既有 `CHAT_*` 网关配置。

教师在「智能体配置」页管理智能体、勾选绑定技能/材料、编辑技能文件；学生在「解题助手」页选择已启用智能体建会话连续追问，回答按 Markdown 渲染（不执行材料或模型产生的 HTML），引用可展开并跳回原材料。

## Swagger / OpenAPI

- 浏览器访问 Swagger UI：本地开发 `http://localhost:5173/swagger-ui/index.html`（vite 代理），或后端直连 `http://localhost:8080/swagger-ui/index.html`；Compose 部署经 nginx 同路径可达。
- OpenAPI JSON：`GET /v3/api-docs`（免认证读取）；页面右上角可填 Bearer token 试调主要接口，SSE 接口的响应类型与事件格式已在文档中说明。

## 接口自动化测试（apitest/）

```bash
cd apitest
py run_all.py        # Windows（python 3.10+）；或 python run_all.py
```

- 用例按 URL 路径组织：`cases/api/<路径>/<HTTP方法>.py`，一个接口一个脚本。
- 覆盖：登录与 Bearer、检索与数据库一致性、提示词、智能体 CRUD/启停/绑定（学生 403、他班 404、越界 400 无部分写入）、技能文件（解析入库、可读 400、重名、删除级联解绑）、会话锁定智能体（无效智能体 404、停用后老会话仍可用、列表只含本人）、SSE 流式（meta 先行、落库一致、无依据仍生成、空问题 400、他人会话 404）、旧 `/api/ask` 回归（无依据固定句短路）。
- 运行后生成 HTML 报告 `apitest/reports/report.html`（已在 `.gitignore` 忽略，不入库）。
- 连接参数经环境变量：`APITEST_BASE_URL`（默认 `http://localhost:8080`）与 `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME`。

## 验证清单

- [x] 教师/学生登录签发 JWT，token 经 `Authorization: Bearer` 头携带，服务端无状态校验（STATELESS，无会话 Cookie）
- [x] 未登录或 token 无效/过期访问受保护接口返回 401；前端清除本地 token 并引导登录页
- [x] 教师可上传材料，学生上传返回 403（服务端 `@PreAuthorize` + 服务层双重校验）
- [x] 班级隔离在服务端查询层强制 `class_id` 过滤（取自认证主体，每请求回源查库），跨班访问返回拒绝状态
- [x] 材料写入知识库后，本班材料列表能查到该记录
- [x] 密码只保存 BCrypt 哈希；`JWT_SECRET` 等密钥只来自服务端环境变量，过短时启动失败
- [x] 检索：原词查询由 `keyword` 命中、同义改写由 `vector` 命中、`hybrid` 用 RRF 融合而非分数相加
- [x] 每条命中展示标题、切片序号、字符范围与摘录（摘录来自 MySQL），可回到本班原材料
- [x] A 班无法通过伪造 `class_id` 搜到 B 班内容；关键字、向量过滤与回表均执行班级过滤
- [x] Qdrant 不向浏览器暴露；向量库不可用时关键字检索仍成功，向量/混合检索返回 503
- [x] 空查询返回 400；低于阈值或无关问题返回空 `hits` 与固定提示，不伪造结果
- [x] `/api/ask` 有依据时引用编号与 `citations` 顺序一致；无依据时返回固定句、空引用且未调用生成模型
- [x] 嵌入失败时材料与正文仍存在，失败切片不可检索，无半成品向量，可重建恢复
- [x] `docker compose up` 启动应用、数据库与 Qdrant，volume 持久化数据、材料与向量
- [x] `GET /health` 免登录返回服务状态
- [x] 教师可管理本班智能体（CRUD/启停）与技能、材料绑定；学生写接口返回 403（服务端校验）
- [x] 他班/不存在/未启用/不属于当前用户的单个资源统一 404，不泄露存在性
- [x] 新会话必须选定本班已启用智能体并锁定；停用后新会话 404、已有会话仍可继续
- [x] 修改技能或材料绑定后，已有会话下一轮立即使用新配置（会话不存绑定快照）
- [x] 技能文件解析拒绝缺字段、重复/未知字段、非法工具等并返回可读 400；删除同步解绑
- [x] 技能路由确定性：只考虑绑定且启用的技能，至多选一个（最高分/同分小 ID/零分不选）
- [x] 检索只返回智能体绑定、本班、READY 且符合学科范围的材料（≤4 条），引用从 1 连续编号
- [x] 有依据时回答 `[n]` 与引用一致；无依据时仍按智能体生成，引用为空且正文无 `[n]`
- [x] `/api/ask` 保持原有无依据固定句短路行为，未被新流式口径改变
- [x] 抓包可见 `meta → delta × n → done`，`delta` 只含新增文本；代理不缓冲（nginx proxy_buffering off）
- [x] 流开始前失败用真实 HTTP 状态（400/404/503），开始后以 `error` 事件收尾且不泄露上游细节
- [x] 页面刷新后从服务端恢复会话与消息；回答按文本/Markdown 渲染，不执行材料产生的 HTML
- [x] Swagger UI 可打开、加载全部路径的 OpenAPI 描述并配置 Bearer 试调
- [x] `apitest/run_all.py` 一键执行 50 个用例全过并生成 HTML 报告（报告不入库）

**无状态认证边界**：登出只清除前端本地 token，服务端不维护吊销名单——已签发 token 在过期（默认 12 小时）前仍有效，属已声明设计边界（见 `openspec/changes/replace-session-with-token-auth`）。
