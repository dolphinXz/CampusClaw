package com.campusclaw.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.domain.Material;
import com.campusclaw.repository.MaterialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端流程测试：登录签发 token、Bearer 头认证、角色拒绝、班级隔离、上传入库与列表可见。
 * 需要可用的 MySQL（本地默认 campusclaw 库，或 `docker compose up -d db`）。
 */
@SpringBootTest(properties = {
        "app.material.storage-dir=${java.io.tmpdir}/campusclaw-e2e-materials",
        "app.seed.enabled=true",
        // 测试上下文自带密钥：生产环境缺失/过短密钥会启动失败（JwtService 构造校验）
        "app.auth.jwt-secret=campusclaw-test-secret-0123456789abcdef",
        // Qdrant 指向不可达地址：走降级启动路径（keyword 仍工作），测试与本机向量库状态解耦
        "app.retrieval.qdrant-url=http://localhost:65535"
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

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

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

        // 4. 教师与学生都能登录并签发 token
        String teacherToken = login(TEACHER_A, TEACHER_A_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(TEACHER_A))
                .andExpect(jsonPath("$.role").value("TEACHER"));

        String studentToken = login(STUDENT_A1, STUDENT_A1_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(STUDENT_A1))
                .andExpect(jsonPath("$.role").value("STUDENT"));

        // 5. 学生上传被服务端拒绝（403）
        mockMvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "student.txt", "text/plain", "no".getBytes()))
                        .param("title", "E2E 学生越权上传")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 6. 教师上传入库
        mockMvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "e2e-note.txt", "text/plain", "e2e 内容".getBytes()))
                        .param("title", "E2E 教师上传材料")
                        .param("description", "端到端测试上传")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("E2E 教师上传材料"))
                .andExpect(jsonPath("$.uploadedByName").value("教师 A"));

        try {
            // 7. 列表可见且只包含本班记录
            List<String> classATitles = listTitles(teacherToken);
            assertThat(classATitles).contains("E2E 教师上传材料");
            assertThat(classATitles).allSatisfy(title -> assertThat(title).doesNotContain("B 班"));
            assertThat(classATitles).allSatisfy(title -> assertThat(title).doesNotContain("E2E B 班"));

            // 8. 班级隔离：B 班材料对 A 班用户不可见，直接访问被拒绝
            String teacherBToken = login(TEACHER_B, TEACHER_B_PASSWORD);
            mockMvc.perform(multipart("/api/materials")
                            .file(new MockMultipartFile("file", "b-note.txt", "text/plain", "b".getBytes()))
                            .param("title", "E2E B 班材料")
                            .header("Authorization", "Bearer " + teacherBToken))
                    .andExpect(status().isOk());

            long classBMaterialId = materialRepository.findAll().stream()
                    .filter(material -> "E2E B 班材料".equals(material.getTitle()))
                    .findFirst()
                    .orElseThrow()
                    .getId();

            List<String> classBTitles = listTitles(teacherBToken);
            assertThat(classBTitles).contains("E2E B 班材料");
            assertThat(classBTitles).doesNotContain("E2E 教师上传材料");

            mockMvc.perform(get("/api/materials/" + classBMaterialId)
                            .header("Authorization", "Bearer " + studentToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/materials/" + classBMaterialId + "/content")
                            .header("Authorization", "Bearer " + studentToken))
                    .andExpect(status().isForbidden());
        } finally {
            // 清理本次测试写入的材料记录（存储文件保留在临时目录）
            List<Material> leftovers = materialRepository.findAll().stream()
                    .filter(material -> material.getTitle().startsWith("E2E"))
                    .toList();
            materialRepository.deleteAll(leftovers);
        }
    }

    @Test
    void tokenEdgeCasesReturn401AndStatelessHolds() throws Exception {
        // 格式非法的 token → 401
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        // 签名被篡改（换密钥重签）→ 401
        SecretKey forgedKey = Keys.hmacShaKeyFor(
                "forged-secret-0123456789abcdefghijkl".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("1")
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(forgedKey, Jwts.SIG.HS256)
                .compact();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());

        // 过期 token → 401（应用同一密钥、过期时间在过去的签名）
        SecretKey appKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("1")
                .issuedAt(new Date(System.currentTimeMillis() - 3_600_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(appKey, Jwts.SIG.HS256)
                .compact();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());

        // 登录响应不建立会话：无 Set-Cookie，且只凭 token 不带任何 Cookie 可连续访问
        String token = login(TEACHER_A, TEACHER_A_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 登出为 no-op：旧 token 到期前仍有效（无状态边界，已在 spec 声明）
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** 登录并返回响应体中的 token；登录响应不得携带 Set-Cookie 会话。 */
    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.get("token").asText();
    }

    private List<String> listTitles(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/materials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        // MockHttpServletResponse 默认按 ISO-8859-1 解码，这里强制 UTF-8 以正确读取中文
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.get("title").asText())
                .toList();
    }
}
