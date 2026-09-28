价值主张：为校园教学提供安全的班级化知识库协作。
核心场景：教师/学生登录、教师上传、班级隔离、材料入库与列表、可追溯检索与有依据问答、Compose 启动和 /health。
本学期不做：开放式长对话、作业提交。

## 技术栈

- 后端：Java 21 + Spring Boot 3（Spring Security 会话登录 + BCrypt 密码哈希）、MySQL 8、Flyway
- 前端：Vue 3 + Vue Router + Vite
- 部署：Docker Compose（前端 nginx + 后端 + MySQL），数据库与材料文件均挂载持久化 volume

## 快速启动（Docker Compose）

```bash
cp .env.example .env      # 按需修改 DB_PASSWORD 等密钥
docker compose up --build
```

启动后访问 http://localhost （前端 nginx 会把 `/api`、`/health` 反向代理到后端）。
重建容器后，数据库数据（`db-data` volume）和已上传材料（`material-data` volume）仍然保留。

## 本地开发

1. 准备 MySQL 8：建库 `campusclaw`，并创建同名用户（密码与环境变量一致）。
2. 启动后端（需要 JDK 21 + Maven）：

   ```bash
   cd backend
   DB_URL="jdbc:mysql://localhost:3306/campusclaw?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC" \
   DB_USERNAME=campusclaw DB_PASSWORD=change-me \
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
| `MATERIAL_STORAGE_DIR` | 材料文件存储目录 | `./data/materials` |
| `SEED_ENABLED` | 是否写入种子数据 | `true` |
| `FRONTEND_ORIGIN` | 允许的前端来源（CORS） | `http://localhost:5173` |

密码只保存 BCrypt 哈希，密钥只从服务端环境变量读取，不写入代码仓库。

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

- 单元测试：登录会话写入、错误密码拒绝、学生上传拒绝、跨班材料访问拒绝。
- 端到端测试（`EndToEndFlowTest`）：健康检查免登录、未登录 401、教师/学生登录、学生上传 403、教师上传入库、本班列表可见、跨班访问 403。
  需要可用的 MySQL（本地默认 `campusclaw` 库，或先 `docker compose up -d db`）；数据库不可用时该测试会跳过。

## 验证清单

- [x] 教师/学生登录；未登录访问受保护页面被引导到登录页（前端路由守卫 + 后端 401）
- [x] 教师可上传材料，学生上传返回 403（服务端 `@PreAuthorize` + 服务层双重校验）
- [x] 班级隔离在服务端查询层强制 `class_id` 过滤，跨班访问返回拒绝状态
- [x] 材料写入知识库后，本班材料列表能查到该记录
- [x] 密码只保存 BCrypt 哈希；密钥只来自服务端环境变量
- [x] `docker compose up` 启动应用与数据库，volume 持久化数据库与材料文件
- [x] `GET /health` 免登录返回服务状态
