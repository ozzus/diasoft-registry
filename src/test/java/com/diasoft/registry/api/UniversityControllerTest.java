package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.UniversityResponse;
import com.diasoft.registry.config.SecurityConfig;
import com.diasoft.registry.service.UniversityService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UniversityController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.security.enabled=false")
class UniversityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UniversityService universityService;

    @Test
    void listsUniversities() throws Exception {
        given(universityService.listUniversities()).willReturn(List.of(
                new UniversityResponse(UUID.fromString("11111111-1111-1111-1111-111111111111"), "ITMO", "ITMO University", Instant.parse("2026-04-04T10:00:00Z"))
        ));

        mockMvc.perform(get("/api/v1/universities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ITMO"))
                .andExpect(jsonPath("$[0].name").value("ITMO University"));
    }
}
