# 设计

## 技术栈与取代关系
- 延续 Java 21 + Spring Boot 3.3 + Spring Security 6、Vue 3 + Vite；仅新增 JWT 依赖 `io.jsonwebtoken:jjwt` 0.12.x（`jjwt-api` 编译期 + `jjwt-impl`/`jjwt-jackson` 运行期）。
- 不引入 spring-security-oauth2-resource-server（对单体会话替换过重），保持既有自定义过滤器架构。
- **取代** `add-auth-class-isolation-materials/design.md` 的「登录态明确采用服务端会话（session + HttpOnly Cookie）」决策；该历史 change 文档不修改。

## 总体数据流

```
登录：POST /api/auth/login {username,password}
  → AuthService 校验 BCrypt 密码（规则不变，失败 401）
  → JwtService.generateToken(userId)   # HS256, sub=userId, exp=可配
  → 200 { token, user }

访问受保护接口：Authorization: Bearer <token>
  → JwtAuthenticationFilter：解析 token 取 userId → userRepository.findById
    → 重建 CampusUserPrincipal 设入 SecurityContext（与旧 SessionAuthenticationFilter 同构）
  → token 缺失/非法 → 不设认证，由 RestAuthenticationEntryPoint 返回 401

登出：POST /api/auth/logout → 204（服务端 no-op）；前端清除本地 token
```

## 关键决策

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| JWT 库 | jjwt 0.12.x 三件套 | 成熟主流、API 简洁；不引 OAuth2 资源服务器 |
| Claim | 只存 `sub = userId`（+ 标准 `exp`/`iat`），**每请求回源查库**重建 principal | 与旧 SessionAuthenticationFilter 行为完全一致：角色/班级变更即时生效，班级隔离的 `classId` 永远新鲜；claim 不堆业务字段 |
| 签名算法 | HS256（对称，单服务端校验） | 单体应用无需 RS256 的公私钥分离 |
| 登录响应 | 新 DTO `LoginResponse { token, user }` | token 放 body 避免 CORS expose-header 问题；`GET /me` 仍返回 `UserResponse` |
| 过期时间 | `JWT_EXPIRATION_MINUTES` 默认 720 分钟（12 小时） | 覆盖一个教学日；无 refresh token，旧容器 30 分钟会话过短、24 小时放大失窃窗口 |
| 密钥 | `JWT_SECRET` 必填且 ≥32 字节，缺失/过短**启动失败**（`JwtService` 构造校验抛 `IllegalStateException`） | 对齐既有「密钥只放服务端环境变量」规则，不提供不安全默认值 |
| CORS | 移除 `setAllowCredentials(true)`，保留 `Authorization` 头放行 | token 方案不依赖 cookie，无需 credentials |
| 前端存储 | localStorage key `campusclaw-token` | 刷新可恢复登录态；XSS 风险见边界声明 |
| 检索 change | 不修改 | 其「class_id 只从已认证会话获得」语义由认证主体（CampusUserPrincipal）满足 |

## 后端组件变更

### 新增
- `security/JwtService.java`
  - `generateToken(Long userId)`：HS256 签发，`sub` = userId，`exp` = now + 配置分钟数。
  - `parseUserId(String token)`：校验签名与过期，返回 userId；任何解析失败抛 401 语义异常（`ResponseStatusException(UNAUTHORIZED, "未登录")`）。
  - 构造函数校验：密钥非空且 `getBytes(UTF_8).length >= 32`，否则 `IllegalStateException`（应用启动失败，日志不含密钥值）。
- `security/JwtAuthenticationFilter.java`（`OncePerRequestFilter`）
  - 读 `Authorization` 头，`Bearer ` 前缀校验；缺失或非法**不设认证、不中断链**，交给入口点返回 401。
  - 解析成功 → `userRepository.findById` → `SecurityUtils`（新方法仅设 SecurityContextHolder）写入上下文。
- `dto/LoginResponse.java`：`record LoginResponse(String token, UserResponse user)`。

### 删除
- `security/SessionAuthenticationFilter.java`（含 `SESSION_USER_ID` 常量）。
- `SecurityUtils.saveAuthentication`（含 `HttpSessionSecurityContextRepository` 写会话逻辑）。
- `AuthService.logout(HttpSession)`。

