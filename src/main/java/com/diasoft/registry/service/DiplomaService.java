package com.diasoft.registry.service;

import com.diasoft.registry.api.dto.DiplomaResponse;
import com.diasoft.registry.api.dto.GatewayDiplomaListResponse;
import com.diasoft.registry.api.dto.GatewayQrResponse;
import com.diasoft.registry.api.dto.ShareLinkResponse;
import com.diasoft.registry.config.AppProperties;
import com.diasoft.registry.model.DiplomaStatus;
import com.diasoft.registry.model.ShareLinkStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiplomaService {
    private static final String GATEWAY_SERVICE_ACTOR = "gateway-service";
    private static final Set<Integer> ALLOWED_TTL_SECONDS = Set.of(3600, 86400, 604800, 2592000);

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
        DiplomaResponse diploma = findDiplomaById(diplomaId, "diploma not found");
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
        DiplomaResponse diploma = findDiplomaById(diplomaId, "student diploma not found");

        if (!currentUserService.isSuperAdmin()) {
            String studentExternalId = currentUserService.requireStudentScope();
            if (diploma.studentExternalId() == null || !diploma.studentExternalId().equals(studentExternalId)) {
                throw new org.springframework.security.access.AccessDeniedException("student scope mismatch");
            }
        }

        return diploma;
    }

    public GatewayDiplomaListResponse listUniversityDiplomasForGateway(UUID universityId, String search, String status, int page, int pageSize) {
        ensureUniversityExists(universityId);
        QueryFilters filters = buildGatewayUniversityFilters(universityId, search, status);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        int offset = (safePage - 1) * safePageSize;

        JdbcClient.StatementSpec listQuery = applyFilters(jdbcClient.sql(
                baseDiplomaSelect() + filters.whereClause() + " order by d.updated_at desc limit :limit offset :offset"
        ), filters)
                .param("limit", safePageSize)
                .param("offset", offset);

        JdbcClient.StatementSpec countQuery = applyFilters(jdbcClient.sql("""
                select count(*)
                from diplomas d
                join students s on s.id = d.student_id
                """ + filters.whereClause()), filters);

        List<DiplomaResponse> items = listQuery.query(diplomaRowMapper()).list();
        Long total = countQuery.query(Long.class).single();
        return new GatewayDiplomaListResponse(items, total == null ? 0 : total);
    }

    public DiplomaResponse getUniversityDiplomaForGateway(UUID universityId, UUID diplomaId) {
        DiplomaResponse diploma = findDiplomaById(diplomaId, "diploma not found");
        if (!diploma.universityId().equals(universityId)) {
            throw new NotFoundException("diploma not found");
        }
        return diploma;
    }

    public DiplomaResponse getStudentDiplomaForGateway(UUID diplomaId) {
        return findDiplomaById(diplomaId, "student diploma not found");
    }

    public GatewayQrResponse getUniversityDiplomaQr(UUID universityId, UUID diplomaId) {
        DiplomaResponse diploma = getUniversityDiplomaForGateway(universityId, diplomaId);
        return new GatewayQrResponse(
                diploma.verificationToken(),
                properties.gateway().publicBaseUrl().replaceAll("/+$", "") + "/v/" + diploma.verificationToken(),
                Instant.parse("2099-12-31T00:00:00Z")
        );
    }

    @Transactional
    public DiplomaResponse revoke(UUID diplomaId, String reason) {
        DiplomaResponse diploma = getUniversityDiploma(diplomaId);
        return revokeInternal(diploma, reason, actorResolver.currentActorId());
    }

    @Transactional
    public DiplomaResponse revokeForGateway(UUID universityId, UUID diplomaId, String reason) {
        DiplomaResponse diploma = getUniversityDiplomaForGateway(universityId, diplomaId);
        return revokeInternal(diploma, reason, GATEWAY_SERVICE_ACTOR);
    }

    @Transactional
    public ShareLinkResponse createShareLink(UUID diplomaId, Integer requestedMaxViews) {
        DiplomaResponse diploma = getStudentDiploma(diplomaId);
        int maxViews = requestedMaxViews == null ? properties.shareLink().maxViews() : requestedMaxViews;
        Instant expiresAt = Instant.now(clock).plus(properties.shareLink().ttl());
        return createShareLinkInternal(diploma, expiresAt, maxViews, actorResolver.currentActorId());
    }

    @Transactional
    public ShareLinkResponse createShareLinkWithTtl(UUID diplomaId, int ttlSeconds) {
        if (!ALLOWED_TTL_SECONDS.contains(ttlSeconds)) {
            throw new BadRequestException("ttlSeconds is not allowed");
        }
        DiplomaResponse diploma = getStudentDiplomaForGateway(diplomaId);
        Instant expiresAt = Instant.now(clock).plusSeconds(ttlSeconds);
        return createShareLinkInternal(diploma, expiresAt, null, GATEWAY_SERVICE_ACTOR);
    }

    @Transactional
    public void revokeShareLink(UUID diplomaId, String shareToken) {
        ShareTokenRecord shareTokenRecord = jdbcClient.sql("""
                select id, diploma_id, token, expires_at, max_views, used_views, status
                from share_tokens
                where diploma_id = :diplomaId and token = :token
                """)
                .param("diplomaId", diplomaId)
                .param("token", shareToken)
                .query((rs, rowNum) -> new ShareTokenRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("diploma_id", UUID.class),
                        rs.getString("token"),
                        rs.getTimestamp("expires_at").toInstant(),
                        (Integer) rs.getObject("max_views"),
                        rs.getInt("used_views"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new NotFoundException("share link not found"));

        if (ShareLinkStatus.revoked.name().equalsIgnoreCase(shareTokenRecord.status())) {
            return;
        }

        jdbcClient.sql("""
                update share_tokens
                set status = :status
                where id = :id
                """)
                .param("status", ShareLinkStatus.revoked.name())
                .param("id", shareTokenRecord.id())
                .update();

        auditService.log(
                GATEWAY_SERVICE_ACTOR,
                "sharelink.revoked",
                "share_link",
                shareTokenRecord.id().toString(),
                Map.of("diploma_id", diplomaId, "share_token", shareToken)
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("share_token", shareTokenRecord.token());
        payload.put("diploma_id", shareTokenRecord.diplomaId().toString());
        payload.put("expires_at", shareTokenRecord.expiresAt());
        payload.put("used_views", shareTokenRecord.usedViews());
        payload.put("status", ShareLinkStatus.revoked.name());
        if (shareTokenRecord.maxViews() != null) {
            payload.put("max_views", shareTokenRecord.maxViews());
        }
        outboxService.append("share_link", shareTokenRecord.id(), "sharelink.revoked.v1", payload);
    }

    private DiplomaResponse revokeInternal(DiplomaResponse diploma, String reason, String actorId) {
        Instant now = Instant.now(clock);

        jdbcClient.sql("""
                update diplomas
                set status = :status,
                    revoked_at = :revokedAt,
                    revoke_reason = :revokeReason,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("status", DiplomaStatus.revoked.name())
                .param("revokedAt", now)
                .param("revokeReason", reason)
                .param("updatedAt", now)
                .param("id", diploma.id())
                .update();

        jdbcClient.sql("""
                insert into revocations (id, diploma_id, reason, created_at)
                values (:id, :diplomaId, :reason, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("diplomaId", diploma.id())
                .param("reason", reason)
                .param("createdAt", now)
                .update();

        auditService.log(
                actorId,
                "diploma.revoked",
                "diploma",
                diploma.id().toString(),
                Map.of("reason", reason, "university_id", diploma.universityId())
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("diploma_id", diploma.id().toString());
        payload.put("verification_token", diploma.verificationToken());
        payload.put("university_code", diploma.universityCode());
        payload.put("diploma_number", diploma.diplomaNumber());
        payload.put("student_name", diploma.ownerName());
        payload.put("student_name_masked", diploma.ownerNameMask());
        payload.put("program_name", diploma.programName());
        payload.put("revoked_at", now.truncatedTo(ChronoUnit.MILLIS));
        payload.put("revoke_reason", reason);
        payload.put("status", DiplomaStatus.revoked.name());
        if (diploma.graduationYear() != null) {
            payload.put("graduation_year", diploma.graduationYear());
        }
        if (diploma.recordHash() != null) {
            payload.put("record_hash", diploma.recordHash());
        }
        outboxService.append("diploma", diploma.id(), "diploma.revoked.v1", payload);

        return findDiplomaById(diploma.id(), "diploma not found");
    }

    private ShareLinkResponse createShareLinkInternal(DiplomaResponse diploma, Instant expiresAt, Integer maxViews, String actorId) {
        UUID shareLinkId = UUID.randomUUID();
        String shareToken = UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.now(clock);

        jdbcClient.sql("""
                insert into share_tokens (id, diploma_id, token, expires_at, max_views, used_views, status, created_at)
                values (:id, :diplomaId, :token, :expiresAt, :maxViews, 0, :status, :createdAt)
                """)
                .param("id", shareLinkId)
                .param("diplomaId", diploma.id())
                .param("token", shareToken)
                .param("expiresAt", expiresAt)
                .param("maxViews", maxViews)
                .param("status", ShareLinkStatus.active.name())
                .param("createdAt", createdAt)
                .update();

        auditService.log(
                actorId,
                "sharelink.created",
                "share_link",
                shareLinkId.toString(),
                maxViews == null
                        ? Map.of("diploma_id", diploma.id(), "expires_at", expiresAt)
                        : Map.of("diploma_id", diploma.id(), "max_views", maxViews, "expires_at", expiresAt)
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("share_token", shareToken);
        payload.put("diploma_id", diploma.id().toString());
        payload.put("expires_at", expiresAt);
        payload.put("used_views", 0);
        payload.put("status", ShareLinkStatus.active.name());
        if (maxViews != null) {
            payload.put("max_views", maxViews);
        }
        outboxService.append("share_link", shareLinkId, "sharelink.created.v1", payload);

        return new ShareLinkResponse(
                shareLinkId,
                diploma.id(),
                shareToken,
                expiresAt,
                maxViews,
                0,
                ShareLinkStatus.active.name(),
                properties.gateway().publicBaseUrl().replaceAll("/+$", "") + "/s/" + shareToken
        );
    }

    private DiplomaResponse findDiplomaById(UUID diplomaId, String notFoundMessage) {
        return jdbcClient.sql(baseDiplomaSelect() + """
                where d.id = :diplomaId
                """)
                .param("diplomaId", diplomaId)
                .query(diplomaRowMapper())
                .optional()
                .orElseThrow(() -> new NotFoundException(notFoundMessage));
    }

    private QueryFilters buildGatewayUniversityFilters(UUID universityId, String search, String status) {
        StringBuilder where = new StringBuilder(" where d.university_id = :universityId");
        String searchValue = null;
        String mappedStatus = null;

        if (search != null && !search.isBlank()) {
            where.append(" and (s.full_name ilike :search or d.diploma_number ilike :search)");
            searchValue = "%" + search.trim() + "%";
        }
        if (status != null && !status.isBlank()) {
            mappedStatus = switch (status.trim().toLowerCase()) {
                case "active" -> DiplomaStatus.valid.name();
                case "revoked" -> DiplomaStatus.revoked.name();
                case "expired" -> "expired";
                default -> throw new BadRequestException("unsupported status filter");
            };
            where.append(" and d.status = :status");
        }

        return new QueryFilters(where.toString(), universityId, searchValue, mappedStatus);
    }

    private JdbcClient.StatementSpec applyFilters(JdbcClient.StatementSpec statementSpec, QueryFilters filters) {
        statementSpec = statementSpec.param("universityId", filters.universityId());
        if (filters.searchValue() != null) {
            statementSpec = statementSpec.param("search", filters.searchValue());
        }
        if (filters.status() != null) {
            statementSpec = statementSpec.param("status", filters.status());
        }
        return statementSpec;
    }

    private String baseDiplomaSelect() {
        return """
                select d.id,
                       d.university_id,
                       u.code as university_code,
                       s.id as student_id,
                       s.external_id as student_external_id,
                       s.full_name as owner_name,
                       d.diploma_number,
                       d.program_name,
                       d.graduation_year,
                       d.record_hash,
                       d.status,
                       vt.token as verification_token,
                       d.revoked_at,
                       d.revoke_reason,
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
                rs.getString("owner_name"),
                maskFullName(rs.getString("owner_name")),
                rs.getString("diploma_number"),
                rs.getString("program_name"),
                (Integer) rs.getObject("graduation_year"),
                rs.getString("record_hash"),
                rs.getString("status"),
                rs.getString("verification_token"),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getString("revoke_reason"),
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

    private record QueryFilters(String whereClause, UUID universityId, String searchValue, String status) {}

    private record ShareTokenRecord(UUID id, UUID diplomaId, String token, Instant expiresAt, Integer maxViews, int usedViews, String status) {}
}
