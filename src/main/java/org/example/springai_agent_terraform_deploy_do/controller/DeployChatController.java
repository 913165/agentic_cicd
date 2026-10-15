package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.tools.ApplicationCatalogTool;
import org.example.springai_agent_terraform_deploy_do.tools.DeployResultHolder;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsBuildTool;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsDeploymentTool;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsRollbackTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@SessionAttributes("messages")
public class DeployChatController {

    private static final Logger log = LoggerFactory.getLogger(DeployChatController.class);

    private final ChatClient chatClient;
    private final ApplicationCatalog applicationCatalog;

    public DeployChatController(ChatClient.Builder chatClientBuilder,
                                JenkinsDeploymentTool jenkinsDeploymentTool,
                                JenkinsBuildTool jenkinsBuildTool,
                                JenkinsRollbackTool jenkinsRollbackTool,
                                ApplicationCatalogTool applicationCatalogTool,
                                ApplicationCatalog applicationCatalog) {
        this.applicationCatalog = applicationCatalog;
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        You are a deployment AI agent. Help the user build, deploy, and roll back applications.

                        Catalog field meanings (important):
                        - phase: build (CI) or deploy (CD).
                          Use buildViaJenkins only for phase=build; deployViaJenkins only for phase=deploy.
                        - name: arbitrary catalog/UI identifier. It can be ANY string (e.g. paynent-job-v2).
                          Do NOT treat it as a typo, do NOT ask to rename it, do NOT block over spelling.
                        - jenkinsJob: Jenkins job to trigger (common-build-job for CI, common-deploy-job for CD).
                        - jenkinsApplication: Jenkins APPLICATION parameter for common-build-job / common-deploy-job
                          (payment-app, order-app, customer-portal-api). Required for both CI and CD common jobs.

                        Rules:
                        - Never build, deploy, or roll back without explicit user approval.
                        - Never invent an automatic rollback after a failed deploy.
                        - Use listApplications when the user asks which apps exist or which jobs are available.
                        - Use buildViaJenkins for phase=build apps when the user message contains "(approved)" /
                          explicit build approval. Pass approved=true, catalog name exactly, and IMAGE_VERSION
                          (semver e.g. 1.0.1; default 1.0.1 if omitted). Job + APPLICATION come from the catalog.
                          CRITICAL: If the user message already contains "(approved)" and is a Build request,
                          you MUST call buildViaJenkins immediately with approved=true.
                          The applicationName argument MUST be the catalog UI name after the word Build
                          (e.g. payment-app-build), NEVER substitute a Deploy (CD) sibling like payment-app.
                          IMAGE_VERSION comes from the message (IMAGE_VERSION=…). Do NOT ask for more approval.
                        - Use deployViaJenkins only for phase=deploy applications with jobType=jenkins, and only when
                          the user message contains "(approved)" / explicit approval.
                          CRITICAL: If the user message already contains "(approved)", you MUST call deployViaJenkins
                          immediately with approved=true and the catalog name exactly as written.
                          Do NOT ask for more approval. Do NOT refuse because Prometheus health is DOWN —
                          health DOWN is normal before/during deploy; approval already covers that.
                          Optionally pass imageTag when the user specifies one (default latest).
                          Jenkins job + APPLICATION + ports + EXPOSE_SERVICE are resolved from the catalog.
                        - If an app's jobType is azure_cicd or other, explain that only Jenkins triggers are
                          implemented today — do not invent another trigger.
                        - Use rollbackViaJenkins only for explicit rollback requests with approval.
                          Pass approved=true plus CLUSTER, APP_NAME, IMAGE_VERSION, NAMESPACE
                          (Jenkins job: common-rollback-job). Prefer catalog cluster/namespace defaults when present.
                        - Without "(approved)", do not call deploy/build/rollback tools — wait for UI Approve.
                        - If the user cancelled, acknowledge cancellation only — do not call build/deploy/rollback tools.
                        Extract the catalog application name and any optional version/cluster/namespace details.
                        After buildViaJenkins, deployViaJenkins, or rollbackViaJenkins returns, your reply MUST include the
                        <<<DEPLOY_RESULT>>> ... <<<END_DEPLOY_RESULT>>> block from the tool output
                        exactly as returned (do not rewrite the JSON). You may add at most one short
                        sentence before that block. Do not paste the full Jenkins log in the reply.
                        """)
                .defaultTools(jenkinsDeploymentTool, jenkinsBuildTool,
                        jenkinsRollbackTool, applicationCatalogTool)
                .build();
    }

    @GetMapping({"/", "/chat"})
    public String chatPage(Model model) {
        if (!model.containsAttribute("messages")) {
            model.addAttribute("messages", new ArrayList<Map<String, String>>());
        }
        addApplications(model);
        return "chat";
    }

    @PostMapping("/chat")
    public String chat(@RequestParam String message, Model model) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) model.getAttribute("messages");
        if (messages == null) {
            messages = new ArrayList<>();
        }

        messages.add(Map.of("role", "user", "content", message));
        System.out.println(">>> CHAT user message: " + message);
        log.info(">>> CHAT user message: {}", message);

        String reply = chatClient
                .prompt()
                .user(message)
                .call()
                .content();

        String deployBlock = DeployResultHolder.takeBlock();
        if (deployBlock != null && !deployBlock.isBlank()) {
            // Prefer structured deploy card payload over free-form model prose
            reply = deployBlock;
        }

        System.out.println(">>> CHAT assistant reply: " + reply);
        log.info(">>> CHAT assistant reply: {}", reply);
        messages.add(Map.of("role", "assistant", "content", reply != null ? reply : "(no response)"));
        model.addAttribute("messages", messages);
        addApplications(model);
        return "chat";
    }

    private void addApplications(Model model) {
        model.addAttribute("applications", applicationCatalog.values());
    }
}
