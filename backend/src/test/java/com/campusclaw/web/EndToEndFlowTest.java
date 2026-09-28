package com.campusclaw.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.domain.Material;
import com.campusclaw.repository.MaterialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端流程测试：登录、角色拒绝、班级隔离、上传入库与列表可见。
 * 需要可用的 MySQL（本地默认 campusclaw 库，或 `docker compose up -d db`）。
 */
@SpringBootTest(properties = {
        "app.material.storage-dir=${java.io.tmpdir}/campusclaw-e2e-materials",
        "app.seed.enabled=true"
})
@AutoConfigureMockMvc
class EndToEndFlowTest {

    private static final String TEACHER_A = "teacher-a";
    private static final String TEACHER_A_PASSWORD = "Teacher@123";
    private static final String TEACHER_B = "teacher-b";
    private static final String TEACHER_B_PASSWORD = "TeacherB@123";
    private static final String STUDENT_A1 = "student-a1";
    private static final String STUDENT_A1_PASSWORD = "Student@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaterialRepository materialRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv().getOrDefault("DB_URL",
                "jdbc:mysql://localhost:3306/campusclaw?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC");
        String username = System.getenv().getOrDefault("DB_USERNAME", "campusclaw");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "change-me");
        boolean reachable;
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            reachable = connection.isValid(3);
        } catch (Exception ex) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "MySQL 不可用，跳过端到端测试");
    }

    @Test
    void fullFlowCoversLoginRoleDenialIsolationUploadAndList() throws Exception {
        // 1. 健康检查免登录
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // 2. 未登录访问受保护接口返回 401
        mockMvc.perform(get("/api/materials"))
                .andExpect(status().isUnauthorized());

        // 3. 错误密码登录被拒绝
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + TEACHER_A + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        // 4. 教师与学生都能登录
        MockHttpSession teacherASession = login(TEACHER_A, TEACHER_A_PASSWORD);
        mockMvc.perform(get("/api/auth/me").session(teacherASession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(TEACHER_A))
                .andExpect(jsonPath("$.role").value("TEACHER"));

        MockHttpSession studentSession = login(STUDENT_A1, STUDENT_A1_PASSWORD);
        mockMvc.perform(get("/api/auth/me").session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(STUDENT_A1))
                .andExpect(jsonPath("$.role").value("STUDENT"));

        // 5. 学生上传被服务端拒绝（403）
        mockMvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "student.txt", "text/plain", "no".getBytes()))
                        .param("title", "E2E 学生越权上传")
                        .session(studentSession))
                .andExpect(status().isForbidden());

        // 6. 教师上传入库
        mockMvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "e2e-note.txt", "text/plain", "e2e 内容".getBytes()))
                        .param("title", "E2E 教师上传材料")
                        .param("description", "端到端测试上传")
                        .session(teacherASession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("E2E 教师上传材料"))
                .andExpect(jsonPath("$.uploadedByName").value("教师 A"));

        try {
            // 7. 列表可见且只包含本班记录
            List<String> classATitles = listTitles(teacherASession);
            assertThat(classATitles).contains("E2E 教师上传材料");
            assertThat(classATitles).allSatisfy(title -> assertThat(title).doesNotContain("B 班"));
            assertThat(classATitles).allSatisfy(title -> assertThat(title).doesNotContain("E2E B 班"));

            // 8. 班级隔离：B 班材料对 A 班用户不可见，直接访问被拒绝
            MockHttpSession teacherBSession = login(TEACHER_B, TEACHER_B_PASSWORD);
            mockMvc.perform(multipart("/api/materials")
                            .file(new MockMultipartFile("file", "b-note.txt", "text/plain", "b".getBytes()))
                            .param("title", "E2E B 班材料")
                            .session(teacherBSession))
                    .andExpect(status().isOk());

            long classBMaterialId = materialRepository.findAll().stream()
                    .filter(material -> "E2E B 班材料".equals(material.getTitle()))
                    .findFirst()
                    .orElseThrow()
                    .getId();

            List<String> classBTitles = listTitles(teacherBSession);
            assertThat(classBTitles).contains("E2E B 班材料");
            assertThat(classBTitles).doesNotContain("E2E 教师上传材料");

            mockMvc.perform(get("/api/materials/" + classBMaterialId).session(studentSession))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/materials/" + classBMaterialId + "/content").session(studentSession))
                    .andExpect(status().isForbidden());
        } finally {
            // 清理本次测试写入的材料记录（存储文件保留在临时目录）
            List<Material> leftovers = materialRepository.findAll().stream()
                    .filter(material -> material.getTitle().startsWith("E2E"))
                    .toList();
            materialRepository.deleteAll(leftovers);
        }
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private List<String> listTitles(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/materials").session(session))
                .andExpect(status().isOk())
                .andReturn();
        // MockHttpServletResponse 默认按 ISO-8859-1 解码，这里强制 UTF-8 以正确读取中文
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.get("title").asText())
                .toList();
    }
}
