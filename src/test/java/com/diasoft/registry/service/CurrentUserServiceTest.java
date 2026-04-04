package com.diasoft.registry.service;

import com.diasoft.registry.config.AppProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserServiceTest {
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsDevIdentityWhenSecurityIsDisabled() {
        CurrentUserService service = new CurrentUserService(appProperties(false));

        CurrentUserService.CurrentUser user = service.currentUser();

        assertThat(user.subject()).isEqualTo("dev-super-admin");
        assertThat(user.roles()).containsExactly("super_admin");
        assertThat(user.universityId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(user.studentExternalId()).isEqualTo("student-001");
    }

    @Test
    void resolvesScopedClaimsFromJwt() {
        CurrentUserService service = new CurrentUserService(appProperties(true));
        UUID universityId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(
                "university-operator",
                List.of("university_operator"),
                universityId,
                null
        ));

        CurrentUserService.CurrentUser user = service.currentUser();

        assertThat(user.subject()).isEqualTo("university-operator");
        assertThat(user.roles()).contains("university_operator");
        assertThat(user.universityId()).isEqualTo(universityId);
        assertThat(user.studentExternalId()).isNull();
    }

    @Test
    void blocksUniversityScopeMismatch() {
        CurrentUserService service = new CurrentUserService(appProperties(true));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(
                "university-operator",
                List.of("university_operator"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                null
        ));

        assertThatThrownBy(() -> service.assertUniversityAccess(UUID.fromString("33333333-3333-3333-3333-333333333333")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("university scope mismatch");
    }

    @Test
    void requiresStudentScopeForStudentFlows() {
        CurrentUserService service = new CurrentUserService(appProperties(true));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(
                "student-001",
                List.of("student"),
                null,
                "student-001"
        ));

        assertThat(service.requireStudentScope()).isEqualTo("student-001");
    }

    @Test
    void rejectsMissingStudentScopeClaim() {
        CurrentUserService service = new CurrentUserService(appProperties(true));
        SecurityContextHolder.getContext().setAuthentication(jwtAuthentication(
                "student-001",
                List.of("student"),
                null,
                null
        ));

        assertThatThrownBy(service::requireStudentScope)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("student scope is required");
    }

    private static JwtAuthenticationToken jwtAuthentication(
            String subject,
            List<String> roles,
            UUID universityId,
            String studentExternalId
    ) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", roles));
        if (universityId != null) {
            builder.claim("university_id", universityId.toString());
        }
        if (studentExternalId != null) {
            builder.claim("student_external_id", studentExternalId);
        }
        Jwt jwt = builder.build();
        return new JwtAuthenticationToken(jwt);
    }

    private static AppProperties appProperties(boolean securityEnabled) {
        return new AppProperties(
                new AppProperties.Runtime("api"),
                new AppProperties.Security(
                        securityEnabled,
                        new AppProperties.DevIdentity(
                                "dev-super-admin",
                                List.of("super_admin"),
                                "11111111-1111-1111-1111-111111111111",
                                "student-001"
                        )
                ),
                new AppProperties.Http(List.of("http://localhost:5173")),
                new AppProperties.Import(500, Duration.ofSeconds(5)),
                new AppProperties.ObjectStorage(
                        "http://localhost:9000",
                        "diploma-imports",
                        "ru-central1",
                        "minioadmin",
                        "minioadmin",
                        true,
                        true
                ),
                new AppProperties.Outbox(100, Duration.ofSeconds(5), "diasoft-registry", 3, Duration.ofSeconds(1), Duration.ofSeconds(5)),
                new AppProperties.Gateway("http://localhost:8080"),
                new AppProperties.ShareLink(Duration.ofHours(24), 3)
        );
    }
}
