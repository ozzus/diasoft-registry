package com.diasoft.registry.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UploadSessionFileRequest(
        @NotBlank String fileName,
        String contentType,
        @Min(1) long fileSizeBytes,
        String sha256
) {}
