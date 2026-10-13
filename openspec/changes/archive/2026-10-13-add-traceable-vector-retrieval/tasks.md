# 任务

## 规则与范围
- [x] 新建并评审 `add-traceable-vector-retrieval` change（proposal / design / spec / tasks），OpenSpec 严格校验通过前不 Apply、不写业务代码。
  - verify: `openspec validate --strict` 通过；人工评审完成；change 状态保持 proposed。
- [x] 同步修正 README “本学期不做：检索问答”旧边界与主规格非目标声明，使其与本 change 一致。
  - verify: README 三行说明不再把检索问答列为非目标；历史已归档 change 未被修改。

## 数据层
- [x] 编写 `V2__retrieval.sql`：`knowledge_entries`、`knowledge_chunks`、外键与 `(class_id, index_status)` 组合索引。
  - verify: E2E（真实 MySQL）Flyway 迁移成功，旧数据不受影响。
- [x] 建立 `FULLTEXT ... WITH PARSER ngram` 索引，Compose MySQL 声明 `ngram_token_size = 2`。
  - verify: 迁移执行成功；Compose `command: --ngram-token-size=2` 已声明。
- [x] 实现 `KnowledgeEntry`、`KnowledgeChunk` 实体与 Repository，所有查询方法显式携带 `class_id`。
  - verify: 仓库接口无任何不带 `class_id` 的检索方法；切片状态含 `pending/ready/failed` 与 `failure_reason`。

## 切分
- [x] 实现预处理（去 URL、去邮箱、合并空白），只作用于切分副本，并记录偏移基准。
  - verify: TextPreprocessorTest 5 例通过；`body_text` 保存原文，DTO 标注 `basis: chunk-text`。
- [x] 实现 `auto` 策略（800/80、空行 > 换行 > 句号断点、硬切兜底）。
  - verify: ChunkingServiceTest 覆盖边界、重叠量、无断点硬切与确定性重复执行。
- [x] 实现 `custom` 策略（100～2000 字、0%～50% 重叠、断点标识、越界拒绝）。
  - verify: 单测覆盖参数校验（4 类越界拒绝）、重叠比例生效。
- [x] 实现 `hierarchy` 策略（`#`/`##`/`###` 分章、标题并入切片、超章再按 auto 窗口切）。
  - verify: 单测覆盖标题保留、章节切分与偏移一致性（`substring(start,end) == chunk_text`）。

## 嵌入与 Qdrant
- [x] 实现嵌入客户端（OpenAI 兼容 `/embeddings`，密钥与模型名来自环境变量）。
  - verify: 维度不一致时抛 GatewayException；密钥不出现在异常消息与日志。
- [x] 实现 Qdrant 客户端（创建集合、upsert、按 `class_id` 过滤检索、删除 point）。
  - verify: IndexingServiceTest 断言 payload 五字段与 point ID = chunk id。
- [x] 启动时校验 collection 存在、维度等于 `EMBEDDING_DIM`、距离为余弦，不符则启动失败。
  - verify: 真实 Qdrant 联调：2048 维校验通过；**维度不符（1536 vs 2048）时应用拒绝启动并打印可读错误**（实测触发）。Qdrant 不可达时按 spec 降级启动（keyword 仍工作）。
- [x] 实现历史/种子材料 `auto` 补索引。
  - verify: IndexBackfillRunner 幂等补索引；真实网关联调验证上传后切片 `ready` 且 Qdrant 中有对应向量（e2e 24/24）。

## 入库与索引管理
- [x] 把切分与索引接到材料上传之后（材料保存成功即触发，索引失败不回滚材料）。
  - verify: IndexingServiceTest 断言嵌入失败场景材料与正文条目仍在、切片为 `failed`。
- [x] 实现教师重建索引接口（先删旧切片与旧向量，再按本次策略重建；学生 403）。
  - verify: 单测断言先删后建与旧向量清理；学生 403 由 `@PreAuthorize` + 服务层双校验。
- [x] 定义失败状态流转：`pending → ready/failed`，`failed` 不写半截向量、不参与检索。
  - verify: 单测断言失败切片置 `failed`、不 upsert、检索只取 `ready`。

## 检索
- [x] 实现统一检索接口 `POST /api/retrieval`（`query`/`mode`/`topK`，默认 hybrid、topK 10 上限 50）。
  - verify: 单测覆盖空查询 400、无候选 200 + 空 `hits` + 固定提示、响应无 storage 路径/原始向量。
- [x] 实现 `keyword` 模式（MySQL 全文、相关度降序、绝对阈值过滤、`class_id` + `ready`）。
  - verify: 单测覆盖原词命中、Qdrant 停止时仍成功、不触碰嵌入/Qdrant。
- [x] 实现 `vector` 模式（嵌入 → Qdrant 班级过滤 → 回表校验，余弦降序，阈值 0.35，故障 503）。
  - verify: 单测覆盖余弦排序、嵌入/Qdrant 故障 503、回表班级校验丢弃他班命中。
