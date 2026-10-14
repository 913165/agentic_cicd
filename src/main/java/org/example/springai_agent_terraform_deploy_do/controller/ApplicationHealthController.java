package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.service.ApplicationHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class ApplicationHealthController {

    private final ApplicationHealthService applicationHealthService;

    public ApplicationHealthController(ApplicationHealthService applicationHealthService) {
        this.applicationHealthService = applicationHealthService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<ApplicationHealthService.HealthStatus> statuses = applicationHealthService.checkAll();
        return Map.of(
                "checkedAt", java.time.Instant.now().toString(),
                "applications", statuses
        );
    }
}
