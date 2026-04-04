package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.CurrentUserResponse;
import com.diasoft.registry.config.SecurityConfig;
import com.diasoft.registry.service.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MeController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com"
})
class MeControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsCurrentUserForAuthorizedRole() throws Exception {
        given(currentUserService.currentUserResponse()).willReturn(new CurrentUserResponse(
                "student-001",
                List.of("student"),
                null,
                "student-001"
        ));

        mockMvc.perform(get("/api/v1/me").with(jwt().jwt(jwt -> jwt
                        .subject("student-001")
                        .claim("realm_access", Map.of("roles", List.of("student")))
                )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("student-001"))
                .andExpect(jsonPath("$.roles[0]").value("student"))
                .andExpect(jsonPath("$.studentExternalId").value("student-001"));
    }

    @Test
    void rejectsRoleOutsideAllowedAudience() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(jwt().jwt(jwt -> jwt
                        .subject("anonymous")
                        .claim("realm_access", Map.of("roles", List.of("hr_viewer")))
                )))
                .andExpect(status().isForbidden());
    }
}
