package com.diasoft.registry.service;

import com.diasoft.registry.api.dto.ImportJobErrorResponse;
import com.diasoft.registry.api.dto.ImportJobResponse;
import com.diasoft.registry.model.DiplomaStatus;
import com.diasoft.registry.model.ImportJobStatus;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportJobService {
    private static final String CODE_INVALID_FILE_FORMAT = "invalid_file_format";
    private static final String CODE_INVALID_COLUMN_COUNT = "invalid_column_count";
    private static final String CODE_MISSING_REQUIRED_FIELD = "missing_required_field";
    private static final String CODE_DUPLICATE_DIPLOMA_NUMBER = "duplicate_diploma_number";
    private static final String CODE_PERSISTENCE_ERROR = "persistence_error";

    private final JdbcClient jdbcClient;
    private final ObjectStorageService objectStorageService;
    private final AuditService auditService;
    private final ActorResolver actorResolver;
    private final Clock clock;
    private final MaskingService maskingService;
    private final OutboxService outboxService;
    private final CurrentUserService currentUserService;

    public ImportJobService(
            JdbcClient jdbcClient,
            ObjectStorageService objectStorageService,
            AuditService auditService,
            ActorResolver actorResolver,
            Clock clock,
            MaskingService maskingService,
            OutboxService outboxService,
            CurrentUserService currentUserService
    ) {
        this.jdbcClient = jdbcClient;
        this.objectStorageService = objectStorageService;
        this.auditService = auditService;
        this.actorResolver = actorResolver;
        this.clock = clock;
        this.maskingService = maskingService;
        this.outboxService = outboxService;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public ImportJobResponse createImport(UUID universityId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("import file is empty");
        }
        currentUserService.assertUniversityAccess(universityId);
        ensureUniversityExists(universityId);

        UUID importJobId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String objectKey = "imports/%s/%s-%s".formatted(
                universityId,
                importJobId,
                sanitizeFileName(file.getOriginalFilename())
        );

        try {
            objectStorageService.putObject(
                    objectKey,
                    file.getInputStream(),
                    file.getSize(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("failed to upload file to object storage", ex);
        }

        jdbcClient.sql("""
                insert into import_jobs (
                    id, university_id, object_key, status, total_rows, processed_rows, failed_rows, created_at, updated_at
                ) values (
                    :id, :universityId, :objectKey, :status, null, 0, 0, :createdAt, :updatedAt
                )
                """)
                .param("id", importJobId)
                .param("universityId", universityId)
                .param("objectKey", objectKey)
                .param("status", ImportJobStatus.pending.name())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();

        auditService.log(
                actorResolver.currentActorId(),
                "import.created",
                "import_job",
                importJobId.toString(),
                Map.of("university_id", universityId, "object_key", objectKey)
        );

        return getImport(importJobId);
    }

    public List<ImportJobResponse> listImports(UUID universityId) {
        currentUserService.assertUniversityAccess(universityId);
        ensureUniversityExists(universityId);
        return jdbcClient.sql("""
                select id, university_id, object_key, status, total_rows, processed_rows, failed_rows, created_at, updated_at
                from import_jobs
                where university_id = :universityId
                order by created_at desc
                limit 50
                """)
                .param("universityId", universityId)
                .query(importJobRowMapper())
                .list();
    }

    public ImportJobResponse getImport(UUID importJobId) {
        ImportJobResponse response = jdbcClient.sql("""
                select id, university_id, object_key, status, total_rows, processed_rows, failed_rows, created_at, updated_at
                from import_jobs
                where id = :id
                """)
                .param("id", importJobId)
                .query(importJobRowMapper())
                .optional()
                .orElseThrow(() -> new NotFoundException("import job not found"));
        currentUserService.assertUniversityAccess(response.universityId());
        return response;
    }

    public List<ImportJobErrorResponse> getImportErrors(UUID importJobId) {
        getImport(importJobId);
        return jdbcClient.sql("""
                select id, import_job_id, row_number, code, message, created_at
                from import_job_errors
                where import_job_id = :importJobId
                order by row_number asc, created_at asc
                """)
                .param("importJobId", importJobId)
                .query((rs, rowNum) -> new ImportJobErrorResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("import_job_id", UUID.class),
                        rs.getInt("row_number"),
                        rs.getString("code"),
                        rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    @Transactional
    public boolean processNextPendingImport() {
        Optional<LockedImportJob> nextJob = jdbcClient.sql("""
                select id, university_id, object_key
                from import_jobs
                where status = 'pending'
                order by created_at asc
                for update skip locked
                limit 1
                """)
                .query((rs, rowNum) -> new LockedImportJob(
                        rs.getObject("id", UUID.class),
                        rs.getObject("university_id", UUID.class),
                        rs.getString("object_key")
                ))
                .optional();

        if (nextJob.isEmpty()) {
            return false;
        }

        processLockedImport(nextJob.get());
        return true;
    }

    private void processLockedImport(LockedImportJob job) {
        Instant now = Instant.now(clock);
        jdbcClient.sql("""
                update import_jobs
                set status = :status, updated_at = :updatedAt
                where id = :id
                """)
                .param("status", ImportJobStatus.processing.name())
                .param("updatedAt", now)
                .param("id", job.id())
                .update();

        List<RawImportRow> rows;
        try {
            rows = extractRows(job.objectKey());
        } catch (ImportFileException ex) {
            failImport(job, ex.code(), ex.getMessage());
            return;
        }

        String universityCode = jdbcClient.sql("select code from universities where id = :id")
                .param("id", job.universityId())
                .query(String.class)
                .single();

        int processedRows = 0;
        int failedRows = 0;
        Map<String, Integer> seenDiplomaNumbers = new java.util.HashMap<>();

        for (RawImportRow rawRow : rows) {
            try {
                ParsedRow row = parseRow(rawRow);
                Integer firstSeenAt = seenDiplomaNumbers.putIfAbsent(row.diplomaNumber(), rawRow.rowNumber());
                if (firstSeenAt != null) {
                    throw new ImportRowException(
                            CODE_DUPLICATE_DIPLOMA_NUMBER,
                            "duplicate diploma_number in import batch; first seen at row " + firstSeenAt
                    );
                }
                UUID studentId = findOrCreateStudent(row.studentExternalId(), row.fullName());
                UpsertedDiploma diploma = upsertDiploma(job.universityId(), studentId, row);
                outboxService.append("diploma", diploma.id(), diploma.eventType(), Map.of(
                        "diploma_id", diploma.id().toString(),
                        "verification_token", diploma.verificationToken(),
                        "university_code", universityCode,
                        "diploma_number", row.diplomaNumber(),
                        "student_name_masked", maskingService.maskFullName(row.fullName()),
                        "program_name", row.programName(),
                        "status", DiplomaStatus.valid.name()
                ));
                processedRows++;
            } catch (ImportRowException ex) {
                failedRows++;
                insertImportError(job.id(), rawRow.rowNumber(), ex.code(), ex.getMessage());
            } catch (DataAccessException ex) {
                failedRows++;
                insertImportError(job.id(), rawRow.rowNumber(), CODE_PERSISTENCE_ERROR, "database write failed");
            }
        }

        ImportJobStatus finalStatus = resolveFinalStatus(processedRows, failedRows);
        Instant finishedAt = Instant.now(clock);

        jdbcClient.sql("""
                update import_jobs
                set total_rows = :totalRows,
                    processed_rows = :processedRows,
                    failed_rows = :failedRows,
                    status = :status,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("totalRows", rows.size())
                .param("processedRows", processedRows)
                .param("failedRows", failedRows)
                .param("status", finalStatus.name())
                .param("updatedAt", finishedAt)
                .param("id", job.id())
                .update();

        outboxService.append("import_job", job.id(), "import.completed.v1", Map.of(
                "import_job_id", job.id().toString(),
                "university_id", job.universityId().toString(),
                "status", finalStatus.name(),
                "processed_rows", processedRows,
                "failed_rows", failedRows,
                "total_rows", rows.size()
        ));
    }

    private List<RawImportRow> extractRows(String objectKey) {
        byte[] bytes = objectStorageService.getObject(objectKey);
        if (objectKey.toLowerCase().endsWith(".xlsx")) {
            return extractSpreadsheetRows(bytes);
        }
        return extractCsvRows(bytes);
    }

    private List<RawImportRow> extractCsvRows(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] rawLines = content.split("\\R");
        List<RawImportRow> rows = new ArrayList<>();
        for (int index = 0; index < rawLines.length; index++) {
            String rawLine = rawLines[index];
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }

            List<String> cells = splitCsvLine(rawLine);
            if (rows.isEmpty() && looksLikeHeader(cells)) {
                continue;
            }
            rows.add(new RawImportRow(index + 1, cells));
        }

        if (rows.isEmpty()) {
            throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "import file does not contain data rows");
        }
        return rows;
    }

    private List<RawImportRow> extractSpreadsheetRows(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "xlsx import does not contain any sheets");
            }

            DataFormatter formatter = new DataFormatter();
            List<RawImportRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = extractCells(row, formatter);
                if (cells.isEmpty() || cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (rows.isEmpty() && looksLikeHeader(cells)) {
                    continue;
                }
                rows.add(new RawImportRow(row.getRowNum() + 1, cells));
            }

            if (rows.isEmpty()) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "xlsx import does not contain data rows");
            }
            return rows;
        } catch (ImportFileException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "failed to parse xlsx import");
        }
    }

    private List<String> extractCells(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum < 0) {
            return cells;
        }
        for (int index = 0; index < lastCellNum; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                cells.add("");
                continue;
            }
            cells.add(formatter.formatCellValue(cell).trim());
        }
        return cells;
    }

    private List<String> splitCsvLine(String rawLine) {
        String[] parts = rawLine.split(",", -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private boolean looksLikeHeader(List<String> cells) {
        String normalized = String.join(" ", cells).toLowerCase();
        return normalized.contains("student") || normalized.contains("diploma") || normalized.contains("program");
    }

    private ParsedRow parseRow(RawImportRow row) {
        if (row.cells().size() < 4) {
            throw new ImportRowException(CODE_INVALID_COLUMN_COUNT, "expected at least 4 columns: student_external_id, full_name, diploma_number, program_name");
        }

        String studentExternalId = row.cells().get(0).trim();
        String fullName = row.cells().get(1).trim();
        String diplomaNumber = row.cells().get(2).trim();
        String programName = row.cells().get(3).trim();

        if (studentExternalId.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "student_external_id is required");
        }
        if (fullName.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "full_name is required");
        }
        if (diplomaNumber.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "diploma_number is required");
        }
        if (programName.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "program_name is required");
        }

        return new ParsedRow(studentExternalId, fullName, diplomaNumber, programName);
    }

    private UUID findOrCreateStudent(String externalId, String fullName) {
        Optional<UUID> existing = jdbcClient.sql("select id from students where external_id = :externalId")
                .param("externalId", externalId)
                .query(UUID.class)
                .optional();

        if (existing.isPresent()) {
            jdbcClient.sql("""
                    update students
                    set full_name = :fullName
                    where id = :id
                    """)
                    .param("fullName", fullName)
                    .param("id", existing.get())
                    .update();
            return existing.get();
        }

        UUID studentId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into students (id, external_id, full_name, created_at)
                values (:id, :externalId, :fullName, :createdAt)
                """)
                .param("id", studentId)
                .param("externalId", externalId)
                .param("fullName", fullName)
                .param("createdAt", Instant.now(clock))
                .update();
        return studentId;
    }

    private UpsertedDiploma upsertDiploma(UUID universityId, UUID studentId, ParsedRow row) {
        Optional<UUID> existingDiploma = jdbcClient.sql("""
                select id
                from diplomas
                where university_id = :universityId and diploma_number = :diplomaNumber
                """)
                .param("universityId", universityId)
                .param("diplomaNumber", row.diplomaNumber())
                .query(UUID.class)
                .optional();

        Instant now = Instant.now(clock);
        UUID diplomaId = existingDiploma.orElseGet(UUID::randomUUID);

        if (existingDiploma.isPresent()) {
            jdbcClient.sql("""
                    update diplomas
                    set student_id = :studentId,
                        program_name = :programName,
                        status = :status,
                        updated_at = :updatedAt
                    where id = :id
                    """)
                    .param("studentId", studentId)
                    .param("programName", row.programName())
                    .param("status", DiplomaStatus.valid.name())
                    .param("updatedAt", now)
                    .param("id", diplomaId)
                    .update();
        } else {
            jdbcClient.sql("""
                    insert into diplomas (id, university_id, student_id, diploma_number, program_name, status, created_at, updated_at)
                    values (:id, :universityId, :studentId, :diplomaNumber, :programName, :status, :createdAt, :updatedAt)
                    """)
                    .param("id", diplomaId)
                    .param("universityId", universityId)
                    .param("studentId", studentId)
                    .param("diplomaNumber", row.diplomaNumber())
                    .param("programName", row.programName())
                    .param("status", DiplomaStatus.valid.name())
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        }

        String verificationToken = jdbcClient.sql("""
                select token
                from verification_tokens
                where diploma_id = :diplomaId and is_active = true
                order by created_at asc
                limit 1
                """)
                .param("diplomaId", diplomaId)
                .query(String.class)
                .optional()
                .orElseGet(() -> {
                    String newToken = UUID.randomUUID().toString().replace("-", "");
                    jdbcClient.sql("""
                            insert into verification_tokens (id, diploma_id, token, is_active, created_at)
                            values (:id, :diplomaId, :token, true, :createdAt)
                            """)
                            .param("id", UUID.randomUUID())
                            .param("diplomaId", diplomaId)
                            .param("token", newToken)
                            .param("createdAt", now)
                            .update();
                    return newToken;
                });

        return new UpsertedDiploma(diplomaId, verificationToken, existingDiploma.isPresent() ? "diploma.updated.v1" : "diploma.created.v1");
    }

    private void ensureUniversityExists(UUID universityId) {
        Long count = jdbcClient.sql("select count(*) from universities where id = :id")
                .param("id", universityId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            throw new NotFoundException("university not found");
        }
    }

    private void failImport(LockedImportJob job, String code, String message) {
        insertImportError(job.id(), 0, code, message);
        jdbcClient.sql("""
                update import_jobs
                set total_rows = 0,
                    processed_rows = 0,
                    failed_rows = 1,
                    status = :status,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("status", ImportJobStatus.failed.name())
                .param("updatedAt", Instant.now(clock))
                .param("id", job.id())
                .update();

        outboxService.append("import_job", job.id(), "import.completed.v1", Map.of(
                "import_job_id", job.id().toString(),
                "university_id", job.universityId().toString(),
                "status", ImportJobStatus.failed.name(),
                "processed_rows", 0,
                "failed_rows", 1,
                "total_rows", 0
        ));
    }

    private void insertImportError(UUID importJobId, int rowNumber, String code, String message) {
        jdbcClient.sql("""
                insert into import_job_errors (id, import_job_id, row_number, code, message, created_at)
                values (:id, :importJobId, :rowNumber, :code, :message, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("importJobId", importJobId)
                .param("rowNumber", rowNumber)
                .param("code", code)
                .param("message", message)
                .param("createdAt", Instant.now(clock))
                .update();
    }

    private ImportJobStatus resolveFinalStatus(int processedRows, int failedRows) {
        if (processedRows == 0 && failedRows > 0) {
            return ImportJobStatus.failed;
        }
        if (failedRows > 0) {
            return ImportJobStatus.partially_failed;
        }
        return ImportJobStatus.completed;
    }

    private String sanitizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "registry.csv";
        }
        return originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private RowMapper<ImportJobResponse> importJobRowMapper() {
        return (rs, rowNum) -> new ImportJobResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("university_id", UUID.class),
                rs.getString("object_key"),
                rs.getString("status"),
                (Integer) rs.getObject("total_rows"),
                rs.getInt("processed_rows"),
                rs.getInt("failed_rows"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private record LockedImportJob(UUID id, UUID universityId, String objectKey) {}

    private record RawImportRow(int rowNumber, List<String> cells) {}

    private record ParsedRow(String studentExternalId, String fullName, String diplomaNumber, String programName) {}

    private record UpsertedDiploma(UUID id, String verificationToken, String eventType) {}

    private static final class ImportRowException extends RuntimeException {
        private final String code;

        private ImportRowException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final class ImportFileException extends RuntimeException {
        private final String code;

        private ImportFileException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
