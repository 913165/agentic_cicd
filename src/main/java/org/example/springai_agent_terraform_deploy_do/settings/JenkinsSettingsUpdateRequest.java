package org.example.springai_agent_terraform_deploy_do.settings;

public record JenkinsSettingsUpdateRequest(
        String jenkinsUrl,
        String jenkinsUser,
        String jenkinsToken
) {
}
