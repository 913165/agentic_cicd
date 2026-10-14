package org.example.springai_agent_terraform_deploy_do.tools;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationConfig;
import org.example.springai_agent_terraform_deploy_do.config.JobType;
import org.example.springai_agent_terraform_deploy_do.config.PipelinePhase;
import org.example.springai_agent_terraform_deploy_do.service.ApplicationHealthService;
import org.example.springai_agent_terraform_deploy_do.service.JenkinsClient;
import org.example.springai_agent_terraform_deploy_do.settings.JenkinsSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JenkinsDeploymentTool {

    private static final Logger log = LoggerFactory.getLogger(JenkinsDeploymentTool.class);
    private static final String DEFAULT_JOB = "common-deploy-job";

    private final ApplicationCatalog applicationCatalog;
    private final ApplicationHealthService applicationHealthService;
    private final JenkinsSettingsService jenkinsSettingsService;
    private final JenkinsClient jenkinsClient;

    @Value("${app.jenkins.deploy.job:common-deploy-job}")
    private String deployJobName;

    public JenkinsDeploymentTool(ApplicationCatalog applicationCatalog,
                                 ApplicationHealthService applicationHealthService,
                                 JenkinsSettingsService jenkinsSettingsService,
                                 JenkinsClient jenkinsClient) {
        this.applicationCatalog = applicationCatalog;
        this.applicationHealthService = applicationHealthService;
        this.jenkinsSettingsService = jenkinsSettingsService;
        this.jenkinsClient = jenkinsClient;
    }

    @Tool(description = "Trigger Jenkins common-deploy-job via API with APPLICATION, IMAGE_TAG, CONTAINER_PORT, SERVICE_PORT, REPLICAS, EXPOSE_SERVICE from the registered ApplicationConfig. Call with approved=true whenever the user message contains (approved). Prometheus health DOWN must NOT stop an approved deploy.")
    public String deployViaJenkins(
            @ToolParam(description = "Application or service name being deployed (must match a registered application name)") String applicationName,
            @ToolParam(description = "Must be true when the user clicked Approve / message contains (approved)", required = false) Boolean approved,
            @ToolParam(description = "Optional Jenkins job name override", required = false) String jobName,
            @ToolParam(description = "Optional image tag for Jenkins IMAGE_TAG (default: latest)", required = false) String imageTag) {

        boolean isApproved = Boolean.TRUE.equals(approved);
        String resolvedImageTag = (imageTag == null || imageTag.isBlank()) ? "latest" : imageTag.trim();
        log.info(">>> TOOL deployViaJenkins START application={}, approved={}, job={}, imageTag={}",
                applicationName, isApproved, jobName, resolvedImageTag);

        ApplicationConfig app = applicationCatalog.findByName(applicationName).orElse(null);
        if (app != null && app.phase() == PipelinePhase.BUILD) {
            return """
                    '%s' is registered as Build (CI), not Deploy (CD).
                    deployViaJenkins only runs Deploy (CD) catalog entries.
                    """.formatted(applicationName).trim();
        }
        if (app != null && app.jobType() != JobType.JENKINS) {
            return """
                    Job type '%s' is not implemented yet for application '%s'.
                    Only Jenkins triggers are supported.
                    """.formatted(app.jobType().getLabel(), applicationName).trim();
        }

        String resolvedJob = resolveJenkinsJob(applicationName, jobName);
        if (resolvedJob == null) {
            String known = applicationCatalog.values().stream()
                    .map(ApplicationConfig::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            return "Unknown application '%s'. Registered apps: %s.".formatted(applicationName, known);
        }

        if (!isApproved) {
            return """
                    BLOCKED: deploy requires explicit user approval.
                    No deploy was started.
                    Ask the user to Approve in the chat UI, then call again with approved=true.
                    """.trim();
        }

        if (app == null) {
            return "Application config missing for '%s' after job resolve.".formatted(applicationName);
        }

        ApplicationHealthService.HealthStatus health = applicationHealthService.checkOne(app);
        log.info("Prometheus health for {} before approved deploy: {} ({})",
                applicationName, health.status(), health.detail());

        if (!jenkinsSettingsService.isConfigured()) {
            return "Jenkins credentials are not configured. Open Settings and save URL / user / token.";
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("APPLICATION", app.resolvedJenkinsApplication());
        params.put("IMAGE_TAG", resolvedImageTag);
        params.put("CONTAINER_PORT", app.containerPort() == null ? "" : String.valueOf(app.containerPort()));
        params.put("SERVICE_PORT", app.servicePort() == null ? "" : String.valueOf(app.servicePort()));
        params.put("REPLICAS", "1");
        params.put("EXPOSE_SERVICE", app.resolvedExposeService());

        long startedMs = System.currentTimeMillis();
        try {
            JenkinsClient.JobRunResult result = jenkinsClient.triggerAndWait(resolvedJob, params);
            long durationSec = Math.max(0, (System.currentTimeMillis() - startedMs) / 1000);
            applicationCatalog.recordExecution(applicationName, result.ok(), durationSec);
            return buildToolResult(result, applicationName, resolvedJob);
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error("Jenkins deploy failed", e);
            return "Jenkins deployment execution error: " + e.getMessage();
        }
    }

    private String resolveJenkinsJob(String applicationName, String jobNameOverride) {
        if (jobNameOverride != null && !jobNameOverride.isBlank()) {
            return jobNameOverride.trim();
        }
        String fallback = deployJobName == null || deployJobName.isBlank() ? DEFAULT_JOB : deployJobName.trim();
        return applicationCatalog.findByName(applicationName)
                .map(app -> {
                    String j = app.jenkinsJob();
                    return (j == null || j.isBlank()) ? fallback : j.trim();
                })
                .orElse(null);
    }

    private static String buildToolResult(JenkinsClient.JobRunResult result,
                                          String application,
                                          String job) {
        boolean ok = result.ok();
        String status = ok ? "SUCCESS" : "FAILED";
        String title = ok
                ? "Deployed " + application + " successfully"
                : "Deployment of " + application + " failed";
        String json = """
                {"ok":%s,"title":"%s","application":"%s","job":"%s","status":"%s","exitCode":%d,"buildNumber":%d,"added":0,"changed":0,"destroyed":0}
                """.formatted(
                ok,
                escapeJson(title),
                escapeJson(application),
                escapeJson(job),
                escapeJson(status),
                ok ? 0 : 1,
                result.buildNumber()
        ).trim();

        String markerBlock = """
                <<<DEPLOY_RESULT>>>
                %s
                <<<END_DEPLOY_RESULT>>>
                """.formatted(json).trim();
        DeployResultHolder.setBlock(markerBlock);

        return """
                %s
                application=%s
                job=%s
                status=%s
                buildNumber=%d
                message=%s
                timestamp=%s

                %s

                --- jenkins output ---
                %s
                """.formatted(
                ok ? "Jenkins deployment successful (job finished)."
                        : "Jenkins deployment failed.",
                application,
                job,
                status,
                result.buildNumber(),
                result.message(),
                Instant.now(),
                markerBlock,
                result.detail() == null || result.detail().isBlank() ? result.message() : result.detail()
        ).trim();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
