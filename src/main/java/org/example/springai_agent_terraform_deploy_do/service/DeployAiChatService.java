package org.example.springai_agent_terraform_deploy_do.service;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import org.example.springai_agent_terraform_deploy_do.settings.OpenAiSettingsService;
import org.example.springai_agent_terraform_deploy_do.tools.ApplicationCatalogTool;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsBuildTool;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsDeploymentTool;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsRollbackTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Owns the runtime {@link ChatClient}. Rebuilds it when the OpenAI API key is saved/updated/deleted
 * so no application restart is required.
 */
@Service
public class DeployAiChatService {

    private static final Logger log = LoggerFactory.getLogger(DeployAiChatService.class);

    private static final String SYSTEM_PROMPT = """
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
            """;

    private final OpenAiSettingsService openAiSettingsService;
    private final OpenAiCommonProperties commonProperties;
    private final OpenAiChatProperties chatProperties;
    private final ToolCallingManager toolCallingManager;
    private final JenkinsDeploymentTool jenkinsDeploymentTool;
    private final JenkinsBuildTool jenkinsBuildTool;
    private final JenkinsRollbackTool jenkinsRollbackTool;
    private final ApplicationCatalogTool applicationCatalogTool;

    private final Object lock = new Object();
    private volatile ChatClient chatClient;

    public DeployAiChatService(OpenAiSettingsService openAiSettingsService,
                               OpenAiCommonProperties commonProperties,
                               OpenAiChatProperties chatProperties,
                               ToolCallingManager toolCallingManager,
                               JenkinsDeploymentTool jenkinsDeploymentTool,
                               JenkinsBuildTool jenkinsBuildTool,
                               JenkinsRollbackTool jenkinsRollbackTool,
                               ApplicationCatalogTool applicationCatalogTool) {
        this.openAiSettingsService = openAiSettingsService;
        this.commonProperties = commonProperties;
        this.chatProperties = chatProperties;
        this.toolCallingManager = toolCallingManager;
        this.jenkinsDeploymentTool = jenkinsDeploymentTool;
        this.jenkinsBuildTool = jenkinsBuildTool;
        this.jenkinsRollbackTool = jenkinsRollbackTool;
        this.applicationCatalogTool = applicationCatalogTool;
    }

    @PostConstruct
    void init() {
        // Key comes only from Configuration UI → encrypted H2 (no env auto-import).
        refreshClient();
    }

    public boolean isConfigured() {
        return openAiSettingsService.isConfigured() && chatClient != null;
    }

    public void refreshClient() {
        synchronized (lock) {
            Optional<String> key = openAiSettingsService.getDecryptedApiKey();
            if (key.isEmpty()) {
                chatClient = null;
                log.info("OpenAI ChatClient cleared (no API key configured)");
                return;
            }
            try {
                chatClient = buildChatClient(key.get());
                log.info("OpenAI ChatClient ready (model={})", openAiSettingsService.configuredModel());
            } catch (Exception e) {
                chatClient = null;
                log.error("Failed to build OpenAI ChatClient", e);
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        }
    }

    public String chat(String message) {
        ChatClient client = chatClient;
        if (client == null || !openAiSettingsService.isConfigured()) {
            throw new AiNotConfiguredException();
        }
        return client.prompt()
                .user(message)
                .call()
                .content();
    }

    private ChatClient buildChatClient(String apiKey) {
        OpenAIClient sync = OpenAiSetup.setupSyncClient(
                commonProperties.getBaseUrl(),
                apiKey,
                null,
                commonProperties.getMicrosoftDeploymentName(),
                commonProperties.getMicrosoftFoundryServiceVersion(),
                commonProperties.getOrganizationId(),
                commonProperties.isMicrosoftFoundry(),
                commonProperties.isGitHubModels(),
                commonProperties.getModel(),
                commonProperties.getTimeout(),
                commonProperties.getMaxRetries(),
                commonProperties.getProxy(),
                commonProperties.getCustomHeaders(),
                ObservationRegistry.NOOP,
                null,
                List.of());

        OpenAIClientAsync async = OpenAiSetup.setupAsyncClient(
                commonProperties.getBaseUrl(),
                apiKey,
                null,
                commonProperties.getMicrosoftDeploymentName(),
                commonProperties.getMicrosoftFoundryServiceVersion(),
                commonProperties.getOrganizationId(),
                commonProperties.isMicrosoftFoundry(),
                commonProperties.isGitHubModels(),
                commonProperties.getModel(),
                commonProperties.getTimeout(),
                commonProperties.getMaxRetries(),
                commonProperties.getProxy(),
                commonProperties.getCustomHeaders(),
                ObservationRegistry.NOOP,
                null,
                List.of());

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(sync)
                .openAiClientAsync(async)
                .options(chatProperties.toOptions())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(jenkinsDeploymentTool, jenkinsBuildTool, jenkinsRollbackTool, applicationCatalogTool)
                .build();
    }

}
