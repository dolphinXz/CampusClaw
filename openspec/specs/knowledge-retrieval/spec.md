# knowledge-retrieval Specification

## Purpose
在既有登录、班级隔离和材料入库能力之上，为本班材料提供可追溯的关键字、向量与混合检索，以及“先检索、后生成”的有依据问答；检索不到可靠依据时固定返回“资料中未找到相关内容”。

## Requirements

### Requirement: 材料正文切分与三种策略
系统 SHALL 把材料正文切成可检索片段，支持 `auto`、`custom`、`hierarchy` 三种切分策略，并记录切片序号与字符范围。

#### Scenario: 默认使用 auto 策略
- **WHEN** 上传或补索引时未指定切分策略
- **THEN** 系统应使用 `auto`（最大约 800 字、重叠 80 字，优先在空行、换行或句号处断开）

#### Scenario: custom 策略参数校验
- **WHEN** 教师指定 `custom` 策略
- **THEN** 系统应校验最大长度在 100～2000 字、重叠在 0%～50% 范围内，超范围拒绝并提示

#### Scenario: hierarchy 策略按标题分章
- **WHEN** 对含 `#`、`##`、`###` 标题的 Markdown 材料使用 `hierarchy` 策略
- **THEN** 系统应按标题分章并把标题行保留在所属切片中，章节超过自动窗口长度时再按 `auto` 窗口切分

#### Scenario: 切分结果可重复验证
- **WHEN** 用同一正文与同一策略参数重复执行切分
- **THEN** 两次输出的切片序列应完全一致

### Requirement: 预处理不改写原文
系统 MUST 只对送入切分器的临时副本做预处理，不得改写原文件与 `body_text`，偏移语义必须如实标注。

#### Scenario: 预处理作用于副本
- **WHEN** 正文包含 URL、邮箱或多余空白并执行预处理
- **THEN** `materials` 原文件与 `knowledge_entries.body_text` 应保持不变，切片偏移指向预处理后文本

#### Scenario: 偏移不冒充原文件下标
- **WHEN** 预处理改变了文本
- **THEN** 返回的字符范围应标注基于切分文本，不得宣称是原文件字符位置

### Requirement: 入库索引流水线
系统 SHALL 在材料与正文保存成功后执行“切分写 MySQL → 嵌入 → 写 Qdrant”，且材料不因索引失败而丢失。

#### Scenario: 正常入库
- **WHEN** 教师上传的材料解析出正文且嵌入与 Qdrant 写入成功
- **THEN** 切片应以 `ready` 状态写入 MySQL，Qdrant 中存在对应向量

#### Scenario: 嵌入失败保留材料
- **WHEN** 材料与正文已保存但嵌入调用失败
- **THEN** 材料与正文仍应存在，对应切片标记为 `failed` 并记录失败原因，Qdrant 中不写入半截向量

#### Scenario: 失败切片不参与检索
- **WHEN** 存在 `index_status = failed` 的切片
- **THEN** 任何检索模式都不得返回该切片

### Requirement: 中文全文索引
系统 SHALL 为切片正文建立中文全文索引，并只检索本班就绪切片。

#### Scenario: 建立 ngram 全文索引
- **WHEN** 执行数据库迁移
- **THEN** `chunk_text` 应存在 `FULLTEXT ... WITH PARSER ngram` 索引，ngram token 长度为 2

#### Scenario: 关键字查询限定班级与状态
- **WHEN** 执行关键字检索
- **THEN** 查询必须携带会话 `class_id` 且只读取 `index_status = ready` 的切片

### Requirement: 向量索引一致性
系统 SHALL 在 Qdrant 中只存向量与关联编号，并保证与 MySQL 切片标识一致。

#### Scenario: 集合配置
- **WHEN** 应用启动并创建或校验集合
- **THEN** 集合 `campusclaw_chunks` 应使用余弦距离，向量维度等于配置的 `EMBEDDING_DIM`，维度不符则启动失败

#### Scenario: point ID 与 payload 一致
- **WHEN** 切片写入 Qdrant
- **THEN** point ID、`knowledge_chunks.id` 与 payload 的 `chunk_id` 应相同，payload 还应包含 `class_id`、`material_id`、`knowledge_entry_id`、`chunk_index`

