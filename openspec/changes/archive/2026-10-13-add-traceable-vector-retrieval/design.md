# 设计

## 技术栈与范围
- 延续 Java 21 + Spring Boot 3（Web、Security、Data JPA）、MySQL 8 + Flyway、Vue 3 + Vue Router + Vite、Docker Compose。
- 新增 Qdrant 8（向量库）；嵌入模型与对话模型经由 OpenAI 兼容 HTTP 网关调用，只从 Spring Boot 服务端访问。
- 本变更不改登录、角色、材料上传与既有班级隔离接口，只在其后追加切分、索引、检索与问答能力。
- README 与本仓库主规格原把“检索问答”列为非目标；本 change 合并后即为新的范围边界，不回改已归档 change。

## 数据分别存哪里
- **MySQL 是正文真相源**：原始正文、切片文本、切片序号、字符范围、班级与材料关联都在 MySQL；摘录一律回 MySQL 读取，不信任 Qdrant payload 中的任何文本。
- **MySQL 全文索引**：`knowledge_chunks.chunk_text` 上建 `FULLTEXT ... WITH PARSER ngram`，`ngram_token_size = 2`（实例级参数，Compose 的 MySQL 配置需显式声明）。
- **Qdrant 只存向量和编号**：集合 `campusclaw_chunks`，距离 `Cosine`，不保存 `chunk_text`。
- **ID 一致性**：Qdrant point ID = `knowledge_chunks.id`（主键自增值），payload `chunk_id` 同值；payload 至少包含 `class_id`、`material_id`、`knowledge_entry_id`、`chunk_id`、`chunk_index`。
- **命中回表**：向量命中后必须携带**会话** `class_id` 回 MySQL 取正文并复核 `index_status = ready`；同一 `chunk_id` 若不属于本班或非 ready，直接丢弃该命中。

## 表结构（Flyway 迁移 `V2__retrieval.sql`）

```sql
-- 材料正文条目（1 材料 ↔ 1 正文）
CREATE TABLE knowledge_entries (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    material_id   BIGINT NOT NULL,
    class_id      BIGINT NOT NULL,
    body_text     LONGTEXT NOT NULL,        -- 解析后的原始正文，预处理不改写本列
    char_count    INT NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_entries_material (material_id),
    KEY idx_knowledge_entries_class (class_id),
    CONSTRAINT fk_entries_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    CONSTRAINT fk_entries_class FOREIGN KEY (class_id) REFERENCES class_rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 可检索切片
CREATE TABLE knowledge_chunks (
    id                  BIGINT NOT NULL AUTO_INCREMENT,   -- 即 Qdrant point ID
    knowledge_entry_id  BIGINT NOT NULL,
    material_id         BIGINT NOT NULL,
    class_id            BIGINT NOT NULL,
    chunk_index         INT NOT NULL,                     -- 0 起始顺序
    chunk_text          MEDIUMTEXT NOT NULL,
    start_offset        INT NOT NULL,                     -- 预处理后文本中的起止
    end_offset          INT NOT NULL,
    index_status        VARCHAR(16) NOT NULL,             -- pending / ready / failed
    failure_reason      VARCHAR(500),                     -- 仅 failed 时有值
    strategy            VARCHAR(16) NOT NULL,             -- auto / custom / hierarchy
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_chunks_class_status (class_id, index_status),
    KEY idx_chunks_material (material_id, chunk_index),
    FULLTEXT KEY ft_chunks_text (chunk_text) WITH PARSER ngram,
    CONSTRAINT fk_chunks_entry FOREIGN KEY (knowledge_entry_id) REFERENCES knowledge_entries(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_class FOREIGN KEY (class_id) REFERENCES class_rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- 状态机：`pending`（已切片、待向量化）→ `ready`（向量已写入 Qdrant）/ `failed`（嵌入或 Qdrant 写入失败，记录 `failure_reason`）。
- `failed` 切片不参与任何检索；`failure_reason` 只存错误摘要，不存密钥与上游响应原文。
- 索引状态与失败原因按课件要求落在 `knowledge_chunks` 上；材料级是否就绪由其下切片状态聚合派生，不另存冗余字段。

## 三种切分策略

| 策略 | 规则 | 适用场景 |
| --- | --- | --- |
| `auto`（默认） | 最大 800 字、重叠 80 字；断点优先级：空行 > 换行 > 句号（`。！？.!?` 等句末标点）；窗口内无断点时硬切 | 普通讲义和规章正文 |
| `custom` | 最大长度 100～2000 字，重叠 0%～50%（由请求参数给出并做范围校验）；断点标识可选：换行、空行、句号；无断点时硬切 | 需要精确控制粒度的材料 |
| `hierarchy` | 按 Markdown `#`、`##`、`###` 分章并把标题行并入所属切片；单章超过 800 字时章内再按 `auto` 窗口切分 | 有标题层级的 Markdown |