### 修改
- `config/SecurityConfig.java`
  - `SessionCreationPolicy.IF_REQUIRED` → `STATELESS`。
  - 过滤器换挂 `new JwtAuthenticationFilter(userRepository, jwtService)`。
  - CORS 移除 `setAllowCredentials(true)`。
- `service/AuthService.java`：`login(LoginRequest)` 去掉 `HttpSession` 参数，只校验密码返回 principal。
- `web/AuthController.java`
  - `POST /login`：调 `authService.login` 后 `jwtService.generateToken`，返回 `LoginResponse`；不再写 SecurityContext（后续请求带 token）。
  - `POST /logout`：不取 session，直接 204。
  - `GET /me`：不变。
- `application.yml`
  - 删 `server.servlet.session.cookie.*`（无会话即惰性配置）。
  - 加 `app.auth.jwt-secret: ${JWT_SECRET:}`、`app.auth.jwt-expiration-minutes: ${JWT_EXPIRATION_MINUTES:720}`。
- `pom.xml`：加 jjwt 三件套。

### 不动
`CampusUserPrincipal`、`SecurityUtils.currentPrincipal`、`RestAuthenticationEntryPoint`、`RestAccessDeniedHandler`、`@PreAuthorize("hasRole('TEACHER')")`、`MaterialService` 班级隔离、BCrypt、登录接口路径。

## 前端组件变更
- 新增 `src/token.js`：`getToken()/setToken()/clearToken()`（localStorage `campusclaw-token`），零依赖（避免 api ↔ auth 循环引用）。
- `src/api.js`
  - `request()` 自动注入 `Authorization: Bearer <token>`（无 token 不加）。
  - 删除 `credentials: 'include'`。
  - 响应 401 且**非登录接口**时：`clearToken()` + `window.dispatchEvent(new Event('campusclaw:unauthorized'))`（事件解耦 api 与 router 的循环依赖；`/api/auth/login` 的 401 属凭据错误，由登录页显示，不触发跳转）。
- `src/auth.js`
  - `login()`：解构 `{ token, user }`，`setToken(token)` 后赋 `authState.user`。
  - `logout()`：finally 中 `clearToken()`。
  - `fetchMe()`：本地无 token 直接置 `anonymous`（省一次请求）。
- `src/App.vue`：`onMounted` 挂 `campusclaw:unauthorized` 监听 → 重置 authState → 跳登录页（保留 `redirect`）；`onBeforeUnmount` 摘除。
- `src/views/LoginView.vue`：调用在 auth.js 内收敛，预计无需改动。
- `src/vite.config.js`：注释「登录 Cookie 天然同源」→「token 经 Authorization 头透传」。
- `nginx.conf`：不改（`proxy_set_header` 未限制，Authorization 默认透传）。

## 边界与风险（明确声明）
- **XSS 与 localStorage**：token 可被同页执行的 JS 读取，弱于 HttpOnly Cookie。接受该取舍：同源单页、无第三方脚本注入、Vue 模板默认转义、密钥类数据仍只在服务端。更强方案（内存态 + 刷新重登）与 refresh token 列为 Non-goal。
- **不可吊销**：失窃 token 在过期前持续有效。缓解：12 小时过期 + 登出清本地；吊销名单列为 Non-goal。
- **会话防护的消失**：会话固定攻击随无状态天然消失；CSRF 在无 cookie 携带凭证后不成立（`csrf.disable()` 与 SameSite 策略维持原状）。
- **兼容**：`add-traceable-vector-retrieval` change 不改，其「已认证会话」措辞由本方案认证主体满足。

## 测试策略
- `EndToEndFlowTest`：`login()` 改从响应 JSON 取 `token`，后续请求 `.header("Authorization", "Bearer …")`；401/403/隔离断言全部保留。
- `AuthServiceTest`：删 session 断言 → 断言密码校验行为；新增 JwtService 单测（签发/解析/过期/篡改/密钥长度校验）。
- 新增边界用例：无 Authorization 头 → 401；格式错误 → 401；过期（负过期签发）→ 401；篡改签名 → 401；有效 token `/me` → 200；登出后原 token 仍有效（验证 no-op 边界）。
- `MaterialServiceTest`：不动（手工构造 Authentication）。
