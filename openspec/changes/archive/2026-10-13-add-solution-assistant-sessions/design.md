# 设计

## 技术栈与范围
- 延续 Java 21 + Spring Boot 3.3.5（Web、Security、Data JPA）、MySQL 8 + Flyway、Vue 3 + Vite、docker compose；对话模型经由 OpenAI 兼容 HTTP 网关，只从 Spring Boot 服务端访问。
- 新增：springdoc-openapi（Swagger UI + `/v3/api-docs`）、`SseEmitter`（Spring MVC 原生，不引入 WebFlux）、对话网关 `stream: true` 增量调用。
- 本变更不改登录、角色、材料上传、班级隔离与第 4 课检索/`/api/ask`；`class_id` 一律取自已认证主体。

## 一次回答的固定链路
```
POST /api/sessions/{id}/messages (Bearer)
 1 认证取 principal（角色/班级每请求回源，沿用 JWT 设计）
 2 会话归属校验：session.ownerId == principal.id，否则 404（跨班与不存在一致）
 3 保存学生消息（user 行）
 4 加载班级提示词（无保存记录时用内置默认）
 5 读取班级「解题引导」技能及 enabled
 6 hybrid 检索本班前 4 条切片（复用 RetrievalService.topHits，阈值与 RRF 不变）
 7 组装上下文：系统提示词 +（技能启用时）技能指令 + 检索片段 [1..n] + 会话历史（最近 6 条）+ 本轮问题
 8 未命中（0 切片）：固定句「资料中未找到相关内容」直接作为 delta+done 下发，不调用对话模型，citations=[]
 9 命中：调用对话网关 stream，逐段转发 delta 事件
10 完成：持久化 assistant 消息（完整回答 + 引用 JSON + basis + skillCode），下发 done 事件
```
- 客户端只能提交 `question`；system 角色消息、classId、引用等字段不在请求 DTO 中定义。
- 检索只用本轮问题；会话历史仅帮模型理解追问，最多保留最近 6 条（user/assistant 交替，无 system）。
- 提示词保存「从下一轮对话开始生效」自然成立：每轮都从数据库读当前值。
- 流式中途网关断开：已完成的增量已下发，assistant 消息不落库（避免半截回答被当成完整回答），下发 `error` 事件后关闭；客户端可重试。
- 503 语义：嵌入/Qdrant/网关在**流开始前**失败（检索阶段、或首次 delta 前的连接失败）→ 普通 HTTP 503 JSON；已开始下发 delta 后失败 → `error` SSE 事件。

## 表结构（Flyway `V3__assistant.sql`）

