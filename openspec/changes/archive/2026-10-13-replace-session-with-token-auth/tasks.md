# 任务

## 规则与范围
- [x] 新建 change `replace-session-with-token-auth`（.openspec.yaml / proposal / design / specs/token-auth/spec.md / tasks），status: proposed。
  - verify: `openspec validate replace-session-with-token-auth --strict` 通过；未写任何业务代码。
- [x] proposal 声明取代 `add-auth-class-isolation-materials/design.md` 的会话决策，并确认检索 change 无需修改；历史 change 与 class04.md 均不改动。
  - verify: 两个历史 change 目录与 class04.md 无 diff。

## 后端依赖与配置
- [x] `pom.xml` 引入 jjwt 0.12.x（jjwt-api 编译 + jjwt-impl/jjwt-jackson 运行期）。
  - verify: `mvn test` 编译通过，无 oauth2 相关依赖。
- [x] `application.yml` 增加 `app.auth.jwt-secret`（`${JWT_SECRET:}`）与 `app.auth.jwt-expiration-minutes`（`${JWT_EXPIRATION_MINUTES:720}`），删除 `server.servlet.session.cookie.*` 段。
  - verify: 无残留 session 配置；配置键与 design 一致。

## 后端认证核心
- [x] 实现 `JwtService`（HS256 签发 `sub=userId`、解析校验、构造期密钥非空且 ≥32 字节校验，错误消息不含密钥）。
  - verify: 密钥缺失/过短时 Bean 创建失败、应用启动失败（JwtServiceTest 覆盖）。
- [x] 实现 `JwtAuthenticationFilter` 并删除 `SessionAuthenticationFilter`。
  - verify: `grep -r "HttpSession\|getSession\|SESSION_USER_ID" backend/src/main` 零命中（仅剩一条说明注释）。
- [x] `SecurityUtils`：删除 `saveAuthentication`（会话写入），新增仅设 `SecurityContextHolder` 的方法。
  - verify: 代码中无 `HttpSessionSecurityContextRepository` 引用。
- [x] `AuthService.login` 去掉 `HttpSession` 参数；删除 `logout(HttpSession)`。
  - verify: 密码校验与 401「账号或密码错误」行为不变。
- [x] `AuthController`：login 返回 `LoginResponse(token, user)` 且不再写 SecurityContext；logout 无逻辑直接 204；/me 不变。
  - verify: 登录响应 jsonPath 含 `$.token`；响应头无 `Set-Cookie`（E2E 断言）。
- [x] `SecurityConfig`：`STATELESS`、换挂 `JwtAuthenticationFilter`、移除 `setAllowCredentials(true)`。
  - verify: 无 `IF_REQUIRED`/`allowCredentials` 残留；permitAll 列表（/health、/error、POST login/logout）与 401/403 出口不变。

## 后端测试
- [x] 改造 `AuthServiceTest`（去 MockHttpSession 断言，只测密码校验）。
  - verify: 2 个用例通过。
- [x] 新增 `JwtServiceTest`（roundtrip、过期、篡改签名、格式非法、缺/短密钥六类，纯单测不起 Spring 上下文）。
  - verify: 6 个用例通过，无 MySQL 也能运行。
- [x] 改造 `EndToEndFlowTest`：`login()` 从响应 JSON 取 token，全部 `.session(...)` 改为 `.header("Authorization", "Bearer …")`。
  - verify: 既有 401/403/上传/隔离断言全部保持并通过。
- [x] 新增 token 边界用例：无头 401、格式错误 401、篡改 401、过期 401、有效 token 无 Cookie 连续请求 200、登出后旧 token 到期前仍有效。
  - verify: `mvn test` 12 个用例全绿（含 MySQL 可用时的 E2E）。
- [x] `MaterialServiceTest` 确认无需改动。
  - verify: 仍通过。

## 前端
- [x] 新增 `src/token.js`（localStorage `campusclaw-token` 存取，零依赖叶子模块）。
  - verify: 全仓 localStorage 认证读写只此一处。
- [x] `api.js`：注入 `Authorization: Bearer`、删除 `credentials: 'include'`、401（登录接口除外）清 token 并派发 `campusclaw:unauthorized` 事件。
  - verify: 端到端断言所有业务请求带 Authorization、无 Cookie；密码错误的登录 401 不触发跳转（19/19）。
- [x] `auth.js`：fetchMe 无 token 快路径、login 解构 `{token, user}` 存 token、logout finally 清 token。
  - verify: 刷新恢复登录态；无 token 访问受保护路由直达登录页。
- [x] `App.vue` 挂载 `campusclaw:unauthorized` 监听 → 重置 authState → 跳登录页（带 redirect），卸载时摘除。
  - verify: 端到端断言篡改 token 后回登录页且 token 被清。
- [x] 更新 `vite.config.js` 「Cookie 天然同源」注释；确认 nginx Authorization 默认透传。
  - verify: 注释与实现一致；nginx 无需修改（proxy_set_header 未限制 Authorization）。

## 配置与文档
- [x] `.env.example` 增加 `JWT_SECRET`（≥32 字节占位与生成命令注释）、`JWT_EXPIRATION_MINUTES=720`。
  - verify: 无真实密钥；留空强制用户自行生成。
- [x] `docker-compose.yml` backend 注入 `JWT_SECRET: ${JWT_SECRET:?JWT_SECRET must be set}` 与过期配置。
  - verify: 配置已写入（静态核对 compose L50 fail-fast）；**本机无 docker，`docker compose config` 与缺密钥拒绝启动未实测**，待有 docker 环境补验。
- [x] `README.md`：L7 技术栈行改「JWT 登录（Authorization 头）」、环境变量表加两行、测试描述与验证清单同步（含登出不吊销边界）。
  - verify: README 不再出现「会话登录」；变量表与 .env.example 一致。

## validate 与验收
- [x] `openspec validate replace-session-with-token-auth --strict` 复验。
  - verify: 通过；change 保持 proposed 直至归档。
- [x] 运行 `cd backend && mvn test` 与前端构建。
  - verify: 12 个测试全绿（JwtServiceTest 6 / AuthServiceTest 2 / MaterialServiceTest 2 / EndToEndFlowTest 2）；`npm run build` 成功。
- [x] 端到端手工验收：登录 → 刷新恢复 → 无 JSESSIONID → 篡改 token 401 清除回登录 → 登出清本地 → 教师上传、学生 403、跨班 403 回归（E2E 测试覆盖）。
  - verify: 浏览器端到端脚本 19/19 通过；响应与日志无密钥。
- [x] 逐项勾选 tasks 后归档 change。
  - verify: 复核完成（受用户委托）；`openspec validate --strict` 通过；docker compose 实测为唯一环境受限项，已在上文如实标注后归档（status → archived）。
