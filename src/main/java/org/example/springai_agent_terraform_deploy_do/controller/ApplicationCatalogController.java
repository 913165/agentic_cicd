package org.example.springai_agent_terraform_deploy_do.controller;

import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class ApplicationCatalogController {

    private final ApplicationCatalog applicationCatalog;

    public ApplicationCatalogController(ApplicationCatalog applicationCatalog) {
        this.applicationCatalog = applicationCatalog;
    }

    @GetMapping
    public List<ApplicationConfig> list() {
        return List.copyOf(applicationCatalog.values());
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody ApplicationConfig app) {
        try {
            ApplicationConfig created = applicationCatalog.add(app);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error(e.getMessage()));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody ApplicationConfig app) {
        try {
            ApplicationConfig updated = applicationCatalog.update(name, app);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.startsWith("Application not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(msg));
            }
            return ResponseEntity.badRequest().body(error(msg));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error(e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> remove(@PathVariable String name) {
        try {
            boolean removed = applicationCatalog.remove(name);
            if (!removed) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(error("Application not found: " + name));
            }
            return ResponseEntity.ok(Map.of("removed", name));
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
