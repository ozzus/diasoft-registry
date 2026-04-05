package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.ImportJobResponse;
import com.diasoft.registry.config.SecurityConfig;
import com.diasoft.registry.service.ImportJobService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ImportController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "app.security.enabled=false")
class ImportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ImportJobService importJobService;

    @Test
    void createsImportJob() throws Exception {
        UUID universityId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given(importJobService.createImport(eq(universityId), any())).willReturn(new ImportJobResponse(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                universityId,
                "imports/test.csv",
                null,
                "pending",
                "csv",
                1,
                null,
                0,
                0,
                0,
                0,
                Instant.parse("2026-04-04T10:00:00Z"),
                Instant.parse("2026-04-04T10:00:00Z")
        ));

        MockMultipartFile file = new MockMultipartFile("file", "registry.csv", "text/csv", "student-1,Ivan Petrov,D-1,Computer Science".getBytes());

        mockMvc.perform(multipart("/api/v1/universities/{id}/imports", universityId).file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.universityId").value(universityId.toString()));
    }
}
