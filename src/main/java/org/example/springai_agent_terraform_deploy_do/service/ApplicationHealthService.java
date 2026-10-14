package org.example.springai_agent_terraform_deploy_do.service;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ApplicationHealthService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationHealthService.class);

    private final ApplicationCatalog applicationCatalog;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ApplicationHealthService(ApplicationCatalog applicationCatalog) {
        this.applicationCatalog = applicationCatalog;
    }

    public List<HealthStatus> checkAll() {
        List<HealthStatus> results = new ArrayList<>();
        for (ApplicationConfig app : applicationCatalog.values()) {
            results.add(checkOne(app));
        }
        return results;
    }

    public HealthStatus checkOne(ApplicationConfig app) {
        System.out.println();
        String url = app.healthUrl();
        if (url == null || url.isBlank()) {
            return new HealthStatus(app.name(), "UNKNOWN", "No healthUrl configured", Instant.now().toString());
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .header("Accept", "application/json, text/plain, */*")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (code >= 200 && code < 300) {
                if (looksDown(body)) {
                    return new HealthStatus(app.name(), "DOWN", "HTTP " + code + " body reports DOWN", Instant.now().toString());
                }
                return new HealthStatus(app.name(), "UP", "HTTP " + code, Instant.now().toString());
            }
            return new HealthStatus(app.name(), "DOWN", "HTTP " + code, Instant.now().toString());
        } catch (Exception e) {
            log.debug("Health check failed for {} url={}: {}", app.name(), url, e.toString());
            return new HealthStatus(app.name(), "DOWN", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Instant.now().toString());
        }
    }

    private static boolean looksDown(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        // Spring Boot actuator health JSON: {"status":"DOWN"}
        return lower.contains("\"status\":\"down\"") || lower.contains("\"status\": \"down\"");
    }

    public record HealthStatus(String name, String status, String detail, String checkedAt) {
    }
}
