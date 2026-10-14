package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.service.JenkinsClient;
import org.example.springai_agent_terraform_deploy_do.settings.JenkinsSettingsDto;
import org.example.springai_agent_terraform_deploy_do.settings.JenkinsSettingsService;
import org.example.springai_agent_terraform_deploy_do.settings.JenkinsSettingsUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/jenkins")
public class JenkinsSettingsController {

    private final JenkinsSettingsService jenkinsSettingsService;
    private final JenkinsClient jenkinsClient;

    public JenkinsSettingsController(JenkinsSettingsService jenkinsSettingsService,
                                     JenkinsClient jenkinsClient) {
        this.jenkinsSettingsService = jenkinsSettingsService;
        this.jenkinsClient = jenkinsClient;
    }

    @GetMapping
    public JenkinsSettingsDto get() {
        return jenkinsSettingsService.getDto();
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody JenkinsSettingsUpdateRequest request) {
        try {
            return ResponseEntity.ok(jenkinsSettingsService.save(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error(e.getMessage()));
        }
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody(required = false) JenkinsSettingsUpdateRequest request) {
        try {
            Map<String, Object> result;
            if (request == null) {
                result = jenkinsClient.testConnection();
            } else {
                result = jenkinsClient.testConnection(
                        request.jenkinsUrl(),
                        request.jenkinsUser(),
                        request.jenkinsToken()
                );
            }
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
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