- 未指定策略一律用 `auto`；种子/历史材料补索引也用 `auto`。
- 切分是**纯函数**：同一正文 + 同一策略参数 → 输出切片序列完全一致（单测覆盖确定性）。
- 每个切片的 `start_offset`/`end_offset` 为**预处理后文本**的字符区间，且必须满足 `body.substring(start, end) == chunk_text` 的一致性（由预处理后的副本身份保证）。

## 预处理边界
- 预处理（去 URL、去邮箱、合并空白）只作用于**送入切分器的临时副本**；`materials` 原文件和 `knowledge_entries.body_text` 一律不改写。
- 一旦预处理改变文本，切片偏移表达的是**预处理后文本**的位置，不是原文件下标；对外 DTO 中该字段标注为 `charRange`（基于切分文本），并在描述中说明可能与原文件有偏移，不冒充原文件定位。
- 返回给前端的 `excerpt` 直接取 `chunk_text`，不做二次高亮定位到原文件。

## 索引流水线与失败语义

```
保存材料 + 正文（knowledge_entries）
   → 切分写入 MySQL（index_status = pending）
   → 逐片调用嵌入模型
   → 逐片 upsert Qdrant（point id = chunk id）
   → 成功置 ready；失败置 failed + failure_reason
```

- **与上一 change 的边界差异（必须写明）**：材料与正文一旦保存成功，后续嵌入/Qdrant 失败**不回滚材料**，只把对应切片置 `failed`。失败切片不写半截向量——按“先嵌入成功、再 upsert、upsert 成功才置 ready”的顺序执行；嵌入失败则不触碰 Qdrant。
- 已入库材料**不会**因配置变化自动重切。
- **重建索引**（教师显式触发，单材料）：事务内删除该材料旧切片与对应 Qdrant point → 按本次指定策略重新切分入库 → 重新向量化。失败的旧主键不残留。
- **补索引**：对 `materials` 中尚无 `knowledge_entries` 的本班材料执行 `auto` 切分与向量化（覆盖历史/种子数据）。
- 全流程在 Spring Boot 服务端执行；上传接口在材料保存成功后同步触发索引（索引失败不阻断上传成功响应，切片状态如实记录）。

## Qdrant 客户端与启动校验
- 服务端持有唯一 Qdrant 客户端；地址 `QDRANT_URL`、集合 `QDRANT_COLLECTION`（默认 `campusclaw_chunks`）来自环境变量。
- **启动校验**：应用启动时读取 collection 信息，校验（1）collection 存在；（2）向量维度等于配置的 `EMBEDDING_DIM`；（3）距离为余弦。任一不符则启动失败并打印可读错误（不含密钥）。
- 服务启动时若 collection 不存在则按配置创建（维度 = `EMBEDDING_DIM`，距离 = Cosine）。

## 三种检索模式

| 模式 | 访问路径 | 排序与失败行为 |
| --- | --- | --- |
| `keyword` | 只查 MySQL `MATCH ... AGAINST`（带 `class_id` + `ready`），不调嵌入、不访问 Qdrant | 按全文相关度降序；Qdrant 完全不可用时照常工作 |
| `vector` | 问句嵌入 → Qdrant 按会话 `class_id` 过滤检索 → 回 MySQL 校验班级与 `ready` 取正文 | 余弦降序；阈值 0.35；嵌入失败或 Qdrant 不可用 → HTTP 503 |
| `hybrid`（默认） | 同时执行 keyword 与 vector 两路 | 两路先各按**绝对阈值**过滤（keyword 相关度阈值、vector 余弦阈值 0.35），再对过滤后的候选做 RRF 融合，`k = 60`；某一路查询成功但无命中时不加该路分数；某一路**依赖故障**（嵌入/Qdrant 不可用）→ HTTP 503 |

