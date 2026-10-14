package org.example.springai_agent_terraform_deploy_do.tools;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationConfig;
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

/**
 * Triggers Jenkins {@code common-rollback-job} via HTTP API.
 */
@Component
public class JenkinsRollbackTool {

    private static final Logger log = LoggerFactory.getLogger(JenkinsRollbackTool.class);
    private static final String DEFAULT_JOB = "common-rollback-job";

    private final ApplicationCatalog applicationCatalog;
    private final JenkinsSettingsService jenkinsSettingsService;
    private final JenkinsClient jenkinsClient;

    @Value("${app.jenkins.rollback.job:common-rollback-job}")
    private String rollbackJobName;

    public JenkinsRollbackTool(ApplicationCatalog applicationCatalog,
                               JenkinsSettingsService jenkinsSettingsService,
                               JenkinsClient jenkinsClient) {
        this.applicationCatalog = applicationCatalog;
        this.jenkinsSettingsService = jenkinsSettingsService;
        this.jenkinsClient = jenkinsClient;
    }

    @Tool(description = "Trigger Jenkins common-rollback-job to restore an existing image version. Requires CLUSTER, APP_NAME, IMAGE_VERSION, NAMESPACE. Only call when the user explicitly approved a rollback (approved=true / message contains rollback approved).")
    public String rollbackViaJenkins(
            @ToolParam(description = "DigitalOcean Kubernetes cluster name (CLUSTER), e.g. payment-app-cluster") String cluster,
            @ToolParam(description = "Application to roll back (APP_NAME), e.g. payment-app") String appName,
            @ToolParam(description = "Existing image version to restore (IMAGE_VERSION), e.g. 1.0.4") String imageVersion,
            @ToolParam(description = "Kubernetes namespace (NAMESPACE), e.g. payment-app") String namespace,
            @ToolParam(description = "Must be true when the user confirmed rollback in the UI", required = false) Boolean approved) {

        boolean isApproved = Boolean.TRUE.equals(approved);
        log.info(">>> TOOL rollbackViaJenkins START cluster={} app={} version={} ns={} approved={}",
                cluster, appName, imageVersion, namespace, isApproved);

        if (!isApproved) {
            return """
                    BLOCKED: rollback requires explicit user approval.
                    No Jenkins job was started.
                    Ask the user to confirm rollback in the UI, then call again with approved=true.
                    """.trim();
        }

        String resolvedApp = blankToNull(appName);
        String resolvedCluster = blankToNull(cluster);
        String resolvedVersion = blankToNull(imageVersion);
        String resolvedNamespace = blankToNull(namespace);

        if (resolvedApp != null) {
            ApplicationConfig app = applicationCatalog.findByName(resolvedApp).orElse(null);
            if (app != null) {
                if (resolvedCluster == null && app.cluster() != null && !app.cluster().isBlank()) {
                    resolvedCluster = app.cluster().trim();
                }
                if (resolvedNamespace == null && app.namespace() != null && !app.namespace().isBlank()) {
                    resolvedNamespace = app.namespace().trim();
                }
            }
        }

        try {
            resolvedApp = trimRequired(resolvedApp, "APP_NAME");
            resolvedCluster = trimRequired(resolvedCluster, "CLUSTER");
            resolvedVersion = trimRequired(resolvedVersion, "IMAGE_VERSION");
            resolvedNamespace = trimRequired(resolvedNamespace, "NAMESPACE");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (!jenkinsSettingsService.isConfigured()) {
            return "Jenkins credentials are not configured. Open Settings and save URL / user / token.";
        }

        String job = rollbackJobName == null || rollbackJobName.isBlank() ? DEFAULT_JOB : rollbackJobName.trim();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CLUSTER", resolvedCluster);
        params.put("APP_NAME", resolvedApp);
        params.put("IMAGE_VERSION", resolvedVersion);
        params.put("NAMESPACE", resolvedNamespace);

        try {
            JenkinsClient.JobRunResult result = jenkinsClient.triggerAndWait(job, params);
            return buildToolResult(result, resolvedApp, job, resolvedCluster, resolvedVersion, resolvedNamespace);
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error("Jenkins rollback failed", e);
            return "Jenkins rollback execution error: " + e.getMessage();
        }
    }

    public String rollback(String cluster, String appName, String imageVersion, String namespace) {
        return rollbackViaJenkins(cluster, appName, imageVersion, namespace, true);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String trimRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String buildToolResult(JenkinsClient.JobRunResult result,
                                          String application,
                                          String job,
                                          String cluster,
                                          String imageVersion,
                                          String namespace) {
        boolean ok = result.ok();
        String status = ok ? "SUCCESS" : "FAILED";
        String title = ok
                ? "Rolled back " + application + " successfully"
                : "Rollback of " + application + " failed";
        String json = """
                {"ok":%s,"title":"%s","application":"%s","job":"%s","status":"%s","exitCode":%d,"buildNumber":%d,"cluster":"%s","imageVersion":"%s","namespace":"%s","added":0,"changed":0,"destroyed":0}
                """.formatted(
                ok,
                escapeJson(title),
                escapeJson(application),
                escapeJson(job),
                escapeJson(status),
                ok ? 0 : 1,
                result.buildNumber(),
                escapeJson(cluster),
                escapeJson(imageVersion),
                escapeJson(namespace)
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
                cluster=%s
                imageVersion=%s
                namespace=%s
                buildNumber=%d
                message=%s
                timestamp=%s

                %s

                --- jenkins output ---
                %s
                """.formatted(
                ok ? "Jenkins rollback successful (common-rollback-job finished)."
                        : "Jenkins rollback failed.",
                application,
                job,
                status,
                cluster,
                imageVersion,
                namespace,
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
