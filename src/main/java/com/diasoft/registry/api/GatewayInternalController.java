package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.DiplomaResponse;
import com.diasoft.registry.api.dto.GatewayDiplomaListResponse;
import com.diasoft.registry.api.dto.GatewayImportAcceptedResponse;
import com.diasoft.registry.api.dto.GatewayImportErrorsResponse;
import com.diasoft.registry.api.dto.GatewayImportRowErrorResponse;
import com.diasoft.registry.api.dto.GatewayQrResponse;
import com.diasoft.registry.api.dto.GatewayShareLinkRequest;
import com.diasoft.registry.api.dto.ImportJobResponse;
import com.diasoft.registry.api.dto.RevokeDiplomaRequest;
import com.diasoft.registry.api.dto.ShareLinkResponse;
import com.diasoft.registry.service.DiplomaService;
import com.diasoft.registry.service.ImportJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/internal/gateway")
public class GatewayInternalController {
    private final DiplomaService diplomaService;
    private final ImportJobService importJobService;

    public GatewayInternalController(DiplomaService diplomaService, ImportJobService importJobService) {
        this.diplomaService = diplomaService;
        this.importJobService = importJobService;
    }

    @GetMapping("/university/diplomas")
    public GatewayDiplomaListResponse listUniversityDiplomas(
            @RequestParam("universityId") UUID universityId,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page
    ) {
        return diplomaService.listUniversityDiplomasForGateway(universityId, search, status, page, 20);
    }

    @PostMapping(path = "/university/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GatewayImportAcceptedResponse createImport(
            @RequestParam("universityId") UUID universityId,
            @RequestParam("file") @NotNull MultipartFile file
    ) {
        ImportJobResponse importJob = importJobService.createImportForUniversity(universityId, file);
        return new GatewayImportAcceptedResponse(importJob.id(), importJob.status());
    }

    @GetMapping("/university/imports/{jobId}")
    public ImportJobResponse getImport(
            @PathVariable("jobId") UUID jobId,
            @RequestParam("universityId") UUID universityId
    ) {
        return importJobService.getImportForUniversity(universityId, jobId);
    }

    @GetMapping("/university/imports/{jobId}/errors")
    public GatewayImportErrorsResponse getImportErrors(
            @PathVariable("jobId") UUID jobId,
            @RequestParam("universityId") UUID universityId
    ) {
        List<GatewayImportRowErrorResponse> errors = importJobService.getImportErrorsForUniversity(universityId, jobId).stream()
                .map(error -> new GatewayImportRowErrorResponse(error.rowNumber(), error.message()))
                .toList();
        return new GatewayImportErrorsResponse(errors);
    }

    @PostMapping("/university/diplomas/{id}/revoke")
    public DiplomaResponse revokeDiploma(
            @PathVariable("id") UUID diplomaId,
            @RequestParam("universityId") UUID universityId,
            @Valid @RequestBody RevokeDiplomaRequest request
    ) {
        return diplomaService.revokeForGateway(universityId, diplomaId, request.reason());
    }

    @GetMapping("/university/diplomas/{id}/qr")
    public GatewayQrResponse getQr(
            @PathVariable("id") UUID diplomaId,
            @RequestParam("universityId") UUID universityId
    ) {
        return diplomaService.getUniversityDiplomaQr(universityId, diplomaId);
    }

    @GetMapping("/student/diploma")
    public DiplomaResponse getStudentDiploma(@RequestParam("diplomaId") UUID diplomaId) {
        return diplomaService.getStudentDiplomaForGateway(diplomaId);
    }

    @PostMapping("/student/share-link")
    public ShareLinkResponse createStudentShareLink(
            @RequestParam("diplomaId") UUID diplomaId,
            @Valid @RequestBody GatewayShareLinkRequest request
    ) {
        return diplomaService.createShareLinkWithTtl(diplomaId, request.ttlSeconds());
    }

    @DeleteMapping("/student/share-link/{token}")
    @ResponseStatus(HttpStatus.OK)
    public void revokeStudentShareLink(
            @PathVariable("token") String token,
            @RequestParam("diplomaId") UUID diplomaId
    ) {
        diplomaService.revokeShareLink(diplomaId, token);
    }
}
