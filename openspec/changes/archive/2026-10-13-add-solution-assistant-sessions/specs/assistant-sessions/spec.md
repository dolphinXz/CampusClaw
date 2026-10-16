# Spec Delta

## Purpose
在既有登录、班级隔离和本班知识库检索之上，为学生提供可连续追问的解题助手会话：教师维护系统提示词与解题引导技能，服务端按「提示词 + 技能 + 本班检索」固定链路生成回答，经 SSE 逐段返回并持久化，检索未命中时如实说明且不编造引用。

## ADDED Requirements

### Requirement: 教师提示词管理
系统 SHALL 按班级保存唯一的教师系统提示词，教师可读写、学生只读；每轮回答 MUST 使用当前已保存的提示词参与上下文组装。

#### Scenario: 教师保存并读取提示词
- **WHEN** 教师 `PUT /api/prompt` 提交合法内容后调用 `GET /api/prompt`
- **THEN** 系统应返回刚保存的内容，且数据库 `system_prompts` 中该班级记录的内容与响应一致

#### Scenario: 学生不能修改提示词
- **WHEN** 学生调用 `PUT /api/prompt`
- **THEN** 系统应返回 403 且数据库内容不变

#### Scenario: 保存后下一轮生效
- **WHEN** 教师保存新提示词后学生在会话中再次提问
- **THEN** 本轮回答的模型上下文应包含新提示词，检索结果与技能开关保持不变

#### Scenario: 未保存时使用默认提示词
- **WHEN** 班级从未保存过提示词而任意已登录用户请求 `GET /api/prompt`
- **THEN** 系统应返回内置默认提示词（不报错），且不因读取产生越权数据

### Requirement: 解题引导技能管理
系统 SHALL 为每个班级预置至少一条「解题引导」技能，教师可维护其内容与启用状态，学生 MUST 不能开关或修改技能；技能状态与内容 MUST 由服务端管理。

#### Scenario: 预置解题引导技能
- **WHEN** 教师请求 `GET /api/skills`
- **THEN** 系统应返回至少一条代码为 `solution-guide` 的技能，含名称、说明、指令内容与启用状态

#### Scenario: 教师开关技能
- **WHEN** 教师 `PUT /api/skills/{id}` 修改 `enabled` 后重新读取
- **THEN** 系统应返回新状态，且数据库记录与响应一致

#### Scenario: 学生不能开关技能
- **WHEN** 学生调用 `PUT /api/skills/{id}`
- **THEN** 系统应返回 403 且技能状态不变

#### Scenario: 他班技能 ID 不可用
- **WHEN** 教师用他班技能 ID 调用 `PUT /api/skills/{id}`
- **THEN** 系统应返回 404，与不存在的表现一致

### Requirement: 解题引导技能行为
技能启用时，回答 SHALL 引导学生分析问题、拆解步骤、指出本班材料出处而不是直接给出最终答案；技能关闭时同一问题的回答方式 SHALL 有明显区别；资料未命中时 MUST NOT 编造引用。

#### Scenario: 启用时注入技能指令
- **WHEN** `solution-guide` 处于启用状态且学生提问命中本班材料
- **THEN** 模型上下文应包含技能指令全文，回答应引用检索到的本班材料（编号与引用顺序一致），且完成事件与落库消息记录生效的技能代码

#### Scenario: 关闭时上下文不含技能指令
- **WHEN** 教师关闭 `solution-guide` 后学生用同一问题提问
- **THEN** 模型上下文不应包含该技能指令，回答方式与启用时明显不同（直接作答风格）

#### Scenario: 未命中不编造引用
- **WHEN** 本班知识库检索无任何命中
- **THEN** 系统应返回「资料中未找到相关内容」、引用为空、不调用对话生成模型，且不产生任何虚构的材料标题或出处

### Requirement: 会话与消息持久化
系统 SHALL 支持学生新建会话并在同一会话中连续追问；学生消息与助手回答 MUST 持久化，助手消息 SHALL 记录检索依据、引用与生效技能代码。

#### Scenario: 新建会话并连续追问
- **WHEN** 学生创建会话后连续发送两个问题
- **THEN** 两个学生消息与对应助手回答都应出现在同一会话中，`GET /api/sessions/{id}` 能按顺序返回全部消息及引用

#### Scenario: 消息落库与响应一致
- **WHEN** 一次流式回答完成后检查数据库
- **THEN** `chat_messages` 中新增的 assistant 行内容应等于流式收到的完整回答，`citations`、`basis`、`skill_code` 与 `done` 事件一致

