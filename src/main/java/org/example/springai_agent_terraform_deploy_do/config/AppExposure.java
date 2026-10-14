package org.example.springai_agent_terraform_deploy_do.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether the app is exposed externally (LoadBalancer) or kept internal (ClusterIP / private).
 * Used later to pick the matching K8s deploy job.
 */
public enum AppExposure {
    EXTERNAL("external", "External (LoadBalancer)"),
    INTERNAL("internal", "Internal");

    private final String id;
    private final String label;

    AppExposure(String id, String label) {
        this.id = id;
        this.label = label;
    }

    @JsonValue
    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static AppExposure from(String value) {
        if (value == null || value.isBlank()) {
            return EXTERNAL;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        for (AppExposure type : values()) {
            if (type.id.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown exposure: " + value
                + ". Allowed: external, internal");
    }
}
