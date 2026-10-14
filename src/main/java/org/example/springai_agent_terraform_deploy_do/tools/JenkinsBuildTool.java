package org.example.springai_agent_terraform_deploy_do.tools;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationConfig;
import org.example.springai_agent_terraform_deploy_do.config.JobType;
import org.example.springai_agent_terraform_deploy_do.config.PipelinePhase;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Triggers Jenkins {@code common-build-job} via HTTP API (APPLICATION + IMAGE_VERSION).
 */
@Component
public class JenkinsBuildTool {

    private static final Logger log = LoggerFactory.getLogger(JenkinsBuildTool.class);
    private static final String DEFAULT_JOB = "common-build-job";
    private static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    private final ApplicationCatalog applicationCatalog;
    private final JenkinsSettingsService jenkinsSettingsService;
    private final JenkinsClient jenkinsClient;

    @Value("${app.jenkins.build.job:common-build-job}")
    private String buildJobName;

    public JenkinsBuildTool(ApplicationCatalog applicationCatalog,
                            JenkinsSettingsService jenkinsSettingsService,
                            JenkinsClient jenkinsClient) {
        this.applicationCatalog = applicationCatalog;
        this.jenkinsSettingsService = jenkinsSettingsService;
        this.jenkinsClient = jenkinsClient;
    }

    @Tool(description = "Trigger Jenkins common-build-job (CI) with APPLICATION and IMAGE_VERSION from a Build (CI) catalog entry. Pass the catalog UI name exactly (e.g. payment-app-build), NOT the Jenkins APPLICATION param alone. Only call when approved=true / message contains (approved).")
    public String buildViaJenkins(
            @ToolParam(description = "Catalog UI name with phase=build (e.g. payment-app-build). Prefer exact name from the user message after Build.") String applicationName,
            @ToolParam(description = "IMAGE_VERSION semver tag, e.g. 1.0.1") String imageVersion,
            @ToolParam(description = "Must be true when the user approved the build in the UI", required = false) Boolean approved) {

        boolean isApproved = Boolean.TRUE.equals(approved);
        log.info(">>> TOOL buildViaJenkins START application={}, imageVersion={}, approved={}",
                applicationName, imageVersion, isApproved);

        if (!isApproved) {
            return """
                    BLOCKED: build requires explicit user approval.
                    No Jenkins job was started.
                    Ask the user to Approve in the UI, then call again with approved=true.
                    """.trim();
        }

        ApplicationConfig app = resolveBuildCatalogEntry(applicationName);
        if (app == null) {
            String known = applicationCatalog.values().stream()
                    .filter(a -> a.phase() == PipelinePhase.BUILD)
                    .map(a -> a.name() + " (APPLICATION=" + a.resolvedJenkinsApplication() + ")")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            return "No Build (CI) catalog entry matches '%s'. Build entries: %s."
                    .formatted(applicationName, known);
        }
        if (app.jobType() != JobType.JENKINS) {
            return "Job type '%s' is not implemented for build yet.".formatted(app.jobType().getLabel());
        }

        applicationName = app.name();
        String resolvedVersion = imageVersion == null ? "" : imageVersion.trim();
        if (resolvedVersion.isBlank()) {
            resolvedVersion = app.resolvedImageVersion();
        }
        if (!SEMVER.matcher(resolvedVersion).matches()) {
            return "IMAGE_VERSION must look like 1.0.1 (got: %s).".formatted(resolvedVersion);
        }

        String jenkinsApplication = app.resolvedJenkinsApplication();
        String jenkinsJob = (app.jenkinsJob() == null || app.jenkinsJob().isBlank())
                ? (buildJobName == null || buildJobName.isBlank() ? DEFAULT_JOB : buildJobName.trim())
                : app.jenkinsJob().trim();

        if (!jenkinsSettingsService.isConfigured()) {
            return "Jenkins credentials are not configured. Open Settings and save URL / user / token.";
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("APPLICATION", jenkinsApplication);
        params.put("IMAGE_VERSION", resolvedVersion);

        long startedMs = System.currentTimeMillis();
        try {
            JenkinsClient.JobRunResult result = jenkinsClient.triggerAndWait(jenkinsJob, params);
            long durationSec = Math.max(0, (System.currentTimeMillis() - startedMs) / 1000);
            applicationCatalog.recordExecution(applicationName, result.ok(), durationSec);
            return buildToolResult(result, applicationName, jenkinsJob, jenkinsApplication, resolvedVersion);
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error("Jenkins build failed", e);
            return "Jenkins build execution error: " + e.getMessage();
        }
    }

    public String build(String applicationName, String imageVersion) {
        return buildViaJenkins(applicationName, imageVersion, true);
    }

    private ApplicationConfig resolveBuildCatalogEntry(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String needle = requested.trim();

        ApplicationConfig exact = applicationCatalog.findByName(needle).orElse(null);
        if (exact != null && exact.phase() == PipelinePhase.BUILD) {
            return exact;
        }

        List<ApplicationConfig> buildApps = applicationCatalog.values().stream()
                .filter(a -> a.phase() == PipelinePhase.BUILD)
                .toList();

        for (ApplicationConfig a : buildApps) {
            if (a.name().equalsIgnoreCase(needle)) {
                return a;
            }
        }

        ApplicationConfig byNamePrefix = buildApps.stream()
                .filter(a -> a.name().equalsIgnoreCase(needle + "-build")
                        || a.name().toLowerCase().startsWith(needle.toLowerCase() + "-"))
                .findFirst()
                .orElse(null);
        if (byNamePrefix != null) {
            return byNamePrefix;
        }

        List<ApplicationConfig> byJenkinsApp = buildApps.stream()
                .filter(a -> a.resolvedJenkinsApplication().equalsIgnoreCase(needle))
                .toList();
        if (byJenkinsApp.size() == 1) {
            return byJenkinsApp.getFirst();
        }
        if (byJenkinsApp.size() > 1) {
            return byJenkinsApp.stream()
                    .filter(a -> a.name().toLowerCase().contains("build"))
                    .findFirst()
                    .orElse(byJenkinsApp.getFirst());
        }
        return null;
    }

    private static String buildToolResult(JenkinsClient.JobRunResult result,
                                          String catalogName,
                                          String job,
                                          String jenkinsApplication,
                                          String imageVersion) {
        boolean ok = result.ok();
        String status = ok ? "SUCCESS" : "FAILED";
        String title = ok
                ? "Built " + catalogName + " successfully"
                : "Build of " + catalogName + " failed";
        String json = """
                {"ok":%s,"title":"%s","application":"%s","job":"%s","jenkinsApplication":"%s","imageVersion":"%s","status":"%s","exitCode":%d,"buildNumber":%d,"added":0,"changed":0,"destroyed":0,"kind":"build"}
                """.formatted(
                ok,
                escapeJson(title),
                escapeJson(catalogName),
                escapeJson(job),
                escapeJson(jenkinsApplication),
                escapeJson(imageVersion),
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
                jenkinsApplication=%s
                job=%s
                imageVersion=%s
                status=%s
                buildNumber=%d
                message=%s
                timestamp=%s

                %s

                --- jenkins output ---
                %s
                """.formatted(
                ok ? "Jenkins build successful (common-build-job finished)."
                        : "Jenkins build failed.",
                catalogName,
                jenkinsApplication,
                job,
                imageVersion,
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
