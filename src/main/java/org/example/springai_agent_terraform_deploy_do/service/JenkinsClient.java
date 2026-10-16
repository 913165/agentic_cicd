package org.example.springai_agent_terraform_deploy_do.service;

import org.example.springai_agent_terraform_deploy_do.settings.JenkinsSettings;
import org.example.springai_agent_terraform_deploy_do.settings.JenkinsSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Calls Jenkins Remote Access API with Basic auth (username + API token).
 * No Terraform / curl / local-exec.
 */
@Service
public class JenkinsClient {

    private static final Logger log = LoggerFactory.getLogger(JenkinsClient.class);

    private final JenkinsSettingsService jenkinsSettingsService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${app.jenkins.timeout-minutes:30}")
    private long timeoutMinutes;

    @Value("${app.jenkins.poll-seconds:5}")
    private long pollSeconds;

    public JenkinsClient(JenkinsSettingsService jenkinsSettingsService) {
        this.jenkinsSettingsService = jenkinsSettingsService;
    }

    public Map<String, Object> testConnection() {
        return testConnection(null, null, null);
    }

    /**
     * Probe Jenkins. Blank override fields fall back to saved settings (token blank keeps stored token).
     */
    public Map<String, Object> testConnection(String urlOverride, String userOverride, String tokenOverride) {
        JenkinsSettings saved = jenkinsSettingsService.getOrEmpty();
        String url = firstNonBlank(urlOverride, saved.getJenkinsUrl());
        String user = firstNonBlank(userOverride, saved.getJenkinsUser());
        String token = (tokenOverride != null && !tokenOverride.isBlank())
                ? tokenOverride.trim()
                : (saved.getJenkinsToken() == null ? "" : saved.getJenkinsToken());

        if (url.isBlank()) {
            throw new IllegalArgumentException("Jenkins URL is required");
        }
        if (user.isBlank()) {
            throw new IllegalArgumentException("Jenkins username is required");
        }
        if (token.isBlank()) {
            throw new IllegalArgumentException("Jenkins API token is required");
        }

        JenkinsSettings probe = new JenkinsSettings();
        probe.setJenkinsUrl(url.trim());
        probe.setJenkinsUser(user.trim());
        probe.setJenkinsToken(token);

        String apiUrl = join(probe.getJenkinsUrl(), "/api/json");
        try {
            HttpResponse<String> response = send(probe, HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .header("Accept", "application/json"));
            int code = response.statusCode();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", apiUrl);
            result.put("httpStatus", code);
            if (code >= 200 && code < 300) {
                result.put("ok", true);
                result.put("message", "Connected to Jenkins (HTTP " + code + ")");
            } else {
                result.put("ok", false);
                result.put("message", switch (code) {
                    case 401 -> "Unauthorized — check username / API token";
                    case 403 -> "Forbidden — token lacks permission";
                    case 404 -> "Not found — check Jenkins URL";
                    default -> "HTTP " + code;
                });
            }
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("url", apiUrl);
            result.put("message", "Could not reach Jenkins: " + e.getMessage());
            return result;
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    /**
     * Trigger {@code buildWithParameters} and wait until the new build finishes.
     * Pipeline jobs only expose parameters after Jenkins has parsed the Jenkinsfile once;
     * if needed this seeds that with a plain {@code /build}, then retries with parameters.
     */
    public JobRunResult triggerAndWait(String jobName, Map<String, String> parameters) {
        JenkinsSettings s = requireConfigured();
        String job = jobName == null ? "" : jobName.trim();
        if (job.isEmpty()) {
            throw new IllegalArgumentException("Jenkins job name is required");
        }
        Map<String, String> params = parameters == null ? Map.of() : parameters;
        String jobUrl = join(s.getJenkinsUrl(), "/job/" + encodePath(job));
        StringBuilder logBuf = new StringBuilder();

        try {
            if (!isParameterized(s, jobUrl)) {
                log.info("Jenkins job {} not parameterized yet — seeding via plain /build", job);
                logBuf.append("Job not parameterized yet; seeding Pipeline parameters via /build\n");
                String seedError = seedPipelineParameters(s, jobUrl, job, logBuf);
                if (seedError != null) {
                    return JobRunResult.failed(400, readBuildNumber(s, jobUrl), seedError, logBuf.toString().trim());
                }
            }

            int before = readBuildNumber(s, jobUrl);
            log.info("Jenkins trigger job={} beforeBuild=#{} params={}", job, before, params.keySet());
            logBuf.append("Trigger beforeBuild=#").append(before)
                    .append(" params=").append(params).append('\n');

            HttpResponse<String> trigger = postBuildWithParameters(s, jobUrl, params);
            int code = trigger.statusCode();
            String body = trigger.body() == null ? "" : trigger.body();

            // Fresh Pipeline job: parameters may still be missing; seed once and retry.
            if (code == 400 && body.toLowerCase().contains("not parameterized")) {
                log.info("Jenkins job {} returned not parameterized — seeding and retrying", job);
                logBuf.append("buildWithParameters HTTP 400 not parameterized; seeding and retrying\n");
                String seedError = seedPipelineParameters(s, jobUrl, job, logBuf);
                if (seedError != null) {
                    return JobRunResult.failed(400, before, seedError, logBuf.toString().trim());
                }
                before = readBuildNumber(s, jobUrl);
                trigger = postBuildWithParameters(s, jobUrl, params);
                code = trigger.statusCode();
                body = trigger.body() == null ? "" : trigger.body();
            }

            if (code != 201 && code != 200) {
                String hint = extractHtmlTitle(body);
                String msg = "Failed to trigger Jenkins job '%s' (HTTP %d)%s"
                        .formatted(job, code, hint.isEmpty() ? "" : ": " + hint);
                if (code == 500) {
                    msg += " — often an invalid Choice parameter (value not in Jenkins job choices)";
                }
                logBuf.append(msg).append('\n');
                return JobRunResult.failed(code, before, msg, logBuf.toString().trim());
            }

            logBuf.append("Triggered ").append(job).append(" HTTP ").append(code).append('\n');
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.max(1, pollSeconds) * 1000L);
                BuildInfo info = readBuild(s, jobUrl + "/lastBuild/api/json");
                logBuf.append("poll number=").append(info.number())
                        .append(" building=").append(info.building())
                        .append(" result=").append(info.result()).append('\n');
                log.debug("Jenkins poll job={} {}", job, info);

                if (info.number() > before && !info.building()) {
                    boolean ok = "SUCCESS".equalsIgnoreCase(info.result());
                    String msg = ok
                            ? "Jenkins job %s #%d completed successfully".formatted(job, info.number())
                            : "Jenkins job %s #%d finished with result=%s".formatted(job, info.number(), info.result());
                    return new JobRunResult(ok, code, info.number(), info.result(), msg, logBuf.toString().trim());
                }
            }
            return JobRunResult.failed(code, before,
                    "Timed out waiting for Jenkins job '%s' after %d minutes".formatted(job, timeoutMinutes),
                    logBuf.toString().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JobRunResult.failed(0, 0, "Interrupted waiting for Jenkins job '%s'".formatted(job), logBuf.toString().trim());
        } catch (Exception e) {
            log.error("Jenkins trigger failed for job={}", job, e);
            return JobRunResult.failed(0, 0, "Jenkins API error: " + e.getMessage(), logBuf.toString().trim());
        }
    }

    private HttpResponse<String> postBuildWithParameters(JenkinsSettings s, String jobUrl, Map<String, String> params)
            throws Exception {
        String form = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(nullToEmpty(e.getValue())))
                .collect(Collectors.joining("&"));
        return send(s, HttpRequest.newBuilder()
                .uri(URI.create(jobUrl + "/buildWithParameters"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private boolean isParameterized(JenkinsSettings s, String jobUrl) {
        try {
            String tree = encode("property[parameterDefinitions[name]]");
            HttpResponse<String> response = send(s, HttpRequest.newBuilder()
                    .uri(URI.create(jobUrl + "/api/json?tree=" + tree))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .header("Accept", "application/json"));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return false;
            }
            String body = response.body() == null ? "" : response.body();
            return body.contains("parameterDefinitions");
        } catch (Exception e) {
            log.debug("Could not check parameterized status for {}: {}", jobUrl, e.toString());
            return false;
        }
    }

    /**
     * @return null on success, or an error message
     */
    private String seedPipelineParameters(JenkinsSettings s, String jobUrl, String job, StringBuilder logBuf)
            throws Exception {
        HttpResponse<String> seed = send(s, HttpRequest.newBuilder()
                .uri(URI.create(jobUrl + "/build"))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody()));
        int seedCode = seed.statusCode();
        String seedBody = seed.body() == null ? "" : seed.body();
        logBuf.append("Seed /build HTTP ").append(seedCode).append('\n');

        // Already parameterized jobs reject plain /build with 400 — treat as ready.
        if (seedCode == 400) {
            logBuf.append("Seed /build HTTP 400 (job likely already requires parameters) — continuing\n");
            return null;
        }
        if (seedCode != 201 && seedCode != 200) {
            String hint = extractHtmlTitle(seedBody);
            return "Failed to seed Jenkins parameters for '%s' (HTTP %d)%s"
                    .formatted(job, seedCode, hint.isEmpty() ? "" : ": " + hint);
        }

        long deadline = System.currentTimeMillis() + Math.min(timeoutMinutes, 10) * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000L);
            if (isParameterized(s, jobUrl)) {
                logBuf.append("Pipeline parameters registered\n");
                // Let the seed build finish so lastBuild number is stable.
                for (int i = 0; i < 90; i++) {
                    BuildInfo info = readBuild(s, jobUrl + "/lastBuild/api/json");
                    if (!info.building() || info.number() == 0) {
                        break;
                    }
                    Thread.sleep(2000L);
                }
                return null;
            }
            logBuf.append("Waiting for parameter registration...\n");
        }
        return "Timed out waiting for Jenkins job '%s' to register Pipeline parameters. Open the job in Jenkins and click Build once, then retry.".formatted(job);
    }

