package com.diasoft.registry.model;

public enum ImportJobStatus {
    pending,
    uploaded,
    normalizing,
    chunked,
    processing,
    completed,
    partially_failed,
    failed
}