#### Scenario: 会话归属校验
- **WHEN** 用户访问不属于自己的会话（他人或他班）
- **THEN** 系统应返回 404，与不存在的表现一致，且不泄露会话是否存在

### Requirement: SSE 流式回答
`POST /api/sessions/{id}/messages` MUST 以 `text/event-stream` 返回；助手回答 SHALL 通过 `delta` 事件逐段下发，`done` 事件 MUST 在消息持久化后发出并携带消息 ID、检索依据与引用。

#### Scenario: 逐段接收后内容完整
- **WHEN** 学生在归属会话中提交非空问题且检索命中
- **THEN** 客户端应能收到多条 `delta` 事件，全部拼接结果等于最终完整回答，结束时收到含 `messageId` 的 `done` 事件

#### Scenario: 空问题拒绝
- **WHEN** 提交空白或空 `question`
- **THEN** 系统应返回 400 且不创建任何消息

#### Scenario: 流中途失败不落库
- **WHEN** 对话网关在增量下发中断开
- **THEN** 系统应下发 `error` 事件并结束流，数据库中不应出现该轮 assistant 消息，学生消息按设计保留

#### Scenario: 流开始前依赖故障返回 503
- **WHEN** 检索阶段嵌入或向量库不可用
- **THEN** 系统应返回 HTTP 503 JSON 错误而非 SSE 流

### Requirement: 先检索后生成
每次回答 MUST 先用本轮问题检索学生所属班级的知识库（沿用第 4 课 hybrid 检索与阈值），检索 MUST 只使用会话班级；会话历史仅用于理解追问且检索 MUST NOT 使用历史问题。

#### Scenario: 每轮先检索本班
- **WHEN** 学生在会话中提交问题
- **THEN** 生成前应先执行本班 hybrid 检索，命中切片进入上下文，他班内容不得出现

#### Scenario: 追问只检索最新问题
- **WHEN** 学生在同一会话中基于上文追问
- **THEN** 检索查询应只包含本轮问题文本，历史消息只作为对话上下文传给模型

### Requirement: 班级与身份边界
会话、提示词与技能的 class_id MUST 只从已认证主体获得，客户端提交的任何班级或身份字段 MUST 被忽略；跨班访问对外表现 MUST 与「没有这条数据」一致。

#### Scenario: 请求体班级字段无效
- **WHEN** 请求体中携带 `classId` 等字段
- **THEN** 系统应忽略该字段，作用范围不超出会话班级

#### Scenario: 未登录与无效 token
- **WHEN** 未携带或携带无效 Bearer 令牌请求助手接口
- **THEN** 系统应返回 401 `{"message":"未登录"}`

### Requirement: OpenAPI 与 Swagger 文档
系统 SHALL 提供 OpenAPI 描述与浏览器可访问的 Swagger UI，文档 MUST 覆盖路径、参数、请求/响应结构、错误码、登录要求与 Bearer 认证方式，并标明流式接口的响应类型与事件格式。

#### Scenario: 浏览器打开 Swagger
- **WHEN** 浏览器访问 Swagger UI 页面
- **THEN** 页面应能加载 OpenAPI 描述并展示全部主要接口（登录、检索、提示词、技能、会话、发送消息）

#### Scenario: 配置 Bearer 试调
- **WHEN** 在 Swagger 中填入登录获得的 token 并试调受保护接口
- **THEN** 请求应携带 `Authorization: Bearer` 头且返回与接口实际行为一致的结果

### Requirement: 接口自动化测试
仓库 SHALL 包含按 URL 路径组织的 `apitest/`，`run_all.py` MUST 一键执行全部用例并生成 HTML 报告，报告目录 MUST 被 Git 忽略；用例 MUST 同时核对状态码、响应内容与数据库一致性，并覆盖登录、检索、提示词/技能、创建会话、发送消息、未授权、无检索命中与跨班边界。

#### Scenario: 一键执行与报告
- **WHEN** 运行 `apitest/run_all.py`
- **THEN** 系统应执行全部接口用例，生成含每条用例通过/失败与必要失败信息的 HTML 报告，且该报告不被提交到 Git

#### Scenario: 数据库一致性核对
- **WHEN** 用例保存提示词或发送消息成功
- **THEN** 用例应查询数据库确认落库内容与接口响应一致

#### Scenario: 边界与权限覆盖
- **WHEN** 执行完整用例集
- **THEN** 应包含错误凭据被拒、缺少令牌 401、学生改提示词/技能 403、跨班会话 404、空问题 400、SSE 逐段接收与完整回答等断言