#### Scenario: Qdrant 不保存正文
- **WHEN** 向量写入完成
- **THEN** Qdrant 中不得保存 `chunk_text` 或其他正文内容

### Requirement: 关键字检索模式
系统 SHALL 提供只查询 MySQL 全文索引的 `keyword` 检索模式。

#### Scenario: 关键字命中排序
- **WHEN** 用户以 `keyword` 模式提交包含原词的查询
- **THEN** 系统应按全文相关度降序返回本班就绪切片，且不调用嵌入模型、不访问 Qdrant

#### Scenario: Qdrant 不可用时关键字仍工作
- **WHEN** Qdrant 服务不可用
- **THEN** `keyword` 检索仍应成功返回结果

### Requirement: 向量检索模式
系统 SHALL 提供“嵌入 → Qdrant 班级过滤 → 回表取正文”的 `vector` 检索模式。

#### Scenario: 同义改写命中
- **WHEN** 用户以 `vector` 模式提交与材料语义相近的改写查询
- **THEN** 系统应按余弦相似度降序返回本班切片

#### Scenario: 向量模式依赖故障返回 503
- **WHEN** 嵌入调用失败或 Qdrant 不可用
- **THEN** `vector` 模式应返回 HTTP 503，不返回部分或编造结果

#### Scenario: 回表再次校验班级与状态
- **WHEN** Qdrant 命中切片并回 MySQL 取正文
- **THEN** 回表查询必须携带会话 `class_id` 并只接受 `ready` 切片，不一致的命中应丢弃

### Requirement: 混合检索模式与 RRF 融合
系统 MUST 默认使用 `hybrid` 模式：两路各按绝对阈值过滤后用 RRF 融合排序，不得直接相加异质分数。

#### Scenario: 两路绝对阈值过滤
- **WHEN** 同时执行关键字与向量检索
- **THEN** 每一路应先按各自绝对阈值（向量余弦阈值 0.35）过滤，再进入融合

#### Scenario: RRF 融合排序
- **WHEN** 过滤后两路均有候选
- **THEN** 系统应按 `score = Σ 1/(60 + rank)` 融合排序，不得把全文相关度与余弦相似度直接相加

#### Scenario: 单路未命中不加该路分数
- **WHEN** 某一路查询成功但无命中
- **THEN** 融合时只累加另一路分数，结果仍返回 HTTP 200

#### Scenario: 混合模式依赖故障返回 503
- **WHEN** 向量路依赖（嵌入或 Qdrant）不可用
- **THEN** `hybrid` 模式应返回 HTTP 503

### Requirement: 查询边界与空结果语义
系统 MUST 对空查询与无依据结果采用固定、不编造的响应。

#### Scenario: 空查询返回 400
- **WHEN** `query` 去除空白后为空
- **THEN** 系统应返回 HTTP 400

#### Scenario: 无候选返回空命中与固定提示
- **WHEN** 检索执行成功但过滤后没有候选
- **THEN** 系统应返回 HTTP 200、空 `hits` 与固定无依据提示“资料中未找到相关内容”，不得返回低于阈值的切片

### Requirement: 可追溯结果结构
每条检索结果 MUST 能回到原材料，且摘录来自 MySQL。

#### Scenario: 结果字段完整
- **WHEN** 检索返回命中
- **THEN** 每条命中至少包含材料标识与标题、切片序号、字符范围、MySQL 正文摘录、最终排序信息和打开原材料的入口

#### Scenario: 不泄露内部数据
- **WHEN** 检索结果序列化返回
- **THEN** 响应不得包含服务端存储路径、原始向量、失败原因或任何他班数据

### Requirement: 检索班级隔离
`class_id` MUST 只从已认证会话获得，三处查询全部执行班级过滤。

#### Scenario: 伪造班级编号被忽略
- **WHEN** 请求体、查询参数、表单或请求头中携带伪造的 `class_id`
- **THEN** 系统应忽略或拒绝该值，检索范围不得超出会话班级

#### Scenario: 三处班级过滤
- **WHEN** 执行关键字查询、Qdrant 过滤与回表查询
- **THEN** 三处都必须使用会话中的 `class_id` 作为条件

