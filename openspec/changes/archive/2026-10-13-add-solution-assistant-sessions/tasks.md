# 任务

## 规则与范围
- [x] 新建并评审 `add-solution-assistant-sessions` change（proposal / design / spec / tasks），OpenSpec 严格校验通过前不 Apply、不写业务代码。
  - verify: `openspec validate --strict` 通过；change 状态保持 proposed 后实施。
- [x] 同步修正 README「本学期不做：开放式长对话」旧边界，使其与本 change 一致；不改历史归档 change。
  - verify: README 范围说明覆盖会话助手与排除项（作业提交等）；历史 change 未被修改。

## 数据层
- [x] 编写 `V3__assistant.sql`：`system_prompts`、`ai_skills`、`chat_sessions`、`chat_messages` 及唯一键、外键与索引。
  - verify: 真实 MySQL Flyway 迁移成功，既有数据不受影响（e2e/apitest 全过）。
- [x] 实现四个实体与 Repository，所有班级相关查询显式携带 `class_id` 或归属用户条件。
  - verify: 会话查询方法签名带 `ownerId`（`findByIdAndOwnerId`）；无不带归属条件的访问方法。

## 提示词与技能
- [x] 实现 PromptService（get-or-create 默认值、教师保存、学生 403、空/超长 400）与 `GET/PUT /api/prompt`。
  - verify: PromptServiceTest 5 例（默认值、保存一致性、学生 403、空 400、effectiveContent）；`@PreAuthorize` + 服务层双校验。
- [x] 实现 SkillService（get-or-create `solution-guide`、教师维护内容与开关、学生 403、他班 id 404）与 `GET /api/skills`、`PUT /api/skills/{id}`。
  - verify: SkillServiceTest 4 例（惰性预置、开关落库、学生 403、他班 404）。

## 会话与消息
- [x] 实现 SessionService：新建会话、本人会话列表、归属校验（他人/他班 404）、消息按序读取。
  - verify: SessionServiceTest 4 例；请求体无 classId 字段可写（DTO 只含 title）。
- [x] 实现 `POST /api/sessions`、`GET /api/sessions`、`GET /api/sessions/{id}` 控制器与 DTO。
  - verify: apitest 断言会话归属 owner/class 与数据库一致、消息与引用完整返回。

## 流式问答链路
- [x] ChatClient 新增 `stream`（OpenAI 兼容 `stream:true`，逐行解析 `data:` 增量回调），保留既有 `complete`。
  - verify: ChatClientStreamTest 4 例（本地 HTTP server 喂 SSE 样本：增量回调、坏行跳过、非 2xx 不泄漏密钥、连接失败）。
- [x] 实现 AssistantService 固定链路：归属校验 → 保存学生消息 → 加载提示词 → 读取技能 → hybrid 前 4 条 → 组装上下文（含最近 6 条历史）→ 未命中短路或流式生成 → 持久化 assistant 消息（内容/basis/citations/skillCode）→ done 事件。
  - verify: AssistantServiceTest 9 例（未命中不调模型、技能启用/关闭上下文差异、历史截断、检索只用最新问题、流中断不落库、400/404/GatewayException 传播）。
- [x] 实现 `POST /api/sessions/{id}/messages` SSE 控制器（`text/event-stream`，delta/done/error 事件，120s 超时，流前依赖故障 503）。
  - verify: 真机断言事件协议（241 段 delta + done）、空问题 400、404 归属、apitest SSE 用例全过；`citations` 由 `@Lob` 改普通列绑定修复 JSON 列写入 22001。

## OpenAPI / Swagger
- [x] 引入 springdoc，配置 bearerAuth scheme，Security 放行 `/v3/api-docs/**`、`/swagger-ui/**`。
  - verify: 浏览器打开 Swagger UI 加载描述（8 个分组）；`/v3/api-docs` 返回 16 个路径。
- [x] 为全部主要控制器补充 `@Tag/@Operation/@ApiResponse`，SSE 接口标注 `text/event-stream` 与事件格式说明。
  - verify: 文档与实际行为一致；nginx/vite 代理转发 swagger 与 api-docs，nginx 对 `/api/` 关闭 `proxy_buffering`（curl 经代理 SSE 逐段到达实测）。

## 前端
- [x] 实现 `/assistant` 会话页：会话列表、新建会话、fetch 流式解析（delta 追加、done 落引用、error 提示）、刷新恢复。
  - verify: 无头浏览器验收：流式回答逐段显示、引用列表展示、未命中固定句、刷新后从会话列表恢复消息（修 `reactive` 响应式缺陷）。
- [x] 实现 `/assistant-config` 教师页：提示词编辑保存、技能内容与开关；学生路由与接口双重不可写。
  - verify: 浏览器验收教师保存生效、技能开关状态切换；学生访问 `/assistant-config` 被路由守卫重定向；真机验证技能开→引导式（skillCode 落库）/ 关→直接作答，提示词保存后下一轮回答含暗号。

## apitest
- [x] 搭建 `apitest/`：`common.py`（HTTP/SSE/DB 助手）、按 URL 组织的用例脚本、`run_all.py` 一键执行。
  - verify: 覆盖登录、检索、提示词 GET/PUT、技能 GET/PUT、创建会话、发送消息（SSE 逐段与落库一致）、未授权 401、学生 403、跨班 404、空问题 400；核对数据库一致性。
- [x] 生成 HTML 报告并把 `apitest/reports/` 加入 `.gitignore`。
  - verify: `apitest/reports/report.html` 生成；`git status` 不出现报告文件。

## 文档与验证
- [x] 更新 README：会话助手使用、Swagger 入口、apitest 运行方式与新增环境变量（无新增密钥类变量则声明沿用）。
  - verify: README 三行范围、技术栈、解题助手/Swagger/apitest 章节与验证清单同步；无新增密钥变量（沿用 `CHAT_*`）。
- [x] 运行后端全量单测与前端 build。
  - verify: `mvn test` 77 个用例全绿；`npm run build` 成功。
- [x] 真机端到端与 apitest 复核：提示词/技能/会话/SSE/跨班/未命中全链路。
  - verify: 既有 e2e 24/24 无回归；`run_all.py` 27/27 全过并生成报告；无头浏览器验收 15/15；技能开关与提示词生效真机验证通过。
