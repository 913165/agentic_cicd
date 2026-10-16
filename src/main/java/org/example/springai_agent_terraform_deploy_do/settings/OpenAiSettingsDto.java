package org.example.springai_agent_terraform_deploy_do.settings;

public record OpenAiSettingsDto(
        String apiKeyMasked,
        boolean apiKeySet,
        boolean configured,
        String model,
        String updatedAt
) {
}
