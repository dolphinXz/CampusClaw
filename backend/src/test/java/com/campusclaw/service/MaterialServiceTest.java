package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.Material;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.repository.MaterialRepository;
import com.campusclaw.repository.UserRepository;
import com.campusclaw.security.CampusUserPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class MaterialServiceTest {

    @Test
    void studentUploadIsRejected() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        MaterialStorageService storageService = mock(MaterialStorageService.class);
        MaterialParserService parserService = mock(MaterialParserService.class);

        ClassRoom classA = new ClassRoom("CLASS_A", "A 班");
        ReflectionTestUtils.setField(classA, "id", 1L);
        User studentA1 = new User("student-a1", "hash", "学生 A1", UserRole.STUDENT, classA);
        ReflectionTestUtils.setField(studentA1, "id", 3L);

        MaterialService service = new MaterialService(
                materialRepository,
                userRepository,
                storageService,
                parserService
        );
        CampusUserPrincipal principal = CampusUserPrincipal.from(studentA1);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        assertThatThrownBy(() -> service.upload(
                authentication,
                new MockMultipartFile("file", "sample.txt", "text/plain", "sample".getBytes()),
                "标题",
                "说明"
        )).hasMessageContaining("学生不能上传材料");
    }

    @Test
    void crossClassMaterialIsRejected() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        MaterialStorageService storageService = mock(MaterialStorageService.class);
        MaterialParserService parserService = mock(MaterialParserService.class);

        ClassRoom classA = new ClassRoom("CLASS_A", "A 班");
        ClassRoom classB = new ClassRoom("CLASS_B", "B 班");
        ReflectionTestUtils.setField(classA, "id", 1L);
        ReflectionTestUtils.setField(classB, "id", 2L);
        User teacherA = new User("teacher-a", "hash", "教师 A", UserRole.TEACHER, classA);
        User teacherB = new User("teacher-b", "hash", "教师 B", UserRole.TEACHER, classB);
        ReflectionTestUtils.setField(teacherA, "id", 1L);
        ReflectionTestUtils.setField(teacherB, "id", 2L);
        Material materialB = new Material(
                classB,
                teacherB,
                "B 班材料",
                "",
                "key",
                "b.txt",
                "text/plain",
                1,
                ""
        );

        when(materialRepository.findById(2L)).thenReturn(Optional.of(materialB));

        MaterialService service = new MaterialService(
                materialRepository,
                userRepository,
                storageService,
                parserService
        );
        CampusUserPrincipal principal = CampusUserPrincipal.from(teacherA);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        assertThatThrownBy(() -> service.get(2L, authentication))
                .hasMessageContaining("无权访问该材料");
    }
}
