package org.example.springai_agent_terraform_deploy_do.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Blocks Jenkins / AI actions until OpenAI key + model client are configured.
 */
@Component
public class AiConfigurationGuard {

    private final DeployAiChatService deployAiChatService;

    public AiConfigurationGuard(DeployAiChatService deployAiChatService) {
        this.deployAiChatService = deployAiChatService;
    }

    public boolean isReady() {
        return deployAiChatService.isConfigured();
    }

    public Optional<ResponseEntity<Map<String, Object>>> rejectIfNotReady() {
        if (isReady()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "OpenAI key and model are not set up properly");
        body.put("message", new AiNotConfiguredException().getMessage());
        body.put("status", 503);
        return Optional.of(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}
