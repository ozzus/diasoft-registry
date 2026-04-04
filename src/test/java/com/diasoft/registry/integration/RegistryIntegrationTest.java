package com.diasoft.registry.integration;

import com.diasoft.registry.DiasoftRegistryApplication;
import com.diasoft.registry.service.ImportJobService;
import com.diasoft.registry.service.OutboxPublisherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = DiasoftRegistryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.security.enabled=true",
                "app.runtime.mode=api",
                "spring.flyway.clean-disabled=false"
        }
)
@AutoConfigureMockMvc
class RegistryIntegrationTest {
    private static final UUID ITMO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BMSTU_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("diasoft_registry")
            .withUsername("registry")
            .withPassword("registry");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-10-13T13-34-11Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.object-storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.object-storage.bucket", () -> "diploma-imports");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.path-style-access", () -> "true");
        registry.add("app.object-storage.auto-create-bucket", () -> "true");
        registry.add("app.outbox.retry-backoff", () -> "PT0.1S");
        registry.add("app.outbox.retry-max-backoff", () -> "PT0.2S");
        registry.add("app.http.allowed-origins", () -> "http://localhost:5173");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ImportJobService importJobService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from audit_logs").update();
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from import_job_errors").update();
        jdbcClient.sql("delete from import_jobs").update();
        jdbcClient.sql("delete from revocations").update();
        jdbcClient.sql("delete from share_tokens").update();
        jdbcClient.sql("delete from verification_tokens").update();
        jdbcClient.sql("delete from diplomas").update();
        jdbcClient.sql("delete from students").update();
    }

    @Test
    void csvImportPersistsRowsAndPublishesDiplomaLifecycleEvent() throws Exception {
        String importJobId = createImport(
                ITMO_ID,
                "students.csv",
                "text/csv",
                """
                ФИО,номер_диплома,специальность,год_выпуска
                Ivan Petrov,D-2026-0001,Computer Science,2026
                """
        );

        assertThat(importJobService.processNextPendingImport()).isTrue();

        Map<String, Object> importJob = findImportJob(importJobId);
        assertThat(importJob.get("status")).isEqualTo("completed");
        assertThat(importJob.get("processed_rows")).isEqualTo(1);
        assertThat(importJob.get("failed_rows")).isEqualTo(0);
        assertThat(count("select count(*) from diplomas")).isEqualTo(1);
        assertThat(count("select count(*) from verification_tokens")).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where published = false")).isEqualTo(2);

        int published = outboxPublisherService.publishBatch(10);
        assertThat(published).isEqualTo(2);

        List<JsonNode> diplomaMessages = consumeTopic("diploma.lifecycle.v1", 1);
        assertThat(diplomaMessages).hasSize(1);
        JsonNode event = diplomaMessages.get(0);
        assertThat(event.path("event_type").asText()).isEqualTo("diploma.created.v1");
        assertThat(event.path("aggregate_type").asText()).isEqualTo("diploma");
        assertThat(event.path("payload").path("university_code").asText()).isEqualTo("ITMO");
        assertThat(event.path("payload").path("diploma_number").asText()).isEqualTo("D-2026-0001");
    }

    @Test
    void xlsxImportPersistsRowsAndUsesSameValidationContract() throws Exception {
        byte[] workbook = createWorkbook(List.of(
                List.of("ФИО", "номер_диплома", "специальность", "год_выпуска"),
                List.of("Ivan Petrov", "D-2026-0002", "Computer Science", "2026"),
                List.of("Maria Ivanova", "D-2026-0002", "Software Engineering", "2026"),
                List.of("Alex Smirnov", "", "Mathematics", "2026")
        ));

        String importJobId = createImport(ITMO_ID, "students.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        assertThat(importJobService.processNextPendingImport()).isTrue();

        Map<String, Object> importJob = findImportJob(importJobId);
        assertThat(importJob.get("status")).isEqualTo("partially_failed");
        assertThat(importJob.get("processed_rows")).isEqualTo(1);
        assertThat(importJob.get("failed_rows")).isEqualTo(2);

        List<String> errorCodes = jdbcClient.sql("""
                select code
                from import_job_errors
                where import_job_id = :importJobId
                order by row_number asc
                """)
                .param("importJobId", UUID.fromString(importJobId))
                .query(String.class)
                .list();

        assertThat(errorCodes).containsExactly("duplicate_diploma_number", "missing_required_field");
        assertThat(count("select count(*) from diplomas")).isEqualTo(1);
    }

    @Test
    void csvImportReportsStableErrorCodesForMalformedRows() throws Exception {
        String importJobId = createImport(
                ITMO_ID,
                "students.csv",
                "text/csv",
                """
                ФИО,номер_диплома,специальность,год_выпуска
                Ivan Petrov,D-2026-0003,Computer Science,2026
                Maria Ivanova,D-2026-0003,Software Engineering,2026
                Alex Smirnov,,Mathematics,2026
                """
        );

        assertThat(importJobService.processNextPendingImport()).isTrue();

        List<String> errorCodes = jdbcClient.sql("""
                select code
                from import_job_errors
                where import_job_id = :importJobId
                order by row_number asc
                """)
                .param("importJobId", UUID.fromString(importJobId))
                .query(String.class)
                .list();

        assertThat(errorCodes).containsExactly("duplicate_diploma_number", "missing_required_field");
    }

    @Test
    void revokeAndShareLinkAppendOutboxRows() throws Exception {
        seedDiplomaForStudent("student-001", "D-2026-0004");
        UUID diplomaId = firstDiplomaId();

        mockMvc.perform(post("/api/v1/university/diplomas/{id}/revoke", diplomaId)
                        .with(universityRole("university_operator", ITMO_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"issued in error"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/student/diplomas/{id}/share-links", diplomaId)
                        .with(studentRole("student", "student-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxViews":2}
                                """))
                .andExpect(status().isOk());

        assertThat(count("select count(*) from revocations")).isEqualTo(1);
        assertThat(count("select count(*) from share_tokens")).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where event_type = 'diploma.revoked.v1'")).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where event_type = 'sharelink.created.v1'")).isEqualTo(1);
    }

    @Test
    void enforcesJwtRoleAndScopeRules() throws Exception {
        seedDiplomaForStudent("student-001", "D-2026-0005");
        UUID diplomaId = firstDiplomaId();

        mockMvc.perform(get("/api/v1/universities/{id}/imports", ITMO_ID))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/universities/{id}/imports", ITMO_ID)
                        .with(universityRole("university_operator", BMSTU_ID)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/student/diplomas/{id}", diplomaId)
                        .with(studentRole("student", "student-999")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/student/diplomas")
                        .with(jwtWithClaims(
                                "student-without-scope",
                                List.of("student"),
                                Map.of()
                        )))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/university/diplomas")
                        .with(jwtWithClaims(
                                "super-admin",
                                List.of("super_admin"),
                                Map.of()
                        )))
                .andExpect(status().isOk());
    }

    private String createImport(UUID universityId, String filename, String contentType, String content) throws Exception {
        return createImport(universityId, filename, contentType, content.getBytes());
    }

    private String createImport(UUID universityId, String filename, String contentType, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, content);
        var result = mockMvc.perform(multipart("/api/v1/universities/{id}/imports", universityId)
                        .file(file)
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        })
                        .with(universityRole("university_operator", universityId)))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("objectKey").asText()).endsWith(filename);
        return payload.path("id").asText();
    }

    private void seedDiplomaForStudent(String studentExternalId, String diplomaNumber) throws Exception {
        String importJobId = createImport(
                ITMO_ID,
                "seed.csv",
                "text/csv",
                """
                student_external_id,full_name,diploma_number,program_name
                %s,Ivan Petrov,%s,Computer Science
                """.formatted(studentExternalId, diplomaNumber)
        );
        assertThat(importJobId).isNotBlank();
        assertThat(importJobService.processNextPendingImport()).isTrue();
    }

    private UUID firstDiplomaId() {
        return jdbcClient.sql("select id from diplomas order by created_at asc limit 1")
                .query(UUID.class)
                .single();
    }

    private Map<String, Object> findImportJob(String importJobId) {
        return jdbcClient.sql("""
                select status, total_rows, processed_rows, failed_rows
                from import_jobs
                where id = :id
                """)
                .param("id", UUID.fromString(importJobId))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "total_rows", rs.getInt("total_rows"),
                        "processed_rows", rs.getInt("processed_rows"),
                        "failed_rows", rs.getInt("failed_rows")
                ))
                .single();
    }

    private int count(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? 0 : value.intValue();
    }

    private List<JsonNode> consumeTopic(String topic, int expectedCount) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "registry-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            List<JsonNode> payloads = new ArrayList<>();

            while (System.nanoTime() < deadline && payloads.size() < expectedCount) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    payloads.add(objectMapper.readTree(record.value()));
                }
            }

            return payloads;
        }
    }

    private byte[] createWorkbook(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("diplomas");
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex);
                List<String> cells = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < cells.size(); columnIndex++) {
                    row.createCell(columnIndex).setCellValue(cells.get(columnIndex));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private RequestPostProcessor universityRole(String role, UUID universityId) {
        return jwtWithClaims(
                role + "-subject",
                List.of(role),
                Map.of("university_id", universityId.toString())
        );
    }

    private RequestPostProcessor studentRole(String role, String studentExternalId) {
        return jwtWithClaims(
                role + "-subject",
                List.of(role),
                Map.of("student_external_id", studentExternalId)
        );
    }

    private RequestPostProcessor jwtWithClaims(String subject, List<String> roles, Map<String, Object> extraClaims) {
        return jwt()
                .authorities(roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())
                .jwt(builder -> {
                    builder.subject(subject);
                    builder.claim("realm_access", Map.of("roles", roles));
                    for (Map.Entry<String, Object> claim : extraClaims.entrySet()) {
                        builder.claim(claim.getKey(), claim.getValue());
                    }
                });
    }
}