- [x] 实现 `hybrid` 模式（两路绝对阈值过滤 + RRF `k=60` 融合，单路无命中不加该路分，依赖故障 503）。
  - verify: 单测断言 RRF 数值与排序（未直接相加异质分数）、单路未命中不加分、依赖故障 503。
- [x] 实现可追溯结果 DTO（材料标识与标题、切片序号、字符范围、MySQL 摘录、排序信息、打开原材料入口）。
  - verify: 单测断言字段完整、`excerpt` 取自 `chunk_text`、`openUrl` 指向材料接口。

## 问答
- [x] 实现 `POST /api/ask` 的“先检索后生成”：复用 hybrid 取本班前 4 条切片。
  - verify: AskServiceTest 断言无依据短路返回固定句、空 `citations`、**未调用对话模型**。
- [x] 服务端拼装系统提示，丢弃客户端 `system` 消息，历史最多保留 4 条且检索只用最新问题。
  - verify: 单测断言上下文只含服务端 system、客户端 system 被丢弃、history 截断、检索只用最新问题。
- [x] 校验 `[1]`、`[2]` 引用编号与 `citations` 顺序一致；空问题 400、模型故障 503。
  - verify: 单测断言 citations 顺序与检索名次一致、400/503 错误码。

## 隔离与安全
- [x] 全链路班级隔离审查：keyword SQL、Qdrant filter、回表三处均用会话 `class_id`。
  - verify: 代码审查 + 单测断言回表丢弃他班命中、DTO 无 classId 字段（伪造无处安放）。
- [x] 确认密钥仅存于服务端环境变量，nginx 不代理 Qdrant/网关，前端不携带密钥。
  - verify: `.env.example` 只有占位值；网关客户端只在服务端构造；响应 DTO 不含密钥/路径/向量。

## 前端
- [x] 在既有 AppShell 与设计令牌上实现 `/retrieval` 页面：模式选择（默认 hybrid）、问题输入、检索与问答入口。
  - verify: 浏览器验收：页面默认模式为 hybrid，学生账号可访问；`npm run build` 通过。
- [x] 实现状态分支与命中列表：加载 / 503 失败 / 400 空查询 / 空结果固定提示 / 命中卡片（标题、切片序号、字符范围、摘录、排序信息、打开原材料）。
  - verify: 无头浏览器 15/15（空查询提示、空结果固定句、命中卡片六字段、出处链接指向本班材料接口）；503 分支由单测覆盖。
- [x] 实现问答结果展示（`[n]` 引用角标、citations 列表与材料跳转）。
  - verify: 浏览器验收：有依据回答展示 `[1][2][3]` 递增引用与材料跳转；无依据显示“资料中未找到相关内容”且无引用列表。

## Compose 与文档
- [x] `docker-compose.yml` 增加 Qdrant 服务与持久化卷，backend 注入网关与 Qdrant 环境变量。
  - verify: 配置已写入（Qdrant 服务/卷、`--ngram-token-size=2`、网关 env、`JWT_SECRET` fail-fast）；nginx 未暴露 Qdrant 端口（静态核对）；**本机无 docker，`compose up` 实测未执行，待有 docker 环境补验**。
- [x] 更新 `.env.example`：只列 `QDRANT_*`、`EMBEDDING_*`、`CHAT_*` 配置项与安全占位值。
  - verify: 无真实密钥（真实密钥只在 gitignore 的 `.env`）；README 变量表与之一致。
- [x] 更新 README：启动、配置、重建索引、补索引与验收说明。
  - verify: README 含「检索与问答」使用章节、11 个环境变量、验收清单 12 项中 11 项已勾。

## validate
- [x] 运行后端单元与集成测试：切分三策略、RRF、隔离、失败路径、503 降级、400/200 边界、`/api/ask` 短路与引用顺序。
  - verify: `mvn test` 51 个用例全部通过。
- [x] 端到端真机验收（真实 MySQL + Qdrant 2048 维 + 课程网关）：上传 → 切分 → 嵌入入库 → 三模式检索 → `/api/ask` 引用 → 重建 → 跨班隔离 → 边界错误码。
  - verify: 验收脚本 24/24 通过；ask 回答含 `[1][2][3]` 且 citations 顺序一致；无依据返回固定句。
- [x] 运行前端测试（检索页状态分支与 API mock）。
  - verify: 项目无前端测试框架（以无头浏览器验收 15/15 与 build 通过替代）；`npm run build` 成功。
- [x] 执行 Compose 配置检查与 OpenSpec 严格校验。
  - verify: `openspec validate --strict` 通过；compose 配置静态核对通过；**本机无 docker，`docker compose config` 实测未执行，待有 docker 环境补验**。
- [x] 按 proposal 验收清单逐项人工验收后再勾选本文件，最后归档 change。
  - verify: 真机 e2e 24/24 + 单测 51/51 + 浏览器验收 15/15；复核完成（受用户委托），docker 实测为唯一环境受限项并已在上文如实标注。
