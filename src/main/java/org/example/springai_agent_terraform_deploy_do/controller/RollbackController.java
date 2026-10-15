package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.tools.DeployResultHolder;
import org.example.springai_agent_terraform_deploy_do.tools.JenkinsRollbackTool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rollback")
public class RollbackController {

    private final JenkinsRollbackTool jenkinsRollbackTool;

    public RollbackController(JenkinsRollbackTool jenkinsRollbackTool) {
        this.jenkinsRollbackTool = jenkinsRollbackTool;
    }

    public record RollbackRequest(
            String cluster,
            String appName,
            String imageVersion,
            String namespace
    ) {
    }

    @PostMapping
    public ResponseEntity<?> rollback(@RequestBody RollbackRequest request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body(error("Request body is required"));
            }
            String result = jenkinsRollbackTool.rollback(
                    request.cluster(),
                    request.appName(),
                    request.imageVersion(),
                    request.namespace()
            );
            String block = DeployResultHolder.takeBlock();
            boolean failed = result != null && (
                    result.startsWith("BLOCKED:")
                            || result.contains("is required")
                            || result.contains("status=FAILED")
                            || result.startsWith("Jenkins rollback failed")
                            || result.startsWith("Jenkins rollback execution error")
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