- RRF 公式：`score(d) = Σ 1/(k + rank_i(d))`，`k = 60`，`rank_i` 从 1 起；只对通过该路绝对阈值的文档累加。
- **全文分与余弦分含义不同，禁止直接相加**；RRF 只使用名次，不使用原始分数。
- 关键字查询的绝对阈值：`MATCH ... AGAINST`（自然语言模式）得分 **> 0**。勘误说明：MySQL ngram 语料下相关度天然极小（实测命中约 1e-8 量级），初稿取 1.0 会误杀全部真实命中，故阈值定为严格大于 0（`> 0`，不含 0 分行），常量集中在服务端配置类中。
- 空查询（`query` 去空白后为空）→ HTTP 400。
- 过滤后无候选 → HTTP 200、`hits: []`、附固定无依据提示（`basis: "none"`），**不得**用低于阈值的切片充数。
- 默认检索模式 `hybrid`；请求可显式指定 `keyword` / `vector` / `hybrid`。

## 统一结果 DTO（`/api/retrieval`）

```json
{
  "mode": "hybrid",
  "basis": "found | none",
  "message": "仅 basis=none 时出现，固定为「资料中未找到相关内容」",
  "hits": [
    {
      "materialId": 12,
      "materialTitle": "A 班｜分数加减法教案",
      "chunkId": 101,
      "chunkIndex": 2,
      "charRange": { "start": 840, "end": 1520, "basis": "chunk-text" },
      "excerpt": "……MySQL 中的 chunk_text 摘录（最多 300 字）…",
      "score": {
        "source": "keyword | vector | hybrid",
        "rank": 1,
        "detail": { "keyword": 3.1, "cosine": 0.62, "rrf": 0.0327 }
      },
      "openUrl": "/api/materials/12/content"
    }
  ]
}
```

- 只返回派生字段：**不含** `storage_key`、服务端磁盘路径、原始向量、`failure_reason`、其他班任何数据。
- `openUrl` 指向既有受保护材料接口（下载/打开原材料的入口）。
- 接口：`POST /api/retrieval`，请求体 `{ "query": "...", "mode": "hybrid", "topK": 10 }`（`mode` 缺省 hybrid，`topK` 缺省 10、上限 50）。

## 班级隔离
- `class_id` 一律取自已认证会话（`CampusUserPrincipal.getClassId()`）；请求体/查询参数中的 `classId` 字段**被忽略**（DTO 中干脆不定义该字段）。
- keyword：`WHERE class_id = ? AND index_status = 'ready' AND MATCH ...`。
- vector：Qdrant filter `must: class_id = 会话班级`；回表 `WHERE id IN (...) AND class_id = 会话班级 AND index_status = 'ready'`。
- 检索到只存在于其他班的内容 → HTTP 200、空 hits（**不用** 403/404 暗示他班存在该资料）。
- 按材料 ID 的既有访问规则不变：跨班与不存在均 404/403（延续 `MaterialService.findAuthorized` 行为）。
- 教师与学生都能检索本班材料；`reindex`、`backfill` 仅教师（沿用 `@PreAuthorize("hasRole('TEACHER')")` + 服务层双校验）。

## `/api/ask`：先检索、后生成

```json
// 请求
{ "question": "...", "history": [{ "role": "user|assistant", "content": "..." }] }  // history 可选，最多保留最近 4 条
// 响应
{ "answer": "……[1] ……[2] ……", "basis": "found",
  "citations": [ { "materialId": 12, "materialTitle": "…", "chunkId": 101, "chunkIndex": 2, "excerpt": "…" } ] }
```

