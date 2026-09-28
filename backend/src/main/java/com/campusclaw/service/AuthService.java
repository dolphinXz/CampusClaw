package com.campusclaw.service;

import com.campusclaw.dto.LoginRequest;
import com.campusclaw.repository.UserRepository;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public CampusUserPrincipal login(LoginRequest request, HttpSession session) {
        var user = userRepository.findByUsername(request.username().trim())
                .orElseThrow(this::badCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw badCredentials();
        }

        session.setAttribute(SessionAuthenticationFilter.SESSION_USER_ID, user.getId());
        return CampusUserPrincipal.from(user);
    }

    public void logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
    }

    private ResponseStatusException badCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
    }
}
