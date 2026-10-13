价值主张：为校园教学提供安全的班级化知识库协作。
核心场景：教师/学生登录、教师上传、班级隔离、材料入库与列表、可追溯检索与有依据问答、Compose 启动和 /health。
本学期不做：开放式长对话、作业提交。

## 技术栈

- 后端：Java 21 + Spring Boot 3（Spring Security + JWT 无状态认证，Bearer 头携带；BCrypt 密码哈希）、MySQL 8、Flyway
- 检索：MySQL ngram 全文索引 + Qdrant 向量索引（余弦），支持关键字 / 向量 / 混合（RRF）三种模式；问答为“先检索、后生成”，无依据时固定回答不编造
- 前端：Vue 3 + Vue Router + Vite
- 部署：Docker Compose（前端 nginx + 后端 + MySQL），数据库与材料文件均挂载持久化 volume

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

**无状态认证边界**：登出只清除前端本地 token，服务端不维护吊销名单——已签发 token 在过期（默认 12 小时）前仍有效，属已声明设计边界（见 `openspec/changes/replace-session-with-token-auth`）。