- 检索固定复用 `hybrid` 模式取**本班前 4 条**切片，不另造检索方式。
- **有切片**：服务端把材料标题、切片编号、正文、用户问题交给对话模型；生成简短回答，要求用 `[1]`、`[2]` 引用，编号与 `citations` 数组顺序一一对应（`[1]` ↔ `citations[0]`）。
- **无切片**：直接返回固定句“资料中未找到相关内容”，`citations: []`，**不调用**对话模型。
- 客户端提交的 `system` 消息一律丢弃（DTO 只接受 `user`/`assistant`，未知 role 忽略该条）；系统提示由服务端拼装：角色约束 + 班级限定 + 切片材料 + “只依据材料回答”。
- `history` 只帮助理解追问（最多 4 条），**检索只用 `question` 最新问题**。
- 空 `question` → 400；对话模型不可用 → 503；不做流式输出。
- 模型无工具能力：不给它 DB/Qdrant 访问，上下文里也不含其他班资料。

## 网关与密钥（全部只在服务端）

| 变量 | 说明 | 示例占位 |
| --- | --- | --- |
| `QDRANT_URL` | Qdrant 内部地址 | `http://qdrant:6333` |
| `QDRANT_COLLECTION` | 集合名 | `campusclaw_chunks` |
| `EMBEDDING_BASE_URL` | 嵌入网关（OpenAI 兼容 `/embeddings`） | `https://embedding.example/v1` |
| `EMBEDDING_API_KEY` | 嵌入密钥 | `<your-embedding-key>` |
| `EMBEDDING_MODEL` | 嵌入模型名 | `text-embedding-3-small` |
| `EMBEDDING_DIM` | 向量维度（与 collection 一致，启动校验） | `1536` |
| `CHAT_BASE_URL` | 对话网关（OpenAI 兼容 `/chat/completions`） | `https://chat.example/v1` |
| `CHAT_API_KEY` | 对话密钥 | `<your-chat-key>` |
| `CHAT_MODEL` | 对话模型名 | `gpt-4o-mini` |

- 浏览器只与同源 `/api` 通信；nginx 不代理 Qdrant 与任何网关。
- 响应与日志不打印密钥、服务端路径、原始向量、他班正文。
- `.env.example` 只列变量名与占位值。

## 数据迁移
- `V2__retrieval.sql`：建 `knowledge_entries`、`knowledge_chunks`、ngram FULLTEXT 索引（无外键/索引语义变更，纯增量）。
- Flyway 对既有数据库自动执行；迁移后历史与种子材料处于“未切分”状态，由 `backfill` 补齐。
- MySQL 实例需 `ngram_token_size = 2`（Compose MySQL 配置中声明 `--ngram-token-size=2`）。
- 回滚：本 change 不提供自动回滚脚本；删除新表不影响旧表数据。

## 检索前端（既有 AppShell + 设计令牌）
- 新增路由 `/retrieval`（登录后可见，教师与学生皆可）。
- 页面结构：模式选择（`keyword` / `vector` / `hybrid` 单选，默认 hybrid）+ 问题输入 + 检索按钮。
- 状态分支：加载中 / 失败（503 显示“检索服务暂不可用”，400 显示空查询提示）/ 空结果（固定“资料中未找到相关内容”）/ 命中列表。
- 命中卡片：材料标题、切片序号、字符范围、excerpt、排序信息（模式与名次）、“打开原材料”链接（`openUrl`）。
- 问答区：`/api/ask` 的问题输入、回答（渲染 `[n]` 为角标）、`citations` 列表与材料跳转。
- 沿用既有 `btn` / `card` / `status-pill` / 暗色令牌；不引入新 UI 依赖。

## 测试拆分（与 tasks 对应）
- **单元**：三种切分策略（边界、重叠、标题保留、偏移一致性、确定性）、RRF 融合（单路命中、双路命中、名次计算）、阈值过滤、固定无依据提示。
- **服务层**：班级过滤（keyword SQL、Qdrant filter 构造、回表二次校验）、失败状态流转（嵌入失败 → failed、材料仍在、无半截向量）、重建清理旧数据。
- **集成（MockMvc + Testcontainers/Test profile）**：400 空查询、200 空 hits、503 降级（关断嵌入/Qdrant 模拟）、`/api/ask` 无依据短路（验证未调用对话模型）、引用编号顺序、学生越权 403、跨班 200 空命中、伪造 `classId` 被忽略。
- **一致性**：point ID == chunk id == payload `chunk_id`，维度与 collection 一致。
