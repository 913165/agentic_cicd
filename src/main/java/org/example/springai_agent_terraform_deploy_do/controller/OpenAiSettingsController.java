package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.service.DeployAiChatService;
import org.example.springai_agent_terraform_deploy_do.settings.OpenAiSettingsDto;
import org.example.springai_agent_terraform_deploy_do.settings.OpenAiSettingsService;
import org.example.springai_agent_terraform_deploy_do.settings.OpenAiSettingsUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/openai")
public class OpenAiSettingsController {

    private final OpenAiSettingsService openAiSettingsService;
    private final DeployAiChatService deployAiChatService;

    public OpenAiSettingsController(OpenAiSettingsService openAiSettingsService,
                                    DeployAiChatService deployAiChatService) {
        this.openAiSettingsService = openAiSettingsService;
        this.deployAiChatService = deployAiChatService;
    }

    @GetMapping
    public OpenAiSettingsDto get() {
        OpenAiSettingsDto dto = openAiSettingsService.getDto();
        // Reflect runtime client readiness in configured flag
        return new OpenAiSettingsDto(
                dto.apiKeyMasked(),
                dto.apiKeySet(),
                deployAiChatService.isConfigured(),
                dto.model(),
                dto.updatedAt()
        );
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody OpenAiSettingsUpdateRequest request) {
        try {
            openAiSettingsService.save(request);
            deployAiChatService.refreshClient();
            return ResponseEntity.ok(get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error(e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete() {
        try {
            openAiSettingsService.delete();
            deployAiChatService.refreshClient();
            return ResponseEntity.ok(get());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error(e.getMessage()));
        }
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody(required = false) OpenAiSettingsUpdateRequest request) {
        try {
            String override = request == null ? null : request.apiKey();
            Map<String, Object> result = openAiSettingsService.testApiKey(override);
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error(e.getMessage()));
        }
    }

    private static Map<String, String> error(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message == null ? "Unknown error" : message);
        return body;
    }
}
