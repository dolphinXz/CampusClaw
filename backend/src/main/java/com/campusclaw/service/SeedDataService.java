package com.campusclaw.service;

import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.Material;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.repository.ClassRoomRepository;
import com.campusclaw.repository.MaterialRepository;
import com.campusclaw.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeedDataService implements ApplicationRunner {

    private final ClassRoomRepository classRoomRepository;
    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final MaterialStorageService storageService;
    private final MaterialParserService parserService;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;

    public SeedDataService(
            ClassRoomRepository classRoomRepository,
            UserRepository userRepository,
            MaterialRepository materialRepository,
            MaterialStorageService storageService,
            MaterialParserService parserService,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.enabled:true}") boolean seedEnabled
    ) {
        this.classRoomRepository = classRoomRepository;
        this.userRepository = userRepository;
        this.materialRepository = materialRepository;
        this.storageService = storageService;
        this.parserService = parserService;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!seedEnabled || userRepository.count() > 0) {
            return;
        }

        ClassRoom classA = classRoomRepository.save(new ClassRoom("CLASS_A", "A 班"));
        ClassRoom classB = classRoomRepository.save(new ClassRoom("CLASS_B", "B 班"));

        User teacherA = userRepository.save(new User(
                "teacher-a",
                passwordEncoder.encode("Teacher@123"),
                "教师 A",
                UserRole.TEACHER,
                classA
        ));
        User teacherB = userRepository.save(new User(
                "teacher-b",
                passwordEncoder.encode("TeacherB@123"),
                "教师 B",
                UserRole.TEACHER,
                classB
        ));
        userRepository.save(new User(
                "student-a1",
                passwordEncoder.encode("Student@123"),
                "学生 A1",
                UserRole.STUDENT,
                classA
        ));
        userRepository.save(new User(
                "student-b1",
                passwordEncoder.encode("StudentB@123"),
                "学生 B1",
                UserRole.STUDENT,
                classB
        ));

        materialRepository.save(createSample(
                teacherA,
                "A 班欢迎材料",
                "class-a-welcome.txt",
                "欢迎使用 A 班知识库。"
        ));
        materialRepository.save(createSample(
                teacherB,
                "B 班欢迎材料",
                "class-b-welcome.txt",
                "欢迎使用 B 班知识库。"
        ));
    }

    private Material createSample(User uploader, String title, String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var storedFile = storageService.store(fileName, "text/plain", bytes);
        return new Material(
                uploader.getClassRoom(),
                uploader,
                title,
                "示例材料",
                storedFile.key(),
                storedFile.fileName(),
                storedFile.contentType(),
                storedFile.sizeBytes(),
                parserService.parse(storedFile.contentType(), bytes)
        );
    }
}