#### Scenario: 他班内容表现为无命中
- **WHEN** A 班会话检索只存在于 B 班的内容
- **THEN** 系统应返回 HTTP 200 与空 `hits`，不得用 403/404 暗示 B 班存在该资料

#### Scenario: 按材料 ID 访问延续既有规则
- **WHEN** 用户按材料 ID 跨班或访问不存在的材料
- **THEN** 系统应延续既有规则返回 404/403

### Requirement: 检索角色权限
系统 SHALL 允许教师和学生检索本班材料；索引管理 MUST 只属于教师。

#### Scenario: 学生检索
- **WHEN** 学生提交本班材料检索
- **THEN** 系统应正常返回结果

#### Scenario: 学生重建索引被拒
- **WHEN** 学生调用重建或补索引接口
- **THEN** 系统应返回 403

### Requirement: 显式重建与补索引
系统 SHALL 支持教师显式重建单材料索引或补齐本班历史材料索引，重建后旧切片与旧向量 MUST 不残留。

#### Scenario: 重建先删后建
- **WHEN** 教师对已入库材料触发重建索引
- **THEN** 系统应先删除该材料旧切片与旧向量，再按本次策略生成新索引，不留下失效主键

#### Scenario: 配置变化不自动重切
- **WHEN** 切分配置发生变化
- **THEN** 已入库材料的既有索引不应被自动重切，仅在教师显式重建时更新

#### Scenario: 补齐历史材料索引
- **WHEN** 教师触发本班补索引
- **THEN** 对尚无切分记录的材料应以 `auto` 策略完成切分与向量化

### Requirement: 有依据才生成回答
`POST /api/ask` MUST 先用混合检索获取本班前 4 条切片，有依据才调用对话模型。

#### Scenario: 有依据生成并按序引用
- **WHEN** 混合检索返回至少 1 条切片
- **THEN** 系统应把材料标题、切片编号、正文与用户问题交给对话模型生成简短回答，`[1]`、`[2]` 等编号与 `citations` 数组顺序一一对应

#### Scenario: 无依据短路
- **WHEN** 混合检索没有返回任何切片
- **THEN** 系统应直接返回“资料中未找到相关内容”与空 `citations`，不调用对话模型

#### Scenario: 丢弃客户端 system 消息
- **WHEN** 客户端提交包含 `system` 角色的消息
- **THEN** 系统应丢弃该消息，系统提示由服务端生成

#### Scenario: 检索只用最新问题
- **WHEN** 请求携带历史消息
- **THEN** 历史仅用于理解追问，检索必须只使用最新 `question`

#### Scenario: 空问题与模型故障
- **WHEN** `question` 为空，或对话模型不可用
- **THEN** 分别返回 HTTP 400 与 HTTP 503

### Requirement: 模型与密钥仅限服务端
嵌入模型、对话模型和 Qdrant MUST 只由 Spring Boot 服务端访问。

#### Scenario: 密钥来自环境变量
- **WHEN** 服务端调用嵌入或对话网关
- **THEN** 地址、模型名与密钥应来自服务端环境变量，日志与响应不得泄露密钥

#### Scenario: 浏览器不可直连
- **WHEN** 检视前端与网关配置
- **THEN** 浏览器只能访问同源 `/api`，不得直接访问 Qdrant 或携带任何网关密钥

### Requirement: 检索界面
前端 SHALL 提供可切换模式的检索页面，展示加载、失败、空结果、命中列表与出处跳转。

#### Scenario: 模式选择与默认值
- **WHEN** 用户打开检索页面
- **THEN** 页面应提供 `keyword`、`vector`、`hybrid` 三种模式选择，默认 `hybrid`

#### Scenario: 命中展示与出处
- **WHEN** 检索返回命中
- **THEN** 界面应展示材料标题、切片序号、字符范围、摘录与排序信息，并提供打开原材料的入口

#### Scenario: 状态分支
- **WHEN** 检索处于加载、依赖故障、空查询或空结果状态
- **THEN** 界面应分别展示加载中、失败提示、空查询提示与固定无依据提示“资料中未找到相关内容”

#### Scenario: 问答结果展示
- **WHEN** `/api/ask` 返回有依据回答
- **THEN** 界面应渲染回答与 `[n]` 引用，并展示 citations 列表及材料跳转入口
