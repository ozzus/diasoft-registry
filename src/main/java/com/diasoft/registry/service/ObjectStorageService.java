package com.diasoft.registry.service;

import java.io.InputStream;

public interface ObjectStorageService {
    void putObject(String key, InputStream inputStream, long contentLength, String contentType);

    byte[] getObject(String key);
}
