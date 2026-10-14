package org.example.springai_agent_terraform_deploy_do.settings;

public record JenkinsSettingsDto(
        String jenkinsUrl,
        String jenkinsUser,
        /** Always masked in API responses; send a new value to update, or blank to keep. */
        String jenkinsTokenMasked,
        boolean tokenSet,
        boolean configured,
        String updatedAt
) {
}
