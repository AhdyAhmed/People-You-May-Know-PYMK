package com.ahdyahmed.pymk.batch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tuning and temporal defaults for the Day 18 training dataset export. */
@Component
@ConfigurationProperties("pymk.batch.training-export")
public class TrainingDatasetExportProperties {

    private int chunkSize = 1_000;
    private int fetchSize = 1_000;
    private Duration observationWindow = Duration.ofDays(30);

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public Duration getObservationWindow() {
        return observationWindow;
    }

    public void setObservationWindow(Duration observationWindow) {
        this.observationWindow = observationWindow;
    }

    void validate() {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("training-export chunk-size must be positive");
        }
        if (fetchSize <= 0) {
            throw new IllegalArgumentException("training-export fetch-size must be positive");
        }
        if (observationWindow == null || observationWindow.isZero()
                || observationWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "training-export observation-window must be positive");
        }
    }
}
