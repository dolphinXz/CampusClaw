# 任务

## 规则与范围
- [x] 新建并评审 `add-traceable-vector-retrieval` change（proposal / design / spec / tasks），OpenSpec 严格校验通过前不 Apply、不写业务代码。
  - verify: `openspec validate --strict` 通过；人工评审完成；change 状态保持 proposed。
- [x] 同步修正 README “本学期不做：检索问答”旧边界与主规格非目标声明，使其与本 change 一致。
  - verify: README 三行说明不再把检索问答列为非目标；历史已归档 change 未被修改。

## 数据层
- [ ] 编写 `V2__retrieval.sql`：`knowledge_entries`、`knowledge_chunks`、外键与 `(class_id, index_status)` 组合索引。
  - verify: 在既有库与全新库上 Flyway 均能迁移成功，旧数据不受影响。
- [ ] 建立 `FULLTEXT ... WITH PARSER ngram` 索引，Compose MySQL 声明 `ngram_token_size = 2`。
  - verify: 迁移后 `MATCH(chunk_text) AGAINST(...)` 中文查询能命中；token 长度为 2。
- [ ] 实现 `KnowledgeEntry`、`KnowledgeChunk` 实体与 Repository，所有查询方法显式携带 `class_id`。
  - verify: 代码审查确认无任何不带 `class_id` 的检索方法；切片状态字段含 `pending/ready/failed` 与 `failure_reason`。

## 切分
- [ ] 实现预处理（去 URL、去邮箱、合并空白），只作用于切分副本，并记录偏移基准。
  - verify: 单测证明 `body_text` 与原文件未被改写；偏移标注为基于预处理后文本。
- [ ] 实现 `auto` 策略（800/80、空行 > 换行 > 句号断点、硬切兜底）。
  - verify: 单测覆盖边界、重叠量、无断点硬切与确定性重复执行。
- [ ] 实现 `custom` 策略（100～2000 字、0%～50% 重叠、断点标识、越界拒绝）。
  - verify: 单测覆盖参数校验、指定断点生效与无断点硬切。
- [ ] 实现 `hierarchy` 策略（`#`/`##`/`###` 分章、标题并入切片、超章再按 auto 窗口切）。
  - verify: 单测覆盖标题保留、章节切分与偏移一致性（`substring(start,end) == chunk_text`）。

## 嵌入与 Qdrant
- [ ] 实现嵌入客户端（OpenAI 兼容 `/embeddings`，密钥与模型名来自环境变量）。
  - verify: 单测用 stub 网关验证请求体与维度；密钥不出现在日志。
- [ ] 实现 Qdrant 客户端（创建集合、upsert、按 `class_id` 过滤检索、删除 point）。
  - verify: 单测验证 payload 字段（`class_id`/`material_id`/`knowledge_entry_id`/`chunk_id`/`chunk_index`）与 point ID = chunk id。
- [ ] 启动时校验 collection 存在、维度等于 `EMBEDDING_DIM`、距离为余弦，不符则启动失败。
  - verify: 人为改动维度后应用拒绝启动并打印可读错误（无密钥）。
- [ ] 实现历史/种子材料 `auto` 补索引。
  - verify: 对迁移后的种子材料触发补索引后，其切片变为 `ready` 且 Qdrant 中有对应向量。

## 入库与索引管理
- [ ] 把切分与索引接到材料上传之后（材料保存成功即触发，索引失败不回滚材料）。
  - verify: 上传成功后产生 `knowledge_entries` 与切片；嵌入失败场景材料仍在、切片为 `failed`。
- [ ] 实现教师重建索引接口（先删旧切片与旧向量，再按本次策略重建；学生 403）。
  - verify: 重建后无失效主键残留、旧向量被清理；学生调用返回 403。
- [ ] 定义失败状态流转：`pending → ready/failed`，`failed` 不写半截向量、不参与检索。
  - verify: 集成测试断言失败切片不出现在任何检索结果，Qdrant 无对应半成品 point。

## 检索
- [ ] 实现统一检索接口 `POST /api/retrieval`（`query`/`mode`/`topK`，默认 hybrid、topK 10 上限 50）。
  - verify: 空查询 400；无候选 200 + 空 `hits` + 固定提示；响应无 storage 路径/原始向量。
