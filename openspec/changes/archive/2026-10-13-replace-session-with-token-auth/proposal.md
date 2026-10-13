# 提案：登录认证从服务端会话改为 JWT Token

## Why
现有登录态依赖服务端会话（HttpSession + HttpOnly Cookie），前端只能靠 `credentials: 'include'` 隐式携带凭证，接口与客户端的耦合方式不透明，也不便被未来的非浏览器客户端复用。改为标准的 JWT + `Authorization: Bearer` 头后：认证凭证显式随请求携带、服务端无状态校验、前端登录态存取路径清晰，并为后续扩展（多端接入、网关鉴权）铺路。

## What
- **登录签发 token**：`POST /api/auth/login` 校验账号密码成功后签发 JWT，响应改为 `{ token, user }`；凭据无效仍返回 401。
- **Bearer 头携带**：受保护接口要求 `Authorization: Bearer <token>`；token 缺失、格式错误、过期或签名被篡改统一返回 401 `{"message":"未登录"}`。
- **无状态校验**：Spring Security 会话策略改为 `STATELESS`，不再创建 HttpSession；JWT 只含 `sub = userId`，过滤器每请求回源查库重建认证主体，角色与班级变更即时生效。
- **密钥管理**：签名密钥 `JWT_SECRET` 只从服务端环境变量注入，缺失或短于 32 字节时应用启动失败；过期时间 `JWT_EXPIRATION_MINUTES` 可配（默认 720 分钟）。
- **登出语义**：服务端无状态、不吊销 token，`POST /api/auth/logout` 保留为 204 no-op；前端登出即丢弃本地 token。
- **前端生命周期**：登录把 token 存入 localStorage（key `campusclaw-token`），每个请求注入 Authorization 头；任何接口返回 401 时清除 token 并引导回登录页；刷新页面凭本地 token 恢复登录态。
- **既有安全行为保持**：未登录 401、学生越权 403、班级隔离 `class_id` 只取自认证主体、`/health` 免登录、密钥不进响应与日志——全部延续不变。
- **取代旧设计决策**：本变更取代 `add-auth-class-isolation-materials/design.md` 中「登录态明确采用服务端会话（session + HttpOnly Cookie）」的设计决定；该历史 change 文档不修改，由本 change 声明取代关系。

## Capabilities
- `token-auth`

## Non-goals
- 不做 refresh token 与滑动续期。
- 不做服务端 token 吊销名单/黑名单；token 在过期前无法主动作废（靠过期时间兜底）。
- 不引入 OAuth2 授权框架、第三方 IdP 或 spring-security-oauth2-resource-server。
- 不修改登录接口路径（`POST /api/auth/login`）、密码哈希算法（BCrypt）与账号密码校验规则。
- 不修改 `add-traceable-vector-retrieval` 检索 change 的任何文档；其「class_id 只从已认证会话获得」的语义由本方案的认证主体满足。
- 不改 class04.md 课件原文（其「本课不做：不重写登录」为课件边界，本变更为项目自主决策并留痕）。
- 不做多因素认证、设备指纹或异地登录提醒。
