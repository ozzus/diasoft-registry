package com.diasoft.registry.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ShareLinkRequest(
        @Min(1) @Max(50) Integer maxViews
) {}
