package com.diasoft.registry.service;

import com.diasoft.registry.api.dto.CreateUploadSessionRequest;
import com.diasoft.registry.api.dto.ImportJobErrorResponse;
import com.diasoft.registry.api.dto.ImportJobResponse;
import com.diasoft.registry.api.dto.ImportJobSummaryResponse;
import com.diasoft.registry.api.dto.UploadSessionFileRequest;
import com.diasoft.registry.api.dto.UploadSessionResponse;
import com.diasoft.registry.api.dto.UploadSessionTargetResponse;
import com.diasoft.registry.config.AppProperties;
import com.diasoft.registry.model.DiplomaStatus;
import com.diasoft.registry.model.ImportChunkStatus;
import com.diasoft.registry.model.ImportJobStatus;
import com.diasoft.registry.model.UploadSessionFileStatus;
import com.diasoft.registry.model.UploadSessionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
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
    private static final String CODE_INVALID_FULL_NAME = "invalid_full_name";
    private static final String REQUIRED_HEADERS_MESSAGE = "first row must contain headers: ФИО, номер_диплома, специальность, год_выпуска";
    private static final String INVALID_FULL_NAME_MESSAGE = "full_name must use Cyrillic letters, spaces, and hyphen only";
    private static final Pattern CYRILLIC_FULL_NAME = Pattern.compile("^[А-ЯЁа-яё]+(?:[ -][А-ЯЁа-яё]+)*$");

    private final JdbcClient jdbcClient;
    private final ObjectStorageService objectStorageService;
    private final AuditService auditService;
    private final ActorResolver actorResolver;
    private final Clock clock;
    private final MaskingService maskingService;
    private final RecordHashService recordHashService;
    private final OutboxService outboxService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public ImportJobService(
            JdbcClient jdbcClient,
            ObjectStorageService objectStorageService,
            AuditService auditService,
            ActorResolver actorResolver,
            Clock clock,
            MaskingService maskingService,
            RecordHashService recordHashService,
            OutboxService outboxService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            AppProperties properties
    ) {
        this.jdbcClient = jdbcClient;
        this.objectStorageService = objectStorageService;
        this.auditService = auditService;
        this.actorResolver = actorResolver;
        this.clock = clock;
        this.maskingService = maskingService;
        this.recordHashService = recordHashService;
        this.outboxService = outboxService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public ImportJobResponse createImport(UUID universityId, MultipartFile file) {
        currentUserService.assertUniversityAccess(universityId);
        return createImportInternal(universityId, file);
    }

    @Transactional
    public ImportJobResponse createImportForUniversity(UUID universityId, MultipartFile file) {
        return createImportInternal(universityId, file);
    }

    @Transactional
    public UploadSessionResponse createUploadSession(UUID universityId, CreateUploadSessionRequest request) {
        currentUserService.assertUniversityAccess(universityId);
        return createUploadSessionInternal(universityId, request);
    }

    @Transactional
    public UploadSessionResponse createUploadSessionForUniversity(UUID universityId, CreateUploadSessionRequest request) {
        return createUploadSessionInternal(universityId, request);
    }

    @Transactional
    public ImportJobResponse completeUploadSession(UUID universityId, UUID sessionId) {
        currentUserService.assertUniversityAccess(universityId);
        return completeUploadSessionInternal(universityId, sessionId);
    }

    @Transactional
    public ImportJobResponse completeUploadSessionForUniversity(UUID universityId, UUID sessionId) {
        return completeUploadSessionInternal(universityId, sessionId);
    }

    public List<ImportJobResponse> listImports(UUID universityId) {
        currentUserService.assertUniversityAccess(universityId);
        ensureUniversityExists(universityId);
        return jdbcClient.sql("""
                select id, university_id, object_key, upload_session_id, status, source_format, file_count,
                       total_rows, processed_rows, failed_rows, total_chunks, completed_chunks, created_at, updated_at
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
        ImportJobResponse response = getImportInternal(importJobId);
        currentUserService.assertUniversityAccess(response.universityId());
        return response;
    }

    public ImportJobResponse getImportForUniversity(UUID universityId, UUID importJobId) {
        ImportJobResponse response = getImportInternal(importJobId);
        if (!response.universityId().equals(universityId)) {
            throw new NotFoundException("import job not found");
        }
        return response;
    }

    public ImportJobSummaryResponse getImportSummaryForUniversity(UUID universityId, UUID importJobId) {
        ImportJobResponse response = getImportForUniversity(universityId, importJobId);
        return new ImportJobSummaryResponse(
                response.id(),
                response.status(),
                response.totalRows(),
                response.processedRows(),
                response.failedRows(),
                response.totalChunks(),
                response.completedChunks(),
                response.fileCount(),
                response.createdAt(),
                response.updatedAt()
        );
    }

    public List<ImportJobErrorResponse> getImportErrorsForUniversity(UUID universityId, UUID importJobId) {
        ImportJobResponse response = getImportInternal(importJobId);
        if (!response.universityId().equals(universityId)) {
            throw new NotFoundException("import job not found");
        }
        return fetchImportErrors(importJobId);
    }

    public List<ImportJobErrorResponse> getImportErrors(UUID importJobId) {
        getImport(importJobId);
        return fetchImportErrors(importJobId);
    }

    @Transactional
    public boolean processNextPendingImport() {
        return processNextUploadedImport() || processNextPendingChunk();
    }

    @Transactional
    public boolean processNextUploadedImport() {
        Optional<LockedImportJob> nextJob = jdbcClient.sql("""
                select id, university_id, object_key
                from import_jobs
                where status = 'uploaded'
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

        normalizeLockedImport(nextJob.get());
        return true;
    }

    @Transactional
    public boolean processNextPendingChunk() {
        Optional<LockedImportChunk> nextChunk = jdbcClient.sql("""
                select id, import_job_id, source_file_name, object_key, row_count
                from import_chunks
                where status = 'pending'
                order by created_at asc
                for update skip locked
                limit 1
                """)
                .query((rs, rowNum) -> new LockedImportChunk(
                        rs.getObject("id", UUID.class),
                        rs.getObject("import_job_id", UUID.class),
                        rs.getString("source_file_name"),
                        rs.getString("object_key"),
                        rs.getInt("row_count")
                ))
                .optional();

        if (nextChunk.isEmpty()) {
            return false;
        }

        processLockedChunk(nextChunk.get());
        return true;
    }

    private ImportJobResponse createImportInternal(UUID universityId, MultipartFile file) {
        ensureUniversityExists(universityId);
        byte[] fileBytes = readUploadBytes(file);
        String fileName = requireValidUploadEnvelope(file.getOriginalFilename(), fileBytes.length);
        validateUploadFileContents(fileName, fileBytes);

        Instant now = Instant.now(clock);
        String objectKey = "uploads/%s/compat/%s-%s".formatted(
                universityId,
                now.toEpochMilli(),
                sanitizeFileName(fileName)
        );

        try {
            objectStorageService.putObject(
                    objectKey,
                    new ByteArrayInputStream(fileBytes),
                    fileBytes.length,
                    normalizeContentType(file.getContentType())
            );
        } catch (Exception ex) {
            throw new IllegalStateException("failed to upload file to object storage", ex);
        }

        SessionImportFile sessionFile = new SessionImportFile(
                UUID.randomUUID(),
                fileName,
                normalizeContentType(file.getContentType()),
                fileBytes.length,
                sha256Hex(fileBytes),
                objectKey
        );
        return createImportJobFromFiles(universityId, null, List.of(sessionFile));
    }

    private UploadSessionResponse createUploadSessionInternal(UUID universityId, CreateUploadSessionRequest request) {
        ensureUniversityExists(universityId);
        if (request.files().size() > properties.importConfig().maxFilesPerSession()) {
            throw new BadRequestException("too many files in one session");
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.importConfig().presignTtl());
        UUID sessionId = UUID.randomUUID();

        jdbcClient.sql("""
                insert into upload_sessions (
                    id, university_id, status, expected_file_count, max_file_size_bytes, max_rows_per_file, created_at, expires_at
                ) values (
                    :id, :universityId, :status, :expectedFileCount, :maxFileSizeBytes, :maxRowsPerFile, :createdAt, :expiresAt
                )
                """)
                .param("id", sessionId)
                .param("universityId", universityId)
                .param("status", UploadSessionStatus.open.name())
                .param("expectedFileCount", request.files().size())
                .param("maxFileSizeBytes", properties.importConfig().maxFileSizeBytes())
                .param("maxRowsPerFile", properties.importConfig().maxRowsPerFile())
                .param("createdAt", JdbcTime.timestamp(now))
                .param("expiresAt", JdbcTime.timestamp(expiresAt))
                .update();

        List<UploadSessionTargetResponse> uploads = new ArrayList<>(request.files().size());
        for (UploadSessionFileRequest fileRequest : request.files()) {
            validateSessionFileRequest(fileRequest);
            UUID fileId = UUID.randomUUID();
            String objectKey = "uploads/%s/%s/%s-%s".formatted(
                    universityId,
                    sessionId,
                    fileId,
                    sanitizeFileName(fileRequest.fileName())
            );
            ObjectUploadTarget target = objectStorageService.createUploadTarget(
                    objectKey,
                    normalizeContentType(fileRequest.contentType()),
                    properties.importConfig().presignTtl()
            );

            jdbcClient.sql("""
                    insert into upload_session_files (
                        id, session_id, file_name, content_type, file_size_bytes, file_sha256, object_key, status, created_at
                    ) values (
                        :id, :sessionId, :fileName, :contentType, :fileSizeBytes, :fileSha256, :objectKey, :status, :createdAt
                    )
                    """)
                    .param("id", fileId)
                    .param("sessionId", sessionId)
                    .param("fileName", fileRequest.fileName().trim())
                    .param("contentType", normalizeContentType(fileRequest.contentType()))
                    .param("fileSizeBytes", fileRequest.fileSizeBytes())
                    .param("fileSha256", normalizeSha256(fileRequest.sha256()))
                    .param("objectKey", objectKey)
                    .param("status", UploadSessionFileStatus.pending.name())
                    .param("createdAt", JdbcTime.timestamp(now))
                    .update();

            uploads.add(new UploadSessionTargetResponse(
                    fileId,
                    fileRequest.fileName().trim(),
                    objectKey,
                    target.method(),
                    target.uploadUrl(),
                    target.headers(),
                    target.expiresAt()
            ));
        }

        auditService.log(
                actorResolver.currentActorId(),
                "upload_session.created",
                "upload_session",
                sessionId.toString(),
                Map.of("university_id", universityId.toString(), "file_count", request.files().size())
        );

        return new UploadSessionResponse(
                sessionId,
                properties.importConfig().maxFileSizeBytes(),
                properties.importConfig().maxRowsPerFile(),
                expiresAt,
                uploads
        );
    }

    private ImportJobResponse completeUploadSessionInternal(UUID universityId, UUID sessionId) {
        ensureUniversityExists(universityId);
        UploadSessionRecord session = jdbcClient.sql("""
                select id, university_id, status, expires_at
                from upload_sessions
                where id = :id
                """)
                .param("id", sessionId)
                .query((rs, rowNum) -> new UploadSessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("university_id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new NotFoundException("upload session not found"));

        if (!session.universityId().equals(universityId)) {
            throw new NotFoundException("upload session not found");
        }
        if (!UploadSessionStatus.open.name().equals(session.status())) {
            Optional<ImportJobResponse> existing = jdbcClient.sql("""
                    select id, university_id, object_key, upload_session_id, status, source_format, file_count,
                           total_rows, processed_rows, failed_rows, total_chunks, completed_chunks, created_at, updated_at
                    from import_jobs
                    where upload_session_id = :sessionId
                    order by created_at desc
                    limit 1
                    """)
                    .param("sessionId", sessionId)
                    .query(importJobRowMapper())
                    .optional();
            if (existing.isPresent()) {
                return existing.get();
            }
            throw new BadRequestException("upload session is not open");
        }
        if (session.expiresAt().isBefore(Instant.now(clock))) {
            jdbcClient.sql("""
                    update upload_sessions
                    set status = :status
                    where id = :id
                    """)
                    .param("status", UploadSessionStatus.expired.name())
                    .param("id", sessionId)
                    .update();
            throw new BadRequestException("upload session has expired");
        }

        List<SessionImportFile> files = jdbcClient.sql("""
                select id, file_name, content_type, file_size_bytes, file_sha256, object_key
                from upload_session_files
                where session_id = :sessionId
                order by created_at asc
                """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new SessionImportFile(
                        rs.getObject("id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("file_size_bytes"),
                        rs.getString("file_sha256"),
                        rs.getString("object_key")
                ))
                .list();

        if (files.isEmpty()) {
            throw new BadRequestException("upload session does not contain files");
        }

        Instant now = Instant.now(clock);
        for (SessionImportFile file : files) {
            if (!objectStorageService.objectExists(file.objectKey())) {
                throw new BadRequestException("uploaded object is missing for file " + file.fileName());
            }
            jdbcClient.sql("""
                    update upload_session_files
                    set status = :status, uploaded_at = :uploadedAt
                    where id = :id
                    """)
                    .param("status", UploadSessionFileStatus.uploaded.name())
                    .param("uploadedAt", JdbcTime.timestamp(now))
                    .param("id", file.fileId())
                    .update();
        }

        ImportJobResponse response = createImportJobFromFiles(universityId, sessionId, files);

        jdbcClient.sql("""
                update upload_sessions
                set status = :status, completed_at = :completedAt
                where id = :id
                """)
                .param("status", UploadSessionStatus.completed.name())
                .param("completedAt", JdbcTime.timestamp(now))
                .param("id", sessionId)
                .update();

        auditService.log(
                actorResolver.currentActorId(),
                "upload_session.completed",
                "upload_session",
                sessionId.toString(),
                Map.of("job_id", response.id().toString(), "file_count", files.size())
        );

        return response;
    }

    private ImportJobResponse createImportJobFromFiles(UUID universityId, UUID sessionId, List<SessionImportFile> files) {
        Instant now = Instant.now(clock);
        UUID importJobId = UUID.randomUUID();
        String manifestKey = "imports/%s/%s/manifest.json".formatted(universityId, importJobId);
        long totalFileBytes = files.stream().mapToLong(SessionImportFile::fileSizeBytes).sum();
        String sourceFormat = files.stream()
                .map(file -> extensionOf(file.fileName()))
                .distinct()
                .count() == 1 ? extensionOf(files.get(0).fileName()) : "mixed";

        ManifestPayload payload = new ManifestPayload(
                sessionId,
                files.stream()
                        .map(file -> new ManifestFilePayload(file.fileName(), file.contentType(), file.fileSizeBytes(), file.fileSha256(), file.objectKey()))
                        .toList()
        );

        try {
            objectStorageService.putObject(
                    manifestKey,
                    objectMapper.writeValueAsBytes(payload),
                    "application/json"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("failed to create import manifest", ex);
        }

        jdbcClient.sql("""
                insert into import_jobs (
                    id, university_id, object_key, upload_session_id, status, source_format, file_count,
                    total_rows, processed_rows, failed_rows, total_chunks, completed_chunks,
                    file_size_bytes, file_sha256, created_at, updated_at
                ) values (
                    :id, :universityId, :objectKey, :uploadSessionId, :status, :sourceFormat, :fileCount,
                    null, 0, 0, 0, 0,
                    :fileSizeBytes, :fileSha256, :createdAt, :updatedAt
                )
                """)
                .param("id", importJobId)
                .param("universityId", universityId)
                .param("objectKey", manifestKey)
                .param("uploadSessionId", sessionId)
                .param("status", ImportJobStatus.uploaded.name())
                .param("sourceFormat", sourceFormat)
                .param("fileCount", files.size())
                .param("fileSizeBytes", totalFileBytes)
                .param("fileSha256", files.size() == 1 ? files.get(0).fileSha256() : null)
                .param("createdAt", JdbcTime.timestamp(now))
                .param("updatedAt", JdbcTime.timestamp(now))
                .update();

        auditService.log(
                actorResolver.currentActorId(),
                "import.created",
                "import_job",
                importJobId.toString(),
                Map.of(
                        "university_id", universityId.toString(),
                        "object_key", manifestKey,
                        "file_count", files.size()
                )
        );

        return getImportInternal(importJobId);
    }

    private void normalizeLockedImport(LockedImportJob job) {
        Instant startedAt = Instant.now(clock);
        jdbcClient.sql("""
                update import_jobs
                set status = :status, updated_at = :updatedAt
                where id = :id
                """)
                .param("status", ImportJobStatus.normalizing.name())
                .param("updatedAt", JdbcTime.timestamp(startedAt))
                .param("id", job.id())
                .update();

        ManifestPayload manifest;
        try {
            manifest = objectMapper.readValue(objectStorageService.getObject(job.objectKey()), ManifestPayload.class);
        } catch (Exception ex) {
            failImport(job, null, CODE_INVALID_FILE_FORMAT, "failed to read import manifest");
            return;
        }

        String universityCode = jdbcClient.sql("select code from universities where id = :id")
                .param("id", job.universityId())
                .query(String.class)
                .single();

        NormalizationStats stats = new NormalizationStats();
        Map<String, SeenLocation> seenDiplomaNumbers = new HashMap<>();
        ChunkAccumulator accumulator = new ChunkAccumulator(job.id(), universityIdPrefix(job.universityId()));

        try {
            for (ManifestFilePayload file : manifest.files()) {
                normalizeFile(job.id(), universityCode, file, accumulator, stats, seenDiplomaNumbers);
            }
            accumulator.flush();
        } catch (ImportFileException ex) {
            failImport(job, null, ex.code(), ex.getMessage());
            return;
        } catch (Exception ex) {
            failImport(job, null, CODE_INVALID_FILE_FORMAT, "failed to normalize import");
            return;
        }

        Instant finishedAt = Instant.now(clock);
        if (accumulator.totalChunks() == 0) {
            ImportJobStatus finalStatus = resolveFinalStatus(stats.processedRows(), stats.failedRows());
            jdbcClient.sql("""
                    update import_jobs
                    set total_rows = :totalRows,
                        processed_rows = :processedRows,
                        failed_rows = :failedRows,
                        total_chunks = 0,
                        completed_chunks = 0,
                        status = :status,
                        updated_at = :updatedAt
                    where id = :id
                    """)
                    .param("totalRows", stats.totalRows())
                    .param("processedRows", 0)
                    .param("failedRows", stats.failedRows())
                    .param("status", finalStatus.name())
                    .param("updatedAt", JdbcTime.timestamp(finishedAt))
                    .param("id", job.id())
                    .update();
            appendImportCompleted(job.id(), job.universityId(), finalStatus, 0, stats.failedRows(), stats.totalRows());
            return;
        }

        jdbcClient.sql("""
                update import_jobs
                set total_rows = :totalRows,
                    processed_rows = 0,
                    failed_rows = :failedRows,
                    total_chunks = :totalChunks,
                    completed_chunks = 0,
                    normalized_prefix = :normalizedPrefix,
                    status = :status,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("totalRows", stats.totalRows())
                .param("failedRows", stats.failedRows())
                .param("totalChunks", accumulator.totalChunks())
                .param("normalizedPrefix", accumulator.prefix())
                .param("status", ImportJobStatus.chunked.name())
                .param("updatedAt", JdbcTime.timestamp(finishedAt))
                .param("id", job.id())
                .update();
    }

    private void normalizeFile(
            UUID importJobId,
            String universityCode,
            ManifestFilePayload file,
            ChunkAccumulator accumulator,
            NormalizationStats stats,
            Map<String, SeenLocation> seenDiplomaNumbers
    ) throws IOException {
        String extension = extensionOf(file.fileName());
        if ("xlsx".equals(extension)) {
            normalizeSpreadsheetFile(importJobId, universityCode, file, accumulator, stats, seenDiplomaNumbers);
            return;
        }
        normalizeCsvFile(importJobId, universityCode, file, accumulator, stats, seenDiplomaNumbers);
    }

    private void normalizeCsvFile(
            UUID importJobId,
            String universityCode,
            ManifestFilePayload file,
            ChunkAccumulator accumulator,
            NormalizationStats stats,
            Map<String, SeenLocation> seenDiplomaNumbers
    ) throws IOException {
        try (InputStream input = objectStorageService.getObjectStream(file.objectKey());
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            ColumnMapping columnMapping = null;
            int rowNumber = 0;
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                rowNumber++;
                if (rawLine.isBlank()) {
                    continue;
                }

                List<String> cells = splitCsvLine(rawLine);
                if (columnMapping == null) {
                    columnMapping = requireHeaderMapping(cells);
                    continue;
                }
                handleRawRow(importJobId, universityCode, file.fileName(), new RawImportRow(rowNumber, cells), columnMapping, accumulator, stats, seenDiplomaNumbers);
            }

            if (columnMapping == null) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "import file does not contain header row");
            }
            if (!stats.seenDataRowForFile(file.fileName())) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "import file does not contain data rows");
            }
        }
    }

    private void normalizeSpreadsheetFile(
            UUID importJobId,
            String universityCode,
            ManifestFilePayload file,
            ChunkAccumulator accumulator,
            NormalizationStats stats,
            Map<String, SeenLocation> seenDiplomaNumbers
    ) {
        try (InputStream input = objectStorageService.getObjectStream(file.objectKey());
             Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() != 1) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "xlsx import must contain exactly one data sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            ColumnMapping columnMapping = null;
            for (Row row : sheet) {
                List<String> cells = extractCells(row, formatter);
                if (cells.isEmpty() || cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (columnMapping == null) {
                    columnMapping = requireHeaderMapping(cells);
                    continue;
                }
                handleRawRow(importJobId, universityCode, file.fileName(), new RawImportRow(row.getRowNum() + 1, cells), columnMapping, accumulator, stats, seenDiplomaNumbers);
            }

            if (columnMapping == null) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "xlsx import does not contain header row");
            }
            if (!stats.seenDataRowForFile(file.fileName())) {
                throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "xlsx import does not contain data rows");
            }
        } catch (ImportFileException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportFileException(CODE_INVALID_FILE_FORMAT, "failed to parse xlsx import");
        }
    }

    private void handleRawRow(
            UUID importJobId,
            String universityCode,
            String sourceFileName,
            RawImportRow rawRow,
            ColumnMapping columnMapping,
            ChunkAccumulator accumulator,
            NormalizationStats stats,
            Map<String, SeenLocation> seenDiplomaNumbers
    ) {
        stats.incrementTotalRows(sourceFileName);

        try {
            ParsedRow row = parseRow(rawRow, columnMapping, universityCode);
            SeenLocation firstSeen = seenDiplomaNumbers.putIfAbsent(row.diplomaNumber(), new SeenLocation(sourceFileName, rawRow.rowNumber()));
            if (firstSeen != null) {
                throw new ImportRowException(
                        CODE_DUPLICATE_DIPLOMA_NUMBER,
                        "duplicate diploma_number in import batch; first seen at " + firstSeen.sourceFileName() + " row " + firstSeen.rowNumber()
                );
            }
            accumulator.add(new NormalizedImportRow(
                    sourceFileName,
                    rawRow.rowNumber(),
                    row.studentExternalId(),
                    row.fullName(),
                    row.diplomaNumber(),
                    row.programName(),
                    row.graduationYear()
            ));
        } catch (ImportRowException ex) {
            stats.incrementFailedRows();
            insertImportError(importJobId, sourceFileName, rawRow.rowNumber(), ex.code(), ex.getMessage());
        }
    }

    private void processLockedChunk(LockedImportChunk chunk) {
        Instant now = Instant.now(clock);
        jdbcClient.sql("""
                update import_chunks
                set status = :status, updated_at = :updatedAt
                where id = :id
                """)
                .param("status", ImportChunkStatus.processing.name())
                .param("updatedAt", JdbcTime.timestamp(now))
                .param("id", chunk.id())
                .update();

        UUID universityId = jdbcClient.sql("select university_id from import_jobs where id = :id")
                .param("id", chunk.importJobId())
                .query(UUID.class)
                .single();
        String universityCode = jdbcClient.sql("select code from universities where id = :id")
                .param("id", universityId)
                .query(String.class)
                .single();

        int processedRows = 0;
        int failedRows = 0;

        try (InputStream input = objectStorageService.getObjectStream(chunk.objectKey());
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    NormalizedImportRow row = objectMapper.readValue(line, NormalizedImportRow.class);
                    UUID studentId = findOrCreateStudent(row.studentExternalId(), row.fullName());
                    UpsertedDiploma diploma = upsertDiploma(universityId, universityCode, studentId, row);
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("diploma_id", diploma.id().toString());
                    payload.put("verification_token", diploma.verificationToken());
                    payload.put("university_code", universityCode);
                    payload.put("diploma_number", row.diplomaNumber());
                    payload.put("student_name", row.fullName());
                    payload.put("student_name_masked", maskingService.maskFullName(row.fullName()));
                    payload.put("program_name", row.programName());
                    payload.put("record_hash", diploma.recordHash());
                    payload.put("status", DiplomaStatus.valid.name());
                    payload.put("graduation_year", row.graduationYear());
                    outboxService.append("diploma", diploma.id(), diploma.eventType(), payload);
                    processedRows++;
                } catch (ImportRowException ex) {
                    failedRows++;
                    insertImportError(chunk.importJobId(), chunk.sourceFileName(), 0, ex.code(), ex.getMessage());
                } catch (Exception ex) {
                    failedRows++;
                    insertImportError(chunk.importJobId(), chunk.sourceFileName(), 0, CODE_PERSISTENCE_ERROR, "database write failed");
                }
            }
        } catch (Exception ex) {
            failedRows = chunk.rowCount();
            insertImportError(chunk.importJobId(), chunk.sourceFileName(), 0, CODE_INVALID_FILE_FORMAT, "failed to read normalized chunk");
        }

        Instant finishedAt = Instant.now(clock);
        jdbcClient.sql("""
                update import_chunks
                set status = :status,
                    processed_rows = :processedRows,
                    failed_rows = :failedRows,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("status", failedRows > 0 && processedRows == 0 ? ImportChunkStatus.failed.name() : ImportChunkStatus.completed.name())
                .param("processedRows", processedRows)
                .param("failedRows", failedRows)
                .param("updatedAt", JdbcTime.timestamp(finishedAt))
                .param("id", chunk.id())
                .update();

        jdbcClient.sql("""
                update import_jobs
                set processed_rows = processed_rows + :processedDelta,
                    failed_rows = failed_rows + :failedDelta,
                    completed_chunks = completed_chunks + 1,
                    status = :status,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("processedDelta", processedRows)
                .param("failedDelta", failedRows)
                .param("status", ImportJobStatus.processing.name())
                .param("updatedAt", JdbcTime.timestamp(finishedAt))
                .param("id", chunk.importJobId())
                .update();

        finalizeImportIfReady(chunk.importJobId(), universityId);
    }

    private void finalizeImportIfReady(UUID importJobId, UUID universityId) {
        Long remaining = jdbcClient.sql("""
                select count(*)
                from import_chunks
                where import_job_id = :importJobId and status in ('pending', 'processing')
                """)
                .param("importJobId", importJobId)
                .query(Long.class)
                .single();

        if (remaining != null && remaining > 0) {
            return;
        }

        ImportJobResponse job = getImportInternal(importJobId);
        ImportJobStatus finalStatus = resolveFinalStatus(job.processedRows(), job.failedRows());
        Instant now = Instant.now(clock);
        jdbcClient.sql("""
                update import_jobs
                set status = :status,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("status", finalStatus.name())
                .param("updatedAt", JdbcTime.timestamp(now))
                .param("id", importJobId)
                .update();

        appendImportCompleted(importJobId, universityId, finalStatus, job.processedRows(), job.failedRows(), job.totalRows() == null ? 0 : job.totalRows());
    }

    private void appendImportCompleted(UUID importJobId, UUID universityId, ImportJobStatus finalStatus, int processedRows, int failedRows, int totalRows) {
        outboxService.append("import_job", importJobId, "import.completed.v1", Map.of(
                "import_job_id", importJobId.toString(),
                "university_id", universityId.toString(),
                "status", finalStatus.name(),
                "processed_rows", processedRows,
                "failed_rows", failedRows,
                "total_rows", totalRows
        ));
    }

    private ImportJobResponse getImportInternal(UUID importJobId) {
        return jdbcClient.sql("""
                select id, university_id, object_key, upload_session_id, status, source_format, file_count,
                       total_rows, processed_rows, failed_rows, total_chunks, completed_chunks, created_at, updated_at
                from import_jobs
                where id = :id
                """)
                .param("id", importJobId)
                .query(importJobRowMapper())
                .optional()
                .orElseThrow(() -> new NotFoundException("import job not found"));
    }

    private List<ImportJobErrorResponse> fetchImportErrors(UUID importJobId) {
        return jdbcClient.sql("""
                select id, import_job_id, source_file_name, row_number, code, message, created_at
                from import_job_errors
                where import_job_id = :importJobId
                order by created_at asc, source_file_name asc, row_number asc
                """)
                .param("importJobId", importJobId)
                .query((rs, rowNum) -> new ImportJobErrorResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("import_job_id", UUID.class),
                        rs.getString("source_file_name"),
                        rs.getInt("row_number"),
                        rs.getString("code"),
                        rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    private void validateSessionFileRequest(UploadSessionFileRequest fileRequest) {
        String fileName = requireValidUploadEnvelope(fileRequest.fileName(), fileRequest.fileSizeBytes());
        if (fileRequest.fileSizeBytes() > properties.importConfig().maxFileSizeBytes()) {
            throw new BadRequestException("file is too large");
        }
        extensionOf(fileName);
    }

    private String requireValidUploadEnvelope(String originalFilename, long fileSizeBytes) {
        if (fileSizeBytes <= 0) {
            throw new BadRequestException("import file is empty");
        }
        if (fileSizeBytes > properties.importConfig().maxFileSizeBytes()) {
            throw new BadRequestException("file is too large");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BadRequestException("file name is required");
        }
        String normalizedFilename = originalFilename.trim();
        String extension = extensionOf(normalizedFilename);
        if (!"csv".equals(extension) && !"xlsx".equals(extension)) {
            throw new BadRequestException("unsupported file type; use .csv or .xlsx");
        }
        return normalizedFilename;
    }

    private void validateUploadFileContents(String fileName, byte[] bytes) {
        if ("xlsx".equals(extensionOf(fileName))) {
            validateSpreadsheetHeaders(bytes);
            return;
        }
        validateCsvHeaders(bytes);
    }

    private void validateCsvHeaders(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] rawLines = content.split("\\R");
        for (String rawLine : rawLines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            requireHeaderMapping(splitCsvLine(rawLine));
            return;
        }
        throw new BadRequestException("import file does not contain header row");
    }

    private void validateSpreadsheetHeaders(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) {
                throw new BadRequestException("xlsx import must contain exactly one data sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                List<String> cells = extractCells(row, formatter);
                if (cells.isEmpty() || cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                requireHeaderMapping(cells);
                return;
            }
            throw new BadRequestException("xlsx import does not contain header row");
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("failed to parse xlsx import");
        }
    }

    private byte[] readUploadBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new BadRequestException("failed to read import file");
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
        String delimiter = rawLine.chars().filter(ch -> ch == ';').count() > rawLine.chars().filter(ch -> ch == ',').count() ? ";" : ",";
        String[] parts = rawLine.split(delimiter, -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private ColumnMapping requireHeaderMapping(List<String> cells) {
        int studentExternalId = -1;
        int fullName = -1;
        int diplomaNumber = -1;
        int programName = -1;
        int graduationYear = -1;

        for (int index = 0; index < cells.size(); index++) {
            String normalized = normalizeHeader(cells.get(index));
            switch (normalized) {
                case "studentexternalid", "studentid", "student":
                    studentExternalId = index;
                    break;
                case "fullname", "фио", "name":
                    fullName = index;
                    break;
                case "diplomanumber", "номердиплома":
                    diplomaNumber = index;
                    break;
                case "programname", "program", "специальность":
                    programName = index;
                    break;
                case "graduationyear", "годвыпуска":
                    graduationYear = index;
                    break;
                default:
                    break;
            }
        }

        if (fullName < 0 || diplomaNumber < 0 || programName < 0 || graduationYear < 0) {
            throw new BadRequestException(REQUIRED_HEADERS_MESSAGE);
        }
        return new ColumnMapping(studentExternalId, fullName, diplomaNumber, programName, graduationYear);
    }

    private ParsedRow parseRow(RawImportRow row, ColumnMapping columnMapping, String universityCode) {
        String fullName = normalizeFullName(cellAt(row, columnMapping.fullName()));
        String diplomaNumber = cellAt(row, columnMapping.diplomaNumber()).trim();
        String programName = cellAt(row, columnMapping.programName()).trim();
        String studentExternalId = columnMapping.studentExternalId() >= 0 ? cellAt(row, columnMapping.studentExternalId()).trim() : "";
        Integer graduationYear;

        if (fullName.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "full_name is required");
        }
        if (!CYRILLIC_FULL_NAME.matcher(fullName).matches()) {
            throw new ImportRowException(CODE_INVALID_FULL_NAME, INVALID_FULL_NAME_MESSAGE);
        }
        if (diplomaNumber.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "diploma_number is required");
        }
        if (programName.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "program_name is required");
        }

        String rawGraduationYear = cellAt(row, columnMapping.graduationYear()).trim();
        if (rawGraduationYear.isBlank()) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "graduation_year is required");
        }
        try {
            graduationYear = Integer.parseInt(rawGraduationYear);
        } catch (NumberFormatException ex) {
            throw new ImportRowException(CODE_MISSING_REQUIRED_FIELD, "graduation_year must be a number");
        }

        if (studentExternalId.isBlank()) {
            studentExternalId = universityCode.trim().toUpperCase(Locale.ROOT) + ":" + diplomaNumber;
        }

        return new ParsedRow(studentExternalId, fullName, diplomaNumber, programName, graduationYear);
    }

    private String normalizeFullName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private String cellAt(RawImportRow row, int index) {
        if (index < 0 || index >= row.cells().size()) {
            throw new ImportRowException(CODE_INVALID_COLUMN_COUNT, "required column is missing");
        }
        return row.cells().get(index);
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
                .param("createdAt", JdbcTime.timestamp(Instant.now(clock)))
                .update();
        return studentId;
    }

    private UpsertedDiploma upsertDiploma(UUID universityId, String universityCode, UUID studentId, NormalizedImportRow row) {
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
        String recordHash = recordHashService.compute(universityCode, row.diplomaNumber(), row.fullName(), row.programName(), row.graduationYear());

        if (existingDiploma.isPresent()) {
            jdbcClient.sql("""
                    update diplomas
                    set student_id = :studentId,
                        program_name = :programName,
                        graduation_year = :graduationYear,
                        record_hash = :recordHash,
                        status = :status,
                        revoked_at = null,
                        revoke_reason = null,
                        updated_at = :updatedAt
                    where id = :id
                    """)
                    .param("studentId", studentId)
                    .param("programName", row.programName())
                    .param("graduationYear", row.graduationYear())
                    .param("recordHash", recordHash)
                    .param("status", DiplomaStatus.valid.name())
                    .param("updatedAt", JdbcTime.timestamp(now))
                    .param("id", diplomaId)
                    .update();
        } else {
            jdbcClient.sql("""
                    insert into diplomas (
                        id, university_id, student_id, diploma_number, program_name, graduation_year, record_hash, status, created_at, updated_at
                    )
                    values (
                        :id, :universityId, :studentId, :diplomaNumber, :programName, :graduationYear, :recordHash, :status, :createdAt, :updatedAt
                    )
                    """)
                    .param("id", diplomaId)
                    .param("universityId", universityId)
                    .param("studentId", studentId)
                    .param("diplomaNumber", row.diplomaNumber())
                    .param("programName", row.programName())
                    .param("graduationYear", row.graduationYear())
                    .param("recordHash", recordHash)
                    .param("status", DiplomaStatus.valid.name())
                    .param("createdAt", JdbcTime.timestamp(now))
                    .param("updatedAt", JdbcTime.timestamp(now))
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
                            .param("createdAt", JdbcTime.timestamp(now))
                            .update();
                    return newToken;
                });

        return new UpsertedDiploma(diplomaId, verificationToken, recordHash, existingDiploma.isPresent() ? "diploma.updated.v1" : "diploma.created.v1");
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

    private void failImport(LockedImportJob job, String sourceFileName, String code, String message) {
        insertImportError(job.id(), sourceFileName, 0, code, message);
        jdbcClient.sql("""
                update import_jobs
                set total_rows = 0,
                    processed_rows = 0,
                    failed_rows = 1,
                    total_chunks = 0,
                    completed_chunks = 0,
                    status = :status,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("status", ImportJobStatus.failed.name())
                .param("updatedAt", JdbcTime.timestamp(Instant.now(clock)))
                .param("id", job.id())
                .update();

        appendImportCompleted(job.id(), job.universityId(), ImportJobStatus.failed, 0, 1, 0);
    }

    private void insertImportError(UUID importJobId, String sourceFileName, int rowNumber, String code, String message) {
        jdbcClient.sql("""
                insert into import_job_errors (id, import_job_id, source_file_name, row_number, code, message, created_at)
                values (:id, :importJobId, :sourceFileName, :rowNumber, :code, :message, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("importJobId", importJobId)
                .param("sourceFileName", sourceFileName)
                .param("rowNumber", rowNumber)
                .param("code", code)
                .param("message", message)
                .param("createdAt", JdbcTime.timestamp(Instant.now(clock)))
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

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType.trim();
    }

    private String extensionOf(String fileName) {
        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".csv")) {
            return "csv";
        }
        if (normalized.endsWith(".xlsx")) {
            return "xlsx";
        }
        throw new BadRequestException("unsupported file type; use .csv or .xlsx");
    }

    private String normalizeSha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to hash import file", ex);
        }
    }

    private String universityIdPrefix(UUID universityId) {
        return "normalized/" + universityId;
    }

    private RowMapper<ImportJobResponse> importJobRowMapper() {
        return (rs, rowNum) -> new ImportJobResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("university_id", UUID.class),
                rs.getString("object_key"),
                rs.getObject("upload_session_id", UUID.class),
                rs.getString("status"),
                rs.getString("source_format"),
                rs.getInt("file_count"),
                (Integer) rs.getObject("total_rows"),
                rs.getInt("processed_rows"),
                rs.getInt("failed_rows"),
                rs.getInt("total_chunks"),
                rs.getInt("completed_chunks"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private final class ChunkAccumulator {
        private final UUID importJobId;
        private final String prefix;
        private final List<String> lines = new ArrayList<>();
        private int chunkIndex = 0;
        private int nextRowNumber = 1;
        private int currentBytes = 0;

        private ChunkAccumulator(UUID importJobId, String basePrefix) {
            this.importJobId = importJobId;
            this.prefix = basePrefix + "/" + importJobId;
        }

        private void add(NormalizedImportRow row) {
            try {
                String json = objectMapper.writeValueAsString(row);
                lines.add(json);
                currentBytes += json.getBytes(StandardCharsets.UTF_8).length + 1;
                if (lines.size() >= properties.importConfig().chunkSize() || currentBytes >= properties.importConfig().chunkPayloadBytes()) {
                    flush();
                }
            } catch (Exception ex) {
                throw new IllegalStateException("failed to serialize normalized row", ex);
            }
        }

        private void flush() {
            if (lines.isEmpty()) {
                return;
            }
            int chunkSize = lines.size();
            int rowFrom = nextRowNumber;
            int rowTo = nextRowNumber + chunkSize - 1;
            String objectKey = prefix + "/chunk-" + chunkIndex + ".ndjson";
            String content = String.join("\n", lines) + "\n";

            objectStorageService.putObject(objectKey, content.getBytes(StandardCharsets.UTF_8), "application/x-ndjson");

            jdbcClient.sql("""
                    insert into import_chunks (
                        id, import_job_id, source_file_name, chunk_index, object_key, row_from, row_to, row_count,
                        status, processed_rows, failed_rows, created_at, updated_at
                    ) values (
                        :id, :importJobId, :sourceFileName, :chunkIndex, :objectKey, :rowFrom, :rowTo, :rowCount,
                        :status, 0, 0, :createdAt, :updatedAt
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("importJobId", importJobId)
                    .param("sourceFileName", "mixed")
                    .param("chunkIndex", chunkIndex)
                    .param("objectKey", objectKey)
                    .param("rowFrom", rowFrom)
                    .param("rowTo", rowTo)
                    .param("rowCount", chunkSize)
                    .param("status", ImportChunkStatus.pending.name())
                    .param("createdAt", JdbcTime.timestamp(Instant.now(clock)))
                    .param("updatedAt", JdbcTime.timestamp(Instant.now(clock)))
                    .update();

            chunkIndex++;
            nextRowNumber = rowTo + 1;
            lines.clear();
            currentBytes = 0;
        }

        private int totalChunks() {
            return chunkIndex;
        }

        private String prefix() {
            return prefix;
        }
    }

    private static final class NormalizationStats {
        private int totalRows;
        private int failedRows;
        private final Map<String, Boolean> filesWithData = new HashMap<>();

        private void incrementTotalRows(String fileName) {
            totalRows++;
            filesWithData.put(fileName, true);
        }

        private void incrementFailedRows() {
            failedRows++;
        }

        private int totalRows() {
            return totalRows;
        }

        private int failedRows() {
            return failedRows;
        }

        private int processedRows() {
            return totalRows - failedRows;
        }

        private boolean seenDataRowForFile(String fileName) {
            return filesWithData.getOrDefault(fileName, false);
        }
    }

    private record UploadSessionRecord(UUID id, UUID universityId, String status, Instant expiresAt) {}

    private record SessionImportFile(UUID fileId, String fileName, String contentType, long fileSizeBytes, String fileSha256, String objectKey) {}

    private record ManifestPayload(UUID sessionId, List<ManifestFilePayload> files) {}

    private record ManifestFilePayload(String fileName, String contentType, long fileSizeBytes, String fileSha256, String objectKey) {}

    private record LockedImportJob(UUID id, UUID universityId, String objectKey) {}

    private record LockedImportChunk(UUID id, UUID importJobId, String sourceFileName, String objectKey, int rowCount) {}

    private record ColumnMapping(int studentExternalId, int fullName, int diplomaNumber, int programName, int graduationYear) {}

    private record RawImportRow(int rowNumber, List<String> cells) {}

    private record ParsedRow(String studentExternalId, String fullName, String diplomaNumber, String programName, Integer graduationYear) {}

    private record NormalizedImportRow(
            String sourceFileName,
            int rowNumber,
            String studentExternalId,
            String fullName,
            String diplomaNumber,
            String programName,
            Integer graduationYear
    ) {}

    private record SeenLocation(String sourceFileName, int rowNumber) {}

    private record UpsertedDiploma(UUID id, String verificationToken, String recordHash, String eventType) {}

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
