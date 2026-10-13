# Spec Delta

## Purpose
把登录认证从服务端会话改为 JWT 无状态认证：登录签发 token、受保护接口经 `Authorization: Bearer` 头携带、服务端只校验签名与过期，既有 401/403 与班级隔离行为保持不变。

## ADDED Requirements

### Requirement: 登录签发 token
系统 SHALL 在账号密码校验通过后签发 JWT 并随登录响应体返回；凭据无效 MUST 返回 401 且不签发 token。

#### Scenario: 登录成功返回 token
- **WHEN** 教师或学生提交有效账号和密码
- **THEN** 系统应返回 200，响应体包含 `token` 与 `user` 用户信息

#### Scenario: 密码错误不签发
- **WHEN** 用户提交错误密码
- **THEN** 系统应返回 401「账号或密码错误」，响应体不包含 token

#### Scenario: 登录不建立会话
- **WHEN** 登录成功后检查响应
- **THEN** 响应不得包含 `Set-Cookie` 会话 Cookie，后续请求只凭 Authorization 头即可访问

### Requirement: Bearer 头认证
受保护接口 MUST 通过 `Authorization: Bearer <token>` 头识别身份，系统 MUST NOT 依赖服务端会话；token 缺失、格式错误、过期或签名无效 MUST 返回 401 `{"message":"未登录"}`。

#### Scenario: 无 Authorization 头
- **WHEN** 未携带 Authorization 头请求受保护接口
- **THEN** 系统应返回 401 与固定消息「未登录」

#### Scenario: 有效 token 访问
- **WHEN** 携带有效 `Authorization: Bearer <token>` 请求 `GET /api/auth/me`
- **THEN** 系统应返回 200 与该用户信息

#### Scenario: 篡改或过期 token
- **WHEN** 携带签名被篡改、已过期或格式非法的 token 请求受保护接口
- **THEN** 系统应返回 401 且不返回任何受保护数据

#### Scenario: 无会话连续请求
- **WHEN** 登录后不携带任何 Cookie、仅凭 token 连续请求多个受保护接口
- **THEN** 每次请求都应独立通过认证

### Requirement: token 密钥与过期管理
签名密钥 MUST 只来自服务端环境变量且长度不小于 32 字节，不满足时应用 MUST 启动失败且错误信息不得包含密钥内容。

#### Scenario: 密钥缺失或过短
- **WHEN** `JWT_SECRET` 为空或短于 32 字节
- **THEN** 应用应启动失败，错误消息可读且不包含密钥值

#### Scenario: 过期时间可配
- **WHEN** 配置 `JWT_EXPIRATION_MINUTES`
- **THEN** 签发 token 的过期时间应等于该配置，未配置时使用默认值 720 分钟

#### Scenario: 密钥不泄露
- **WHEN** 检视 HTTP 响应、日志与前端代码
- **THEN** 均不得出现签名密钥；浏览器只持有自己的 token

### Requirement: 无状态登录态
安全过滤链 MUST 以 STATELESS 策略运行，不创建 HttpSession；登出 MUST 为服务端 204 no-op 且客户端清除本地 token。

#### Scenario: 不创建会话
- **WHEN** 用户登录并访问受保护接口
- **THEN** 服务端不应创建 HttpSession，SecurityContext 不落会话存储

#### Scenario: 登出清除本地 token
- **WHEN** 客户端调用 `POST /api/auth/logout`
- **THEN** 系统应返回 204，客户端清除本地 token 并回到未认证状态

#### Scenario: 登出不吊销已签发 token（边界）
- **WHEN** 登出后仍使用此前签发的 token 请求受保护接口
- **THEN** 该 token 在过期前仍应通过校验（服务端不维护吊销名单）

### Requirement: 既有安全行为保持
认证机制替换 SHALL 不改变既有安全行为：未登录 401、学生越权 403、`class_id` MUST 只取自已认证主体、`/health` 保持免登录。

#### Scenario: 角色授权不变
- **WHEN** 学生调用上传接口或访问教师专属能力
- **THEN** 系统应仍返回 403

#### Scenario: 班级隔离取值不变
- **WHEN** 认证主体访问材料或检索
- **THEN** `class_id` 应取自认证主体（每请求回源查库重建），请求参数或请求头中的伪造 `class_id` 仍被忽略

#### Scenario: 用户被删除后 token 失效
- **WHEN** token 中的用户已被删除
- **THEN** 回源查库应失败，请求返回 401

#### Scenario: 健康检查免认证
- **WHEN** 未携带任何凭证请求 `GET /health`
- **THEN** 系统应返回成功状态

### Requirement: 前端 token 生命周期
前端 SHALL 把 token 存入 localStorage 并在每个 API 请求注入 Authorization 头；除登录接口外任何接口返回 401 时 MUST 清除本地 token 并引导回登录页。

#### Scenario: 登录存储与请求注入
- **WHEN** 用户登录成功
- **THEN** token 应存入 localStorage，之后每个 `/api` 请求都携带 `Authorization: Bearer` 头

#### Scenario: 刷新恢复登录态
- **WHEN** 用户刷新页面
- **THEN** 路由守卫应凭本地 token 请求 `/api/auth/me` 恢复登录态

#### Scenario: 过期自动退出
- **WHEN** token 过期后任一接口返回 401（登录接口除外）
- **THEN** 前端应清除本地 token、置为未认证并跳转登录页（保留 redirect 参数）

#### Scenario: 登出清除
- **WHEN** 用户点击退出登录
- **THEN** 本地 token 应被清除并跳转登录页