- [ ] 实现 `keyword` 模式（MySQL 全文、相关度降序、绝对阈值过滤、`class_id` + `ready`）。
  - verify: 原词查询命中；Qdrant 停止时关键字检索仍成功；伪造 `class_id` 被忽略。
- [ ] 实现 `vector` 模式（嵌入 → Qdrant 班级过滤 → 回表校验，余弦降序，阈值 0.35，故障 503）。
  - verify: 同义改写命中；关闭 Qdrant 或嵌入失败返回 503；回表二次班级校验生效。
- [ ] 实现 `hybrid` 模式（两路绝对阈值过滤 + RRF `k=60` 融合，单路无命中不加该路分，依赖故障 503）。
  - verify: 单测断言 RRF 公式与排序结果；断言全文分与余弦分未直接相加。
- [ ] 实现可追溯结果 DTO（材料标识与标题、切片序号、字符范围、MySQL 摘录、排序信息、打开原材料入口）。
  - verify: 每条命中断言字段完整且 `excerpt` 取自 `chunk_text`。

## 问答
- [ ] 实现 `POST /api/ask` 的“先检索后生成”：复用 hybrid 取本班前 4 条切片。
  - verify: 有切片时调用对话模型；无切片时返回固定句、空 `citations`，且未调用对话模型。
- [ ] 服务端拼装系统提示，丢弃客户端 `system` 消息，历史最多保留 4 条且检索只用最新问题。
  - verify: 请求携带 `system` 与历史时，实际发给模型的上下文只含服务端提示与最新问题检索结果。
- [ ] 校验 `[1]`、`[2]` 引用编号与 `citations` 顺序一致；空问题 400、模型故障 503。
  - verify: 集成测试断言编号映射与错误码。

## 隔离与安全
- [ ] 全链路班级隔离审查：keyword SQL、Qdrant filter、回表三处均用会话 `class_id`。
  - verify: A 班伪造 `class_id` 检索 B 班内容返回 200 + 空命中；按材料 ID 跨班仍 404/403。
- [ ] 确认密钥仅存于服务端环境变量，nginx 不代理 Qdrant/网关，前端不携带密钥。
  - verify: `.env.example` 只有占位值；浏览器网络面板只见同源 `/api`；响应与日志无密钥。

## 前端
- [ ] 在既有 AppShell 与设计令牌上实现 `/retrieval` 页面：模式选择（默认 hybrid）、问题输入、检索与问答入口。
  - verify: 页面默认模式为 hybrid，教师与学生均可访问。
- [ ] 实现状态分支与命中列表：加载 / 503 失败 / 400 空查询 / 空结果固定提示 / 命中卡片（标题、切片序号、字符范围、摘录、排序信息、打开原材料）。
  - verify: 各状态分支人工验收通过，出处链接可打开本班原材料。
- [ ] 实现问答结果展示（`[n]` 引用角标、citations 列表与材料跳转）。
  - verify: 有依据回答展示引用；无依据显示“资料中未找到相关内容”。

## Compose 与文档
- [ ] `docker-compose.yml` 增加 Qdrant 服务与持久化卷，backend 注入网关与 Qdrant 环境变量。
  - verify: `docker compose up --build` 启动成功；重建容器后索引数据仍在；nginx 未暴露 Qdrant 端口。
- [ ] 更新 `.env.example`：只列 `QDRANT_*`、`EMBEDDING_*`、`CHAT_*` 配置项与安全占位值。
  - verify: 无真实密钥；README 变量表与之一致。
- [ ] 更新 README：启动、配置、重建索引、补索引与验收说明。
  - verify: 按 README 从零启动可完成一次上传 → 检索 → 问答验收。

## validate
- [ ] 运行后端单元与集成测试：切分三策略、RRF、隔离、失败路径、503 降级、400/200 边界、`/api/ask` 短路与引用顺序。
  - verify: `mvn test` 全部通过。
- [ ] 运行前端测试（检索页状态分支与 API mock）。
  - verify: 前端测试全部通过。
- [ ] 执行 Compose 配置检查与 OpenSpec 严格校验。
  - verify: `docker compose config` 无误；`openspec validate --strict` 通过。
- [ ] 按 proposal 验收清单逐项人工验收后再勾选本文件，最后归档 change。
  - verify: 验收清单 12 项全部勾选；change 状态变为 archived。
