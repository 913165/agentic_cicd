package org.example.springai_agent_terraform_deploy_do.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable registry of deployable applications.
 * Loaded from / saved to {@code app.applications.file} (default: src/main/resources/applications.json).
 */
@Component
public class ApplicationCatalog {

    private static final Logger log = LoggerFactory.getLogger(ApplicationCatalog.class);
    private static final String CLASSPATH_RESOURCE = "applications.json";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path catalogFile;
    private final Map<String, ApplicationConfig> applications = new LinkedHashMap<>();

    public ApplicationCatalog(
            @Value("${app.applications.file:src/main/resources/applications.json}") String catalogFilePath) {
        this.catalogFile = Path.of(catalogFilePath).toAbsolutePath().normalize();
        reload();
    }

    public synchronized Map<String, ApplicationConfig> getApplications() {
        return Map.copyOf(applications);
    }

    public synchronized Collection<ApplicationConfig> values() {
        // Most recently executed jobs first (Jenkins-style dashboard)
        return applications.values().stream()
                .sorted(BY_LAST_EXECUTED_DESC)
                .toList();
    }

    private static final Comparator<ApplicationConfig> BY_LAST_EXECUTED_DESC = Comparator
            .comparing(ApplicationCatalog::parseExecutedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ApplicationConfig::name, String.CASE_INSENSITIVE_ORDER);

    private static Instant parseExecutedAt(ApplicationConfig app) {
        if (app == null || app.lastExecutedAt() == null || app.lastExecutedAt().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(app.lastExecutedAt().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Persist last job run so the dashboard can sort by recency. */
    public synchronized ApplicationConfig recordExecution(String name, boolean success, long durationSec) {
        ApplicationConfig existing = findByName(name).orElse(null);
        if (existing == null) {
            return null;
        }
        ApplicationConfig updated = existing.withExecution(
                Instant.now().toString(),
                success ? "SUCCESS" : "FAILED",
                (int) Math.max(0, durationSec)
        );
        applications.put(existing.name(), updated);
        persistUnlocked();
        log.info("Recorded execution for {} status={} durationSec={}", existing.name(),
                updated.lastStatus(), updated.lastDurationSec());
        return updated;
    }

    public synchronized Optional<ApplicationConfig> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = name.trim();
        ApplicationConfig exact = applications.get(needle);
        if (exact != null) {
            return Optional.of(exact);
        }
        return applications.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(needle))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public synchronized ApplicationConfig add(ApplicationConfig app) {
        ApplicationConfig normalized = normalize(app);
        String key = normalized.name();
        if (findByName(key).isPresent()) {
            throw new IllegalArgumentException("Application already exists: " + key);
        }
        applications.put(key, normalized);
        persistUnlocked();
        log.info("Added application {} -> {}", key, catalogFile);
        return normalized;
    }

    public synchronized ApplicationConfig update(String currentName, ApplicationConfig app) {
        String existingKey = findByName(currentName).map(ApplicationConfig::name).orElse(null);
        if (existingKey == null) {
            throw new IllegalArgumentException("Application not found: " + currentName);
        }
        ApplicationConfig previous = applications.get(existingKey);
        ApplicationConfig normalized = normalize(app).withPreservedExecution(previous);
        String newKey = normalized.name();
        Optional<ApplicationConfig> clash = findByName(newKey);
        if (clash.isPresent() && !clash.get().name().equals(existingKey)) {
            throw new IllegalArgumentException("Application already exists: " + newKey);
        }
        // Preserve order: rebuild map if name changes
        if (!existingKey.equals(newKey)) {
            Map<String, ApplicationConfig> rebuilt = new LinkedHashMap<>();
            for (Map.Entry<String, ApplicationConfig> entry : applications.entrySet()) {
                if (entry.getKey().equals(existingKey)) {
                    rebuilt.put(newKey, normalized);
                } else {
                    rebuilt.put(entry.getKey(), entry.getValue());
                }
            }
            applications.clear();
            applications.putAll(rebuilt);
        } else {
            applications.put(existingKey, normalized);
        }
        persistUnlocked();
        log.info("Updated application {} -> {} ({})", existingKey, newKey, catalogFile);
        return normalized;
    }

    public synchronized boolean remove(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String key = findByName(name).map(ApplicationConfig::name).orElse(null);
        if (key == null) {
            return false;
        }
        applications.remove(key);
        persistUnlocked();
        log.info("Removed application {} -> {}", key, catalogFile);
        return true;
    }

    public synchronized void reload() {
        applications.clear();
        applications.putAll(load());
        log.info("Loaded {} application(s) from {}", applications.size(), catalogFile);
    }

    public Path getCatalogFile() {
        return catalogFile;
    }

    private Map<String, ApplicationConfig> load() {
        try {
            if (Files.isRegularFile(catalogFile)) {
                try (InputStream in = Files.newInputStream(catalogFile)) {
                    return toMap(objectMapper.readValue(in, new TypeReference<>() {
                    }));
                }
            }
            // First run / missing file: seed from classpath resource if present
            ClassPathResource resource = new ClassPathResource(CLASSPATH_RESOURCE);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    Map<String, ApplicationConfig> seeded = toMap(objectMapper.readValue(in, new TypeReference<>() {
                    }));
                    writeList(new ArrayList<>(seeded.values()));
                    return seeded;
                }
            }
            return new LinkedHashMap<>();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load applications catalog from " + catalogFile, e);
        }
    }

