package com.diasoft.registry.worker;

import com.diasoft.registry.config.AppProperties;
import com.diasoft.registry.service.OutboxPublisherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.runtime.mode", havingValue = "outbox-publisher")
public class OutboxPublisherWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);

    private final OutboxPublisherService outboxPublisherService;
    private final AppProperties properties;

    public OutboxPublisherWorker(OutboxPublisherService outboxPublisherService, AppProperties properties) {
        this.outboxPublisherService = outboxPublisherService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay:5000}")
    public void poll() {
        try {
            outboxPublisherService.publishBatch(properties.outbox().batchSize());
        } catch (Exception ex) {
            log.warn("outbox publish batch failed", ex);
        }
    }
}
