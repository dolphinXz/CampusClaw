package com.campusclaw.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * JwtService 纯单测：签发/解析往返、过期、篡改签名、密钥长度校验。
 * 不起 Spring 上下文，不依赖 MySQL。
 */
class JwtServiceTest {

    private static final String SECRET = "campusclaw-test-secret-0123456789abcdef";

    @Test
    void generateAndParseRoundtrip() {
        JwtService service = new JwtService(SECRET, 720);

        String token = service.generateToken(42L);

        assertThat(service.parseUserId(token)).isEqualTo(42L);
    }

    @Test
    void expiredTokenIsRejected() {
        // 用同一密钥手工签发一个已过期的 token
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("42")
                .issuedAt(new Date(System.currentTimeMillis() - 3_600_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        JwtService service = new JwtService(SECRET, 720);

        assertThatThrownBy(() -> service.parseUserId(expired))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tamperedSignatureIsRejected() {
        JwtService service = new JwtService(SECRET, 720);
        String token = service.generateToken(42L);
        // 换错误密钥重新签名 = 签名被篡改
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "another-secret-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
        String tampered = Jwts.builder()
                .subject("42")
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(wrongKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.parseUserId(tampered))
                .isInstanceOf(ResponseStatusException.class);
        // 原 token 解析不受影响
        assertThat(service.parseUserId(token)).isEqualTo(42L);
    }

    @Test
    void malformedTokenIsRejected() {
        JwtService service = new JwtService(SECRET, 720);

        assertThatThrownBy(() -> service.parseUserId("not-a-jwt"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void missingSecretFailsStartup() {
        assertThatThrownBy(() -> new JwtService("", 720))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void shortSecretFailsStartup() {
        assertThatThrownBy(() -> new JwtService("too-short", 720))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
