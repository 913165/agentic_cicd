package org.example.springai_agent_terraform_deploy_do.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Catalog entry purpose: build (CI) vs deploy (CD).
 * Deploy flow is unchanged for {@link #DEPLOY}; build job wiring comes later.
 */
public enum PipelinePhase {
    BUILD("build", "Build (CI)"),
    DEPLOY("deploy", "Deploy (CD)");

    private final String id;
    private final String label;

    PipelinePhase(String id, String label) {
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

    public boolean isBuild() {
        return this == BUILD;
    }

    public boolean isDeploy() {
        return this == DEPLOY;
    }

    @JsonCreator
    public static PipelinePhase from(String value) {
        if (value == null || value.isBlank()) {
            return DEPLOY;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        if ("ci".equals(normalized) || "build".equals(normalized)) {
            return BUILD;
        }
        if ("cd".equals(normalized) || "deploy".equals(normalized)) {
            return DEPLOY;
        }
        for (PipelinePhase phase : values()) {
            if (phase.id.equals(normalized) || phase.name().equalsIgnoreCase(normalized)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown phase: " + value + ". Allowed: build, deploy");
    }
}
