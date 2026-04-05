package com.diasoft.registry.service;

import com.diasoft.registry.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

@Service
public class S3ObjectStorageService implements ObjectStorageService {
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AppProperties properties;

    public S3ObjectStorageService(S3Client s3Client, S3Presigner s3Presigner, AppProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    @PostConstruct
    void ensureBucket() {
        if (!properties.objectStorage().autoCreateBucket()) {
            return;
        }

        String bucket = properties.objectStorage().bucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException ex) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception ex) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public void putObject(String key, InputStream inputStream, long contentLength, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.objectStorage().bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(inputStream, contentLength)
        );
    }

    @Override
    public void putObject(String key, byte[] bytes, String contentType) {
        putObject(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    @Override
    public byte[] getObject(String key) {
        ResponseBytes<?> object = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(properties.objectStorage().bucket())
                        .key(key)
                        .build()
        );
        return object.asByteArray();
    }

    @Override
    public InputStream getObjectStream(String key) {
        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(properties.objectStorage().bucket())
                        .key(key)
                        .build()
        );
    }

    @Override
    public boolean objectExists(String key) {
        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(properties.objectStorage().bucket())
                            .key(key)
                            .build()
            );
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public ObjectUploadTarget createUploadTarget(String key, String contentType, Duration ttl) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.objectStorage().bucket())
                .key(key)
                .contentType(contentType)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(request)
                        .build()
        );

        return new ObjectUploadTarget(
                "PUT",
                presigned.url().toString(),
                Map.of("Content-Type", contentType),
                Instant.now().plus(ttl)
        );
    }
}
