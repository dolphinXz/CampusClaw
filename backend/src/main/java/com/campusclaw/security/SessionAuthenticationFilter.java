package com.campusclaw.security;

import com.campusclaw.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_USER_ID = "userId";

    private final UserRepository userRepository;

    public SessionAuthenticationFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(SESSION_USER_ID) instanceof Long userId) {
                userRepository.findById(userId).ifPresent(user -> {
                    SecurityUtils.saveAuthentication(CampusUserPrincipal.from(user), request, response);
                });
            }
        }

        filterChain.doFilter(request, response);
    }
}
