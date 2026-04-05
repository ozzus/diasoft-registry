package com.diasoft.registry.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Runtime runtime,
        Security security,
        Http http,
        Import importConfig,
        ObjectStorage objectStorage,
        Outbox outbox,
        Gateway gateway,
        ShareLink shareLink
) {
    public record Runtime(
            @NotBlank String mode
    ) {}

    public record Security(
            boolean enabled,
            @NotNull DevIdentity devIdentity
    ) {}

    public record DevIdentity(
            @NotBlank String subject,
            @NotNull List<@NotBlank String> roles,
            String universityId,
            String studentExternalId
    ) {}

    public record Http(
            @NotNull List<@NotBlank String> allowedOrigins
    ) {}

    public record Import(
            @Min(1) int chunkSize,
            @Min(1) int chunkPayloadBytes,
            @Min(1) long maxFileSizeBytes,
            @Min(1) int maxRowsPerFile,
            @Min(1) int maxFilesPerSession,
            @NotNull Duration pollDelay,
            @NotNull Duration presignTtl
    ) {}

    public record ObjectStorage(
            @NotBlank String endpoint,
            @NotBlank String bucket,
            @NotBlank String region,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            boolean pathStyleAccess,
            boolean autoCreateBucket
    ) {}

    public record Outbox(
            @Min(1) int batchSize,
            @NotNull Duration pollDelay,
            @NotBlank String producer,
            @Min(1) int retryAttempts,
            @NotNull Duration retryBackoff,
            @NotNull Duration retryMaxBackoff
    ) {}

    public record Gateway(
            @NotBlank String publicBaseUrl,
            String serviceToken
    ) {}

    public record ShareLink(
            @NotNull Duration ttl,
            @Min(1) int maxViews
    ) {}
}