    private JenkinsSettings requireConfigured() {
        JenkinsSettings s = jenkinsSettingsService.getOrEmpty();
        if (!s.isConfigured()) {
            throw new IllegalStateException(
                    "Jenkins credentials are not configured. Open Settings and save URL / user / token.");
        }
        return s;
    }

    private int readBuildNumber(JenkinsSettings s, String jobUrl) {
        try {
            BuildInfo info = readBuild(s, jobUrl + "/lastBuild/api/json");
            return Math.max(0, info.number());
        } catch (Exception e) {
            log.debug("No lastBuild yet for {}: {}", jobUrl, e.toString());
            return 0;
        }
    }

    private BuildInfo readBuild(JenkinsSettings s, String apiUrl) throws Exception {
        HttpResponse<String> response = send(s, HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", "application/json"));
        if (response.statusCode() == 404) {
            return new BuildInfo(0, false, "");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " reading " + apiUrl);
        }
        String body = response.body() == null ? "" : response.body();
        int number = parseIntField(body, "number");
        boolean building = parseBooleanField(body, "building");
        String result = parseStringField(body, "result");
        return new BuildInfo(number, building, result);
    }

    private HttpResponse<String> send(JenkinsSettings s, HttpRequest.Builder builder) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (s.getJenkinsUser() + ":" + s.getJenkinsToken()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = builder.header("Authorization", "Basic " + basic).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim().replaceAll("/+$", "");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return b + path;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Encode a single path segment (job name). */
    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int parseIntField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean parseBooleanField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(json);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static String parseStringField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(?:\"([^\"]*)\"|null)").matcher(json);
        if (!m.find()) {
            return "";
        }
        return m.group(1) == null ? "" : m.group(1);
    }

    private static String extractHtmlTitle(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Matcher m = Pattern.compile("<title>Error\\s+\\d+\\s+(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1).trim() : "";
    }

    private record BuildInfo(int number, boolean building, String result) {
    }

    public record JobRunResult(
            boolean ok,
            int httpStatus,
            int buildNumber,
            String result,
            String message,
            String detail
    ) {
        static JobRunResult failed(int httpStatus, int buildNumber, String message, String detail) {
            return new JobRunResult(false, httpStatus, buildNumber, "FAILED", message, detail);
        }
    }
}
