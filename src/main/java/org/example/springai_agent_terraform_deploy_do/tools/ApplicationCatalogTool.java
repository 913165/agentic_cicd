package org.example.springai_agent_terraform_deploy_do.tools;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.stream.Collectors;

@Component
public class ApplicationCatalogTool {

    private final ApplicationCatalog applicationCatalog;

    public ApplicationCatalogTool(ApplicationCatalog applicationCatalog) {
        this.applicationCatalog = applicationCatalog;
    }

    @Tool(description = "List registered applications with phase (build/deploy), job type, Jenkins job, APPLICATION param, and descriptions. Use when the user asks which apps can be built or deployed.")
    public String listApplications() {
        Collection<ApplicationConfig> apps = applicationCatalog.values();
        if (apps.isEmpty()) {
            return "No applications are registered in configuration.";
        }

        String body = apps.stream()
                .map(app -> "- name=%s | phase=%s | jobType=%s | exposure=%s | jenkinsJob=%s | jenkinsApplication=%s | imageVersion=%s | containerPort=%s | servicePort=%s | description=%s | cluster=%s | namespace=%s | healthUrl=%s"
                        .formatted(
                                app.name(),
                                app.phase().getId(),
                                app.jobType().getId(),
                                app.exposure().getId(),
                                app.jenkinsJob(),
                                app.resolvedJenkinsApplication(),
                                app.phase().isBuild() ? app.resolvedImageVersion() : "",
                                app.containerPort() == null ? "" : app.containerPort(),
                                app.servicePort() == null ? "" : app.servicePort(),
                                app.description(),
                                app.cluster() == null ? "" : app.cluster(),
                                app.namespace() == null ? "" : app.namespace(),
                                app.healthUrl() == null ? "" : app.healthUrl()))
                .collect(Collectors.joining("\n"));

        System.out.println(">>> TOOL listApplications:\n" + body);
        return "Registered applications:\n" + body;
    }
}
