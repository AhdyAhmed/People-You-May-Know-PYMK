package com.ahdyahmed.pymk.orchestrator;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Versioned Redis key and TTL controls for both recommendation cache layers. */
@Component
@ConfigurationProperties(prefix = "pymk.cache")
public class RecommendationCacheProperties {

    private static final Pattern KEY_COMPONENT = Pattern.compile("[A-Za-z0-9._-]+");

    private boolean enabled = true;
    private String keyPrefix = "pymk";
    private String schemaVersion = "v1";
    private String pipelineVersion = "l0-rrf-v1";
    private String modelVersion = "naive-mutual-v1";
    private Duration l0Ttl = Duration.ofMinutes(30);
    private Duration resultTtl = Duration.ofMinutes(15);

    public void validate() {
        validateKeyComponent(keyPrefix, "keyPrefix");
        validateKeyComponent(schemaVersion, "schemaVersion");
        validateKeyComponent(pipelineVersion, "pipelineVersion");
        validateKeyComponent(modelVersion, "modelVersion");
        validateTtl(l0Ttl, "l0Ttl");
        validateTtl(resultTtl, "resultTtl");
    }

    private static void validateKeyComponent(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!KEY_COMPONENT.matcher(value).matches()) {
            throw new IllegalStateException(name + " must contain only letters, numbers, dot, underscore, or dash");
        }
    }

    private static void validateTtl(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public void setPipelineVersion(String pipelineVersion) {
        this.pipelineVersion = pipelineVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public Duration getL0Ttl() {
        return l0Ttl;
    }

    public void setL0Ttl(Duration l0Ttl) {
        this.l0Ttl = l0Ttl;
    }

    public Duration getResultTtl() {
        return resultTtl;
    }

    public void setResultTtl(Duration resultTtl) {
        this.resultTtl = resultTtl;
    }
}
