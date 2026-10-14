package org.example.springai_agent_terraform_deploy_do.config;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Catalog entry for build (CI) or deploy (CD).
 * {@code lastExecutedAt} / {@code lastStatus} / {@code lastDurationSec} track the latest job run
 * so the dashboard can sort most-recent first.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApplicationConfig(
        String name,
        String jenkinsJob,
        String description,
        String healthUrl,
        String cluster,
        String namespace,
        JobType jobType,
        AppExposure exposure,
        Integer containerPort,
        Integer servicePort,
        String jenkinsApplication,
        PipelinePhase phase,
        String imageVersion,
        String lastExecutedAt,
        String lastStatus,
        Integer lastDurationSec
) {
    public ApplicationConfig {
        if (jobType == null) {
            jobType = JobType.JENKINS;
        }
        if (exposure == null) {
            exposure = AppExposure.EXTERNAL;
        }
        if (phase == null) {
            phase = PipelinePhase.DEPLOY;
        }
    }

    public ApplicationConfig(String name, String jenkinsJob, String description, String healthUrl) {
        this(name, jenkinsJob, description, healthUrl, null, null, JobType.JENKINS, AppExposure.EXTERNAL,
                null, null, null, PipelinePhase.DEPLOY, null, null, null, null);
    }

    public ApplicationConfig(String name, String jenkinsJob, String description, String healthUrl,
                             String cluster, String namespace) {
        this(name, jenkinsJob, description, healthUrl, cluster, namespace, JobType.JENKINS, AppExposure.EXTERNAL,
                null, null, null, PipelinePhase.DEPLOY, null, null, null, null);
    }

    public String resolvedJenkinsApplication() {
        if (jenkinsApplication != null && !jenkinsApplication.isBlank()) {
            return jenkinsApplication.trim();
        }
        return name;
    }

    public String resolvedImageVersion() {
        if (imageVersion != null && !imageVersion.isBlank()) {
            return imageVersion.trim();
        }
        return "1.0.1";
    }

    public String resolvedExposeService() {
        return exposure == AppExposure.INTERNAL ? "false" : "true";
    }

    public ApplicationConfig withExecution(String executedAt, String status, Integer durationSec) {
        return new ApplicationConfig(
                name, jenkinsJob, description, healthUrl, cluster, namespace,
                jobType, exposure, containerPort, servicePort, jenkinsApplication,
                phase, imageVersion, executedAt, status, durationSec);
    }

    public ApplicationConfig withPreservedExecution(ApplicationConfig previous) {
        if (previous == null) {
            return this;
        }
        if (lastExecutedAt != null && !lastExecutedAt.isBlank()) {
            return this;
        }
        return withExecution(previous.lastExecutedAt(), previous.lastStatus(), previous.lastDurationSec());
    }
}
