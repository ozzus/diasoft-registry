package com.diasoft.registry.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class ObjectStorageConfig {
    @Bean
    S3Client s3Client(AppProperties properties) {
        AppProperties.ObjectStorage cfg = properties.objectStorage();
        return S3Client.builder()
                .endpointOverride(URI.create(cfg.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.pathStyleAccess()).build())
                .region(Region.of(cfg.region()))
                .build();
    }
}