    private void persistUnlocked() {
        writeList(new ArrayList<>(applications.values()));
    }

    private void writeList(List<ApplicationConfig> list) {
        try {
            Path parent = catalogFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(catalogFile.toFile(), list);

            // Keep IDE/runtime classpath copy in sync when writing under src/main/resources
            Path targetCopy = Path.of("target", "classes", CLASSPATH_RESOURCE).toAbsolutePath().normalize();
            if (!targetCopy.equals(catalogFile)) {
                Path targetParent = targetCopy.getParent();
                if (targetParent != null) {
                    Files.createDirectories(targetParent);
                }
                Files.writeString(targetCopy, Files.readString(catalogFile));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save applications catalog to " + catalogFile, e);
        }
    }

    private static Map<String, ApplicationConfig> toMap(List<ApplicationConfig> list) {
        Map<String, ApplicationConfig> apps = new LinkedHashMap<>();
        if (list == null) {
            return apps;
        }
        for (ApplicationConfig app : list) {
            ApplicationConfig normalized = normalize(app);
            if (apps.containsKey(normalized.name())) {
                throw new IllegalStateException("Duplicate application name: " + normalized.name());
            }
            apps.put(normalized.name(), normalized);
        }
        return apps;
    }

    private static ApplicationConfig normalize(ApplicationConfig app) {
        if (app == null) {
            throw new IllegalArgumentException("Application is required");
        }
        String name = trimRequired(app.name(), "name");
        String jenkinsJob = trimRequired(app.jenkinsJob(), "jenkinsJob");
        String description = app.description() == null ? "" : app.description().trim();
        PipelinePhase phase = app.phase() == null ? PipelinePhase.DEPLOY : app.phase();
        JobType jobType = app.jobType() == null ? JobType.JENKINS : app.jobType();

        String lastExecutedAt = blankToNull(app.lastExecutedAt());
        String lastStatus = blankToNull(app.lastStatus());
        Integer lastDurationSec = app.lastDurationSec();

        if (phase.isBuild()) {
            // CI catalog entry for common-build-job: name + job + APPLICATION + IMAGE_VERSION.
            String jenkinsApplication = trimRequired(app.jenkinsApplication(), "jenkinsApplication");
            String imageVersion = normalizeImageVersion(app.imageVersion());
            return new ApplicationConfig(
                    name,
                    jenkinsJob,
                    description,
                    null,
                    null,
                    null,
                    jobType,
                    AppExposure.EXTERNAL,
                    null,
                    null,
                    jenkinsApplication,
                    PipelinePhase.BUILD,
                    imageVersion,
                    lastExecutedAt,
                    lastStatus,
                    lastDurationSec);
        }

        String healthUrl = trimRequired(app.healthUrl(), "healthUrl");
        String cluster = blankToNull(app.cluster());
        String namespace = blankToNull(app.namespace());
        AppExposure exposure = app.exposure() == null ? AppExposure.EXTERNAL : app.exposure();
        Integer containerPort = normalizePort(app.containerPort(), "containerPort");
        Integer servicePort = normalizePort(app.servicePort(), "servicePort");
        String jenkinsApplication = blankToNull(app.jenkinsApplication());
        return new ApplicationConfig(
                name, jenkinsJob, description, healthUrl, cluster, namespace,
                jobType, exposure, containerPort, servicePort, jenkinsApplication, PipelinePhase.DEPLOY, null,
                lastExecutedAt, lastStatus, lastDurationSec);
    }

    private static String normalizeImageVersion(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return "1.0.1";
        }
        if (!value.matches("^[0-9]+\\.[0-9]+\\.[0-9]+$")) {
            throw new IllegalArgumentException("imageVersion must look like 1.0.1");
        }
        return value;
    }

    private static Integer normalizePort(Integer port, String field) {
        if (port == null) {
            return null;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(field + " must be between 1 and 65535");
        }
        return port;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String trimRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
