package com.diasoft.registry.service;

import com.diasoft.registry.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3ObjectStorageService implements ObjectStorageService {
    private final S3Client s3Client;
    private final AppProperties properties;

    public S3ObjectStorageService(S3Client s3Client, AppProperties properties) {
        this.s3Client = s3Client;
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
    public byte[] getObject(String key) {
        ResponseBytes<?> object = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(properties.objectStorage().bucket())
                        .key(key)
                        .build()
        );
        return object.asByteArray();
    }
}
