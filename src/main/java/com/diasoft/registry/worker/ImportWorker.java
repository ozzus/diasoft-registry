package com.diasoft.registry.worker;

import com.diasoft.registry.service.ImportJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.runtime.mode", havingValue = "import-worker")
public class ImportWorker {
    private final ImportJobService importJobService;

    public ImportWorker(ImportJobService importJobService) {
        this.importJobService = importJobService;
    }

    @Scheduled(fixedDelayString = "${app.import.poll-delay:5000}")
    public void poll() {
        importJobService.processNextPendingImport();
    }
}
