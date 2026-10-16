package com.campusclaw.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 全局配置：Bearer JWT 认证说明，供 Swagger UI 试调使用。 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI campusClawOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CampusClaw API")
                        .description("""
                                校园班级知识库接口。受保护接口通过 `Authorization: Bearer <token>` 头携带 \
                                登录签发的 JWT；`POST /api/sessions/{sessionId}/messages` 为 SSE 流式接口，\
                                响应类型 `text/event-stream`，事件顺序 `meta → delta × n → done`：\
                                `meta`（{"status","skill","citations"}，检索结束后最先发送）、\
                                `delta`（增量 {"text":"…"}）、\
                                `done`（{"messageId"}，持久化后发出）、\
                                `error`（{"message":"…"}，流开始后异常收尾）。
                                """)
                        .version("1.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
