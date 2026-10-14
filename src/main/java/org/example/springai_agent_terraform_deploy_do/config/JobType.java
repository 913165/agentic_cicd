package org.example.springai_agent_terraform_deploy_do.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How an application deployment is triggered.
 * Only {@link #JENKINS} is implemented today; other types are reserved for later.
 */
public enum JobType {
    JENKINS("jenkins", "Jenkins job"),
    AZURE_CICD("azure_cicd", "Azure CI/CD"),
    OTHER("other", "Other");

    private final String id;
    private final String label;

    JobType(String id, String label) {
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
    public static JobType from(String value) {
        if (value == null || value.isBlank()) {
            return JENKINS;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        for (JobType type : values()) {
            if (type.id.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown jobType: " + value
                + ". Allowed: jenkins, azure_cicd, other");
    }
}
