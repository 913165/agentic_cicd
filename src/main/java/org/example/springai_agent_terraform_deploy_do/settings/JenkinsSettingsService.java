package org.example.springai_agent_terraform_deploy_do.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class JenkinsSettingsService {

    private static final Logger log = LoggerFactory.getLogger(JenkinsSettingsService.class);

    private final JenkinsSettingsRepository repository;

    public JenkinsSettingsService(JenkinsSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public JenkinsSettingsDto getDto() {
        return toDto(getOrEmpty());
    }

    @Transactional(readOnly = true)
    public JenkinsSettings getOrEmpty() {
        return repository.findById(JenkinsSettings.SINGLETON_ID).orElseGet(JenkinsSettings::new);
    }

    @Transactional
    public JenkinsSettingsDto save(JenkinsSettingsUpdateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String url = trim(req.jenkinsUrl());
        String user = trim(req.jenkinsUser());
        String token = req.jenkinsToken() == null ? "" : req.jenkinsToken().trim();

        if (url.isEmpty()) {
            throw new IllegalArgumentException("Jenkins URL is required");
        }
        if (user.isEmpty()) {
            throw new IllegalArgumentException("Jenkins username is required");
        }

        JenkinsSettings existing = repository.findById(JenkinsSettings.SINGLETON_ID).orElseGet(() -> {
            JenkinsSettings s = new JenkinsSettings();
            s.setId(JenkinsSettings.SINGLETON_ID);
            return s;
        });

        if (token.isEmpty()) {
            if (existing.getJenkinsToken() == null || existing.getJenkinsToken().isBlank()) {
                throw new IllegalArgumentException("Jenkins API token is required");
            }
        } else {
            existing.setJenkinsToken(token);
        }

        existing.setId(JenkinsSettings.SINGLETON_ID);
        existing.setJenkinsUrl(url);
        existing.setJenkinsUser(user);
        existing.setUpdatedAt(Instant.now().toString());
        JenkinsSettings saved = repository.save(existing);
        log.info("Jenkins settings saved (url={}, user={})", saved.getJenkinsUrl(), saved.getJenkinsUser());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public boolean isConfigured() {
        return getOrEmpty().isConfigured();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static JenkinsSettingsDto toDto(JenkinsSettings s) {
        boolean tokenSet = s.getJenkinsToken() != null && !s.getJenkinsToken().isBlank();
        String masked = tokenSet ? mask(s.getJenkinsToken()) : "";
        return new JenkinsSettingsDto(
                nullToEmpty(s.getJenkinsUrl()),
                nullToEmpty(s.getJenkinsUser()),
                masked,
                tokenSet,
                s.isConfigured(),
                s.getUpdatedAt()
        );
    }

    private static String mask(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        if (token.length() <= 4) {
            return "****";
        }
        return "••••••••" + token.substring(token.length() - 4);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
