package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.GatewayDiplomaListResponse;
import com.diasoft.registry.config.SecurityConfig;
import com.diasoft.registry.service.DiplomaService;
import com.diasoft.registry.service.ImportJobService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GatewayInternalController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.gateway.service-token=test-gateway-token",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com"
})
class GatewayInternalControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiplomaService diplomaService;

    @MockBean
    private ImportJobService importJobService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsMissingServiceToken() throws Exception {
        mockMvc.perform(get("/internal/gateway/university/diplomas")
                        .param("universityId", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void allowsGatewayServiceBearerToken() throws Exception {
        given(diplomaService.listUniversityDiplomasForGateway(any(UUID.class), isNull(), isNull(), anyInt(), anyInt()))
                .willReturn(new GatewayDiplomaListResponse(List.of(), 0));

        mockMvc.perform(get("/internal/gateway/university/diplomas")
                        .param("universityId", "11111111-1111-1111-1111-111111111111")
                        .header("Authorization", "Bearer test-gateway-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