```sql
CREATE TABLE system_prompts (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    content     TEXT NOT NULL,
    updated_by  BIGINT NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_prompts_class (class_id),
    CONSTRAINT fk_prompts_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_prompts_user  FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_skills (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    code        VARCHAR(64) NOT NULL,          -- solution-guide
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    content     TEXT NOT NULL,                 -- 注入上下文的技能指令全文
    enabled     TINYINT(1) NOT NULL DEFAULT 1,
    updated_by  BIGINT,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_skills_class_code (class_id, code),
    CONSTRAINT fk_skills_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_skills_user  FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_sessions (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    owner_id    BIGINT NOT NULL,
    title       VARCHAR(200) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_sessions_owner (owner_id, class_id),
    CONSTRAINT fk_sessions_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_sessions_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_messages (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    session_id  BIGINT NOT NULL,
    class_id    BIGINT NOT NULL,
    role        VARCHAR(16) NOT NULL,          -- user / assistant
    content     MEDIUMTEXT NOT NULL,
    basis       VARCHAR(16),                   -- found / none（仅 assistant）
    citations   JSON,                          -- 引用数组（仅 assistant，未命中为 NULL）
    skill_code  VARCHAR(64),                   -- 本轮生效技能（仅 assistant，可追溯）
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_messages_session (session_id, id),
    CONSTRAINT fk_messages_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_class FOREIGN KEY (class_id) REFERENCES class_rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- 每班至多一条提示词、一条 `solution-guide` 技能：由**服务层 get-or-create 惰性初始化**（不依赖 SeedDataService——它只在空库运行；真实库启动后已有班级也能拿到默认值）。默认技能 `enabled = 1`。
- `chat_messages.citations` 存第 4 课引用结构的 JSON 数组（materialId、materialTitle、chunkId、chunkIndex、excerpt），与 `/api/ask` 的 Citation 一致。
- 可追溯：assistant 消息记录 `basis`、`skill_code`、`citations`，可还原「当时用没用技能、检索命中了什么」。
- 跨班/他人会话查询一律 `owner_id = :uid AND id = :sid`，查不到 → 404，不区分「不存在」「无权」「他班」。

## 接口设计

| 方法与路径 | 角色 | 说明 |
| --- | --- | --- |
| `GET /api/prompt` | 任意已登录 | 返回本班提示词（get-or-create 默认值） |
| `PUT /api/prompt` | 教师 | 保存本班提示词（`{content}`，1～8000 字），记录 updated_by |
| `GET /api/skills` | 任意已登录 | 返回本班技能列表（get-or-create `solution-guide`） |
| `PUT /api/skills/{id}` | 教师 | 更新技能 `{name?, description?, content?, enabled?}`；id 不属于本班 → 404 |
| `POST /api/sessions` | 任意已登录 | 新建会话 `{title?}`，title 默认取问题首条消息（后续可扩展） |
| `GET /api/sessions` | 任意已登录 | 列出本人会话（不含他班/他人，含消息数） |
| `GET /api/sessions/{id}` | 会话归属人 | 会话详情 + 全部消息（含引用） |
| `POST /api/sessions/{id}/messages` | 会话归属人 | `{question}` → `text/event-stream` |

- 错误码约定（写入 OpenAPI）：400 空问题/空提示词/超长；401 无 token/无效 token；403 学生改提示词或技能；404 会话不存在或不归属；503 检索依赖（嵌入/Qdrant/网关）故障。
- 跨班表现：`GET/POST /api/sessions/{他班或他人id}/*` → 404，与不存在一致；伪造请求体 `classId` 无字段可写（DTO 不定义）。

## SSE 事件协议

```
Content-Type: text/event-stream
event: delta   data: {"text":"分数加减"}        # 每段增量，可多条
event: done    data: {"messageId":12,"basis":"found","citations":[…],"skillCode":"solution-guide"}
event: error   data: {"message":"问答服务暂不可用"}   # 流中途失败
```
- 未命中时也走 `delta`（内容为固定句）+ `done`（`basis:"none"`、`citations:[]`），客户端统一按流渲染。
- `done` 只在 assistant 消息已持久化后发出，保证客户端拿到 done 即可在刷新后从 `GET /api/sessions/{id}` 看到同样内容。

## ChatClient 流式调用
- `stream(List<Message> messages, Consumer<String> onDelta)`：POST `/chat/completions`，body 带 `"stream": true`；用 `java.net.http.HttpClient` 读响应流，逐行解析 `data: …`，累计 delta 文本并回调；`data: [DONE]` 结束返回全文。
- 失败（连接、非 200、流中断）抛 `GatewayException`，异常消息只含异常类型，不带密钥与上游原文。
- 既有非流式 `complete()` 保留（`/api/ask` 继续使用，不受影响）。

## OpenAPI / Swagger
- 依赖 `org.springdoc:springdoc-openapi-starter-webmvc-ui`（2.6.x，兼容 Boot 3.3）。
- `OpenAPI` Bean：标题/版本/描述 + `bearerAuth` securityScheme（http bearer JWT）。
- Security 放行 `/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`（免认证读文档；试调仍需自备 token）。
- 控制器加 `@Tag/@Operation/@ApiResponse`；SSE 接口标注 `text/event-stream` 响应类型并在描述中写清事件格式。
- nginx 与 vite 代理新增 `/v3/api-docs`、`/swagger-ui` 转发，浏览器经 http://localhost/swagger-ui.html 可达；nginx 对 `/api/` 关闭 `proxy_buffering` 并调大 `proxy_read_timeout`，保证 SSE 不被缓冲。

## 前端
- 路由：`/assistant`（会话与聊天，需登录）、`/assistant-config`（提示词与技能，meta.teacherOnly，路由守卫 + 组件内再校验）。
- SSE 用 `fetch` + `ReadableStream` 逐块解析（`EventSource` 不能带 Authorization 头）；解析 `event:/data:` 帧，`delta` 追加渲染，`done` 落定引用，`error` 提示可重试。
- 侧边栏新增「解题助手」（所有人）与「助手配置」（教师）入口；沿用既有卡片、按钮、状态药丸样式。

## apitest/
```
apitest/
├── run_all.py                 # 发现并执行 cases/**/*.py（按文件名匹配 HTTP 方法），生成 HTML 报告
├── common.py                  # base_url、token、HTTP 与 SSE 客户端、MySQL 校验助手
├── cases/api/
│   ├── auth/login/POST.py
│   ├── retrieval/POST.py
│   ├── prompt/{GET,PUT}.py
│   ├── skills/{GET,PUT}.py
│   ├── sessions/POST.py
│   └── sessions/{session_id}/messages/POST.py
└── reports/                   # 运行产物，.gitignore 忽略
```
- 纯标准库 + `requests` + `pymysql`；连接参数取环境变量 `APITEST_BASE_URL/DB_*`（带本地默认值），不写密钥。
- 每个用例核对：状态码、响应 JSON/SSE 结构与业务内容、数据库落库一致性（如 PUT 提示词后 `system_prompts.content` 与响应一致）、失败请求不产生多余写入、跨班 404 不泄露存在性、SSE 能逐段接收且结束后内容完整。
- 报告：简单自生成 HTML（表格：用例、断言、通过/失败、详情），不引入 pytest/Allure，避免额外依赖。

## 测试策略
- 单测（Mockito）：PromptService（get-or-create、学生保存 403、超长 400）、SkillService（get-or-create、开关、跨班 id 404）、SessionService（归属校验 404、消息落库字段）、AssistantService（链路顺序、未命中短路不调模型、技能启用/关闭上下文差异、历史截断、引用顺序、流中断不落库）。
- ChatClient 流式解析用本地 `com.sun.net.httpserver.HttpServer` 喂 SSE 样本做单测。
- 真机 e2e：扩展 `.tmp/e2e-check.mjs` 覆盖提示词/技能/会话/SSE/跨班；apitest `run_all.py` 独立复核并出报告。
- 浏览器验收：学生流式逐段显示、教师改提示词下一轮生效、技能关闭回答变化、刷新后会话恢复。

## 风险与边界
- **SSE 与代理**：nginx 默认会缓冲响应，必须 `proxy_buffering off`；vite dev 代理默认流式透传。
- **长回答超时**：`SseEmitter` 超时设 120s；超时视为中断（不落库）。
- **历史膨胀**：只保留每会话最近 6 条进上下文；完整历史仍在库里可查。
- **提示词/技能被注入**：教师内容视为可信教学配置（与第 4 课系统提示同级）；学生不可写；内容不参与 SQL/命令拼接。
- **与 /api/ask 并存**：`/api/ask` 是无会话单轮问答，本课不改其行为；两条链路共用检索与网关。
