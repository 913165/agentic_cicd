package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.tools.DeployResultHolder;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsBuildTool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/build")
public class BuildController {

    private final JenkinsBuildTool jenkinsBuildTool;

    public BuildController(JenkinsBuildTool jenkinsBuildTool) {
        this.jenkinsBuildTool = jenkinsBuildTool;
    }

    public record BuildRequest(String applicationName, String imageVersion) {
    }

    @PostMapping
    public ResponseEntity<?> build(@RequestBody BuildRequest request) {
        try {
            if (request == null || request.applicationName() == null || request.applicationName().isBlank()) {
                return ResponseEntity.badRequest().body(error("applicationName is required"));
            }
            String result = jenkinsBuildTool.build(
                    request.applicationName().trim(),
                    request.imageVersion()
            );
            String block = DeployResultHolder.takeBlock();
            boolean failed = result != null && (
                    result.startsWith("BLOCKED:")
                            || result.startsWith("Unknown application")
                            || result.contains("is not a Build")
                            || result.contains("IMAGE_VERSION must")
                            || result.contains("status=FAILED")
                            || result.startsWith("Jenkins build failed")
                            || result.startsWith("Jenkins build execution error")
                            || result.startsWith("Jenkins build terraform")
                            || result.startsWith("Jenkins credentials")
            );
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", !failed);
            body.put("result", result);
            if (block != null) {
                body.put("deployResultBlock", block);
            }
            return failed
                    ? ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
                    : ResponseEntity.ok(body);
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
