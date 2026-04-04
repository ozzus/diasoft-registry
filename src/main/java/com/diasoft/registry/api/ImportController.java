package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.ImportJobErrorResponse;
import com.diasoft.registry.api.dto.ImportJobResponse;
import com.diasoft.registry.service.ImportJobService;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ImportController {
    private final ImportJobService importJobService;

    public ImportController(ImportJobService importJobService) {
        this.importJobService = importJobService;
    }

    @PostMapping(path = "/universities/{id}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobResponse createImport(@PathVariable("id") UUID universityId, @RequestPart("file") @NotNull MultipartFile file) {
        return importJobService.createImport(universityId, file);
    }

    @GetMapping("/universities/{id}/imports")
    public List<ImportJobResponse> listImports(@PathVariable("id") UUID universityId) {
        return importJobService.listImports(universityId);
    }

    @GetMapping("/imports/{id}")
    public ImportJobResponse getImport(@PathVariable("id") UUID importJobId) {
        return importJobService.getImport(importJobId);
    }

    @GetMapping("/imports/{id}/errors")
    public List<ImportJobErrorResponse> getImportErrors(@PathVariable("id") UUID importJobId) {
        return importJobService.getImportErrors(importJobId);
    }
}
