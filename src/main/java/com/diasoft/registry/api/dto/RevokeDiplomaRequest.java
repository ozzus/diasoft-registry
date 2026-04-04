package com.diasoft.registry.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RevokeDiplomaRequest(
        @NotBlank String reason
) {}
