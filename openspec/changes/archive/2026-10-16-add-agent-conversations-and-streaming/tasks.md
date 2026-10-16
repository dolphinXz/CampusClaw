# 任务

## 规则与范围
- [x] 新建并评审 `add-agent-conversations-and-streaming` change，`openspec validate --strict` 通过前不写业务代码。
  - verify: 严格校验通过；状态保持 proposed。
- [x] README 与旧链路边界声明同步（`/api/ask` 不变、新链路无依据仍生成）。
  - verify: README 两条链路口径写清；历史 change 未被修改。

## 数据层
- [x] 编写 `V4__agent_conversations.sql`：`assistants`、`assistant_skills`、`assistant_materials`、`ai_skills` 扩展列、`chat_sessions.agent_id`、`chat_messages.skill_name`；为每个班级插入默认智能体（角色提示词取存量 `system_prompts`）、回填既有会话 `agent_id`、把存量启用技能与全班材料绑定到默认智能体、回填技能 `raw_text`。
  - verify: 真实 MySQL 迁移成功；老会话 `agent_id` 非空且绑定齐全；重复启动不重建表。（补充 `V5__skill_code_nullable.sql` 修复 V4 遗漏的 `code` 列可空性）
- [x] 实现实体与 Repository：`Assistant`、`AssistantSkill`、`AssistantMaterial`；`AiSkill`/`ChatSession`/`ChatMessage` 增补字段；绑定查询全部带班级条件。
  - verify: 编译通过；无绕过归属条件的查询方法。

## 智能体管理
- [x] 实现 AgentService：创建/列表（学生只见已启用）/详情（404 语义）/修改/启停/绑定全量替换（本班校验，任一越界 400）/绑定回显；名称班内唯一、角色提示词 ≤2000。
  - verify: apitest 覆盖班内唯一、学生 403、他班/未启用 404、绑定越界 400 不产生部分写入、空角色回退默认（`cases/api/agents/`）。
- [x] 实现 `/api/agents*` 控制器与 DTO（写接口 `@PreAuthorize` + 服务层双校验）。
  - verify: apitest 学生写 403、他班 404。

## 技能文件
- [x] 实现 `SkillFileParser` 纯函数：元数据/正文分隔、必填与长度校验、切词、布尔与工具校验、重复/未知字段报错、保留 rawText。
  - verify: 单测覆盖合法解析、七类非法文件 400 文案、切词结果（`SkillFileParserTest` 8 个）。
- [x] 改造 SkillService 与 `/api/skills*`：`POST/PUT` 只收 `{rawText}` 重解析全量覆盖，`DELETE` 级联解绑，班内名称唯一，写操作仅教师。
  - verify: 单测+apitest：解析入库与回显一致、删除后绑定消失、学生 403、他班 404、重名 400。

## 会话锁定智能体
- [x] 改造 SessionService：`POST /api/sessions` 必带 `agentId`（本班已启用否则 404）、标题「新的提问」→首轮问题前 24 Unicode 码点、列表 updated_at 倒序、详情附 agent 摘要；`chat_sessions` 保存 agent_id。
  - verify: 单测+apitest 覆盖 agentId 校验矩阵、24 码点截断、排序；停用智能体的老会话仍可继续。

## 限定检索
- [x] RetrievalService 增加材料白名单重载：keyword SQL 加 `material_id IN`、vector 回表后过滤、白名单为空直接无检索；编排层做学科过滤、空正文剔除、≤800 字、连续编号。
  - verify: 单测断言白名单过滤、空绑定不扩大范围（`RetrievalServiceTest` 新增 3 个）；学科过滤与连续编号在 `AgentChatServiceTest`。

## 路由与编排
- [x] 实现 `SkillRouter` 纯函数：绑定∩启用、大小写统一子串计分、最高分/同分小 id/零分不选、名称含「解题引导」产出禁令文本。
  - verify: 单测覆盖未绑定、停用、零分、最高分、同分、禁令注入、重复调用结果一致（`SkillRouterTest` 7 个）。
- [x] 实现 `AgentChatService` 固定顺序编排：同步阶段（会话/智能体/技能/路由/标题/落库用户消息/检索/过滤编号，失败抛 HTTP 状态）→ 异步阶段（meta → 历史 6×500 → 组装 → 流式 delta → 落库 → done；meta 后失败发 error，部分文本失败仍落库，零片段不落库）；组装含默认角色、技能禁令、`[n]` 规则、信任边界声明，材料只进用户消息。
  - verify: 单测断言同步阶段 400/404/503、meta 内容与顺序、无依据仍生成且正文禁 `[n]`、部分落库、窗口 6×500、系统消息不含材料正文、旧 `/api/ask` 未受影响（`AgentChatServiceTest` 13 个 + apitest SSE 用例）。

## SSE 协议
- [x] 改造 `MessageController`：meta-first 事件协议、`Cache-Control: no-cache`、`X-Accel-Buffering: no`、流前真实 HTTP 状态、流后 error 收尾。
  - verify: apitest 抓帧验证 meta→delta×n→done 顺序与落库一致；空问题 400；会话不存在 404；依赖故障 503（单测覆盖）。

## 前端
- [x] 改造 `/assistant-config` 为智能体管理+技能文件管理：智能体 CRUD/启停/绑定勾选（技能与材料列表）、技能 rawText 编辑与解析错误展示。
  - verify: 代码完成（`AssistantConfigView.vue`）；学生角色路由拦截已验证；浏览器人工验收待人工执行。
- [x] 改造 `/assistant` 为智能体选择+会话+流式聊天：新建先选已启用智能体（无可用时禁用并提示）、meta 渲染技能提示与可展开引用、delta 追加、done/error/断线处理、刷新恢复、文本渲染不执行 HTML。
  - verify: 代码完成（`AssistantView.vue` + `api.js`）；SSE 协议行为由 apitest 覆盖；浏览器人工验收待人工执行。

## 文档与测试
- [x] OpenAPI：新接口注解、SSE 媒体类型与事件说明；Swagger 可试调。
  - verify: `/v3/api-docs` 含全部智能体/技能/会话路径（21 条）；Swagger UI 200。
- [x] apitest 扩展：智能体 CRUD/绑定/403/404、技能文件错误 400、路由确定性、限定材料检索、meta 先行、新链路无依据仍生成、旧 `/api/ask` 回归。
  - verify: `run_all.py` 50/50 全过并生成 HTML 报告（报告不入库）。
- [x] 后端全量单测、前端 build、真机 e2e、浏览器人工验收。
  - verify: `mvn test` 105 个全绿 ✓；`npm run build` 成功 ✓；apitest 真机 e2e 全过 ✓；浏览器人工验收通过 ✓（验收发现的 7 个问题已全部修复：空会话复用、下拉锁定语义、自定义下拉组件、引用随正文渲染、带认证打开原材料、追问检索词拼接+词法佐证过滤+落库引用过滤、停止不回滚本地消息）。
- [x] 同步 `learning/` 学习文档与 README 使用说明。
  - verify: `learning/class05-智能体会话与流式回答.md` 已写；README 含智能体/技能文件/新链路说明。
