package com.campusclaw.web;

import com.campusclaw.dto.LoginRequest;
import com.campusclaw.dto.LoginResponse;
import com.campusclaw.dto.UserResponse;
import com.campusclaw.security.JwtService;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var principal = authService.login(request);
        // 无状态认证：签发 JWT 随响应体返回，后续请求凭 Authorization 头携带
        String token = jwtService.generateToken(principal.getId());
        return LoginResponse.from(token, principal);
    }

    /**
     * 无状态语义下的登出占位：服务端不维护可吊销状态，
     * 客户端登出时清除本地 token 即可（旧 token 在过期前仍有效，属已声明边界）。
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        // no-op
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return UserResponse.from(SecurityUtils.currentPrincipal(authentication));
    }
}
