package com.campusclaw.security;

import com.campusclaw.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Authorization: Bearer 头解析 JWT 并重建认证主体。
 * token 缺失或非法时不设认证、不中断过滤器链，
 * 由 RestAuthenticationEntryPoint 统一返回 401。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                String token = header.substring(BEARER_PREFIX.length());
                try {
                    Long userId = jwtService.parseUserId(token);
                    userRepository.findById(userId).ifPresent(user ->
                            SecurityUtils.setAuthentication(CampusUserPrincipal.from(user))
                    );
                } catch (RuntimeException ignored) {
                    // 非法/过期 token：不设认证，交给入口点统一 401
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
