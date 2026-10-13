package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.LoginRequest;
import com.campusclaw.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    @Test
    void loginReturnsPrincipal() {
        UserRepository userRepository = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User("teacher-a", encoder.encode("Teacher@123"), "教师 A", UserRole.TEACHER, new ClassRoom("CLASS_A", "A 班"));
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findByUsername("teacher-a")).thenReturn(Optional.of(user));

        AuthService authService = new AuthService(userRepository, encoder);

        var principal = authService.login(new LoginRequest("teacher-a", "Teacher@123"));

        assertThat(principal.getUsername()).isEqualTo("teacher-a");
        assertThat(principal.getId()).isEqualTo(1L);
    }

    @Test
    void loginRejectsWrongPassword() {
        UserRepository userRepository = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User("teacher-a", encoder.encode("Teacher@123"), "教师 A", UserRole.TEACHER, new ClassRoom("CLASS_A", "A 班"));
        when(userRepository.findByUsername("teacher-a")).thenReturn(Optional.of(user));

        AuthService authService = new AuthService(userRepository, encoder);

        assertThatThrownBy(() -> authService.login(new LoginRequest("teacher-a", "wrong")))
                .hasMessageContaining("账号或密码错误");
    }
}
