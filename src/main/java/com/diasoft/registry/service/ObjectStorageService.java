package com.diasoft.registry.service;

import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorageService {
    void putObject(String key, InputStream inputStream, long contentLength, String contentType);

    void putObject(String key, byte[] bytes, String contentType);

    byte[] getObject(String key);

    InputStream getObjectStream(String key);

    boolean objectExists(String key);

    ObjectUploadTarget createUploadTarget(String key, String contentType, Duration ttl);
}
