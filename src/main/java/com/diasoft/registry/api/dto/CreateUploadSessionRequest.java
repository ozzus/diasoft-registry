package com.diasoft.registry.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateUploadSessionRequest(
        @NotEmpty List<@Valid UploadSessionFileRequest> files
) {}
