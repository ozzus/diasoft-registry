package com.diasoft.registry.api.dto;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        String subject,
        List<String> roles,
        UUID universityId,
        String studentExternalId
) {}
