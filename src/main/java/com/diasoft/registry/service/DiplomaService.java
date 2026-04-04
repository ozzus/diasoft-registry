package com.diasoft.registry.service;

import com.diasoft.registry.api.dto.DiplomaResponse;
import com.diasoft.registry.api.dto.ShareLinkResponse;
import com.diasoft.registry.config.AppProperties;
import com.diasoft.registry.model.DiplomaStatus;
import com.diasoft.registry.model.ShareLinkStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiplomaService {
    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final ActorResolver actorResolver;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final AppProperties properties;
    private final CurrentUserService currentUserService;

    public DiplomaService(
            JdbcClient jdbcClient,
            Clock clock,
            ActorResolver actorResolver,
            AuditService auditService,
            OutboxService outboxService,
            AppProperties properties,
            CurrentUserService currentUserService
    ) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.actorResolver = actorResolver;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.properties = properties;
        this.currentUserService = currentUserService;
    }

    public List<DiplomaResponse> listUniversityDiplomas() {
        if (currentUserService.isSuperAdmin()) {
            return jdbcClient.sql(baseDiplomaSelect() + """
                    order by d.updated_at desc
                    limit 100
                    """)
                    .query(diplomaRowMapper())
                    .list();
        }

        UUID universityId = currentUserService.requireUniversityScope();
        ensureUniversityExists(universityId);
        return jdbcClient.sql(baseDiplomaSelect() + """
                where d.university_id = :universityId
                order by d.updated_at desc
                limit 100
                """)
                .param("universityId", universityId)
                .query(diplomaRowMapper())
                .list();
    }

    public DiplomaResponse getUniversityDiploma(UUID diplomaId) {
        DiplomaResponse diploma = jdbcClient.sql(baseDiplomaSelect() + """
                where d.id = :diplomaId
                """)
                .param("diplomaId", diplomaId)
                .query(diplomaRowMapper())
                .optional()
                .orElseThrow(() -> new NotFoundException("diploma not found"));
        currentUserService.assertUniversityAccess(diploma.universityId());
        return diploma;
    }

    public List<DiplomaResponse> listStudentDiplomas() {
        if (currentUserService.isSuperAdmin()) {
            return jdbcClient.sql(baseDiplomaSelect() + """
                    order by d.updated_at desc
                    limit 100
                    """)
                    .query(diplomaRowMapper())
                    .list();
        }

        String studentExternalId = currentUserService.requireStudentScope();
        return jdbcClient.sql(baseDiplomaSelect() + """
                where s.external_id = :studentExternalId
                order by d.updated_at desc
                limit 100
                """)
                .param("studentExternalId", studentExternalId)
                .query(diplomaRowMapper())
                .list();
    }

    public DiplomaResponse getStudentDiploma(UUID diplomaId) {
        DiplomaResponse diploma = jdbcClient.sql(baseDiplomaSelect() + """
                where d.id = :diplomaId
                """)
                .param("diplomaId", diplomaId)
                .query(diplomaRowMapper())
                .optional()
                .orElseThrow(() -> new NotFoundException("student diploma not found"));

        if (!currentUserService.isSuperAdmin()) {
            String studentExternalId = currentUserService.requireStudentScope();
            if (diploma.studentExternalId() == null || !diploma.studentExternalId().equals(studentExternalId)) {
                throw new org.springframework.security.access.AccessDeniedException("student scope mismatch");
            }
        }

        return diploma;
    }

    @Transactional
    public DiplomaResponse revoke(UUID diplomaId, String reason) {
        DiplomaResponse diploma = getUniversityDiploma(diplomaId);
        Instant now = Instant.now(clock);

        jdbcClient.sql("""
                update diplomas
                set status = :status, updated_at = :updatedAt
                where id = :id
                """)
                .param("status", DiplomaStatus.revoked.name())
                .param("updatedAt", now)
                .param("id", diplomaId)
                .update();

        jdbcClient.sql("""
                insert into revocations (id, diploma_id, reason, created_at)
                values (:id, :diplomaId, :reason, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("diplomaId", diplomaId)
                .param("reason", reason)
                .param("createdAt", now)
                .update();

        auditService.log(
                actorResolver.currentActorId(),
                "diploma.revoked",
                "diploma",
                diplomaId.toString(),
                Map.of("reason", reason, "university_id", diploma.universityId())
        );

        outboxService.append("diploma", diplomaId, "diploma.revoked.v1", Map.of(
                "diploma_id", diplomaId.toString(),
                "verification_token", diploma.verificationToken(),
                "university_code", diploma.universityCode(),
                "diploma_number", diploma.diplomaNumber(),
                "student_name_masked", diploma.ownerNameMask(),
                "program_name", diploma.programName(),
                "status", DiplomaStatus.revoked.name()
        ));

        return getUniversityDiploma(diplomaId);
    }

    @Transactional
    public ShareLinkResponse createShareLink(UUID diplomaId, Integer requestedMaxViews) {
        DiplomaResponse diploma = getStudentDiploma(diplomaId);

        UUID shareLinkId = UUID.randomUUID();
        String shareToken = UUID.randomUUID().toString().replace("-", "");
        int maxViews = requestedMaxViews == null ? properties.shareLink().maxViews() : requestedMaxViews;
        Instant expiresAt = Instant.now(clock).plus(properties.shareLink().ttl());

        jdbcClient.sql("""
                insert into share_tokens (id, diploma_id, token, expires_at, max_views, used_views, status, created_at)
                values (:id, :diplomaId, :token, :expiresAt, :maxViews, 0, :status, :createdAt)
                """)
                .param("id", shareLinkId)
                .param("diplomaId", diplomaId)
                .param("token", shareToken)
                .param("expiresAt", expiresAt)
                .param("maxViews", maxViews)
                .param("status", ShareLinkStatus.active.name())
                .param("createdAt", Instant.now(clock))
                .update();

        auditService.log(
                actorResolver.currentActorId(),
                "sharelink.created",
                "share_link",
                shareLinkId.toString(),
                Map.of("diploma_id", diplomaId, "max_views", maxViews)
        );

        outboxService.append("share_link", shareLinkId, "sharelink.created.v1", Map.of(
                "share_token", shareToken,
                "diploma_id", diplomaId.toString(),
                "expires_at", expiresAt,
                "max_views", maxViews,
                "used_views", 0,
                "status", ShareLinkStatus.active.name()
        ));

        return new ShareLinkResponse(
                shareLinkId,
                diplomaId,
                shareToken,
                expiresAt,
                maxViews,
                0,
                ShareLinkStatus.active.name(),
                properties.gateway().publicBaseUrl().replaceAll("/+$", "") + "/s/" + shareToken
        );
    }

    private String baseDiplomaSelect() {
        return """
                select d.id,
                       d.university_id,
                       u.code as university_code,
                       s.id as student_id,
                       s.external_id as student_external_id,
                       s.full_name as owner_name_mask,
                       d.diploma_number,
                       d.program_name,
                       d.status,
                       vt.token as verification_token,
                       d.created_at,
                       d.updated_at
                from diplomas d
                join universities u on u.id = d.university_id
                join students s on s.id = d.student_id
                left join verification_tokens vt on vt.diploma_id = d.id and vt.is_active = true
                """;
    }

    private RowMapper<DiplomaResponse> diplomaRowMapper() {
        return (rs, rowNum) -> new DiplomaResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("university_id", UUID.class),
                rs.getString("university_code"),
                rs.getObject("student_id", UUID.class),
                rs.getString("student_external_id"),
                maskFullName(rs.getString("owner_name_mask")),
                rs.getString("diploma_number"),
                rs.getString("program_name"),
                rs.getString("status"),
                rs.getString("verification_token"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private String maskFullName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (index > 0) {
                builder.append(' ');
            }
            builder.append(part, 0, 1).append("***");
        }
        return builder.toString();
    }

    private void ensureUniversityExists(UUID universityId) {
        Optional<Long> count = jdbcClient.sql("select count(*) from universities where id = :id")
                .param("id", universityId)
                .query(Long.class)
                .optional();
        if (count.isEmpty() || count.get() == 0) {
            throw new NotFoundException("university not found");
        }
    }
}
