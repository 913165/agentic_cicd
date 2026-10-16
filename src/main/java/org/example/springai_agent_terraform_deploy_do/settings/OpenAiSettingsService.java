package org.example.springai_agent_terraform_deploy_do.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class OpenAiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSettingsService.class);

    private final OpenAiSettingsRepository repository;
    private final SecretCryptoService cryptoService;
    private final String chatModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpenAiSettingsService(OpenAiSettingsRepository repository,
                                 SecretCryptoService cryptoService,
                                 @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String chatModel) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.chatModel = chatModel == null || chatModel.isBlank() ? "gpt-4o-mini" : chatModel.trim();
    }

    @Transactional(readOnly = true)
    public OpenAiSettingsDto getDto() {
        return toDto(getOrEmpty());
    }

    @Transactional(readOnly = true)
    public OpenAiSettings getOrEmpty() {
        return repository.findById(OpenAiSettings.SINGLETON_ID).orElseGet(OpenAiSettings::new);
    }

    @Transactional(readOnly = true)
    public boolean isConfigured() {
        return getOrEmpty().isConfigured();
    }

    @Transactional(readOnly = true)
    public Optional<String> getDecryptedApiKey() {
        OpenAiSettings s = getOrEmpty();
        if (!s.isConfigured()) {
            return Optional.empty();
        }
        String key = cryptoService.decrypt(s.getApiKeyEncrypted());
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(key.trim());
    }

    @Transactional
    public OpenAiSettingsDto save(OpenAiSettingsUpdateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String key = req.apiKey() == null ? "" : req.apiKey().trim();
        OpenAiSettings existing = repository.findById(OpenAiSettings.SINGLETON_ID).orElseGet(() -> {
            OpenAiSettings s = new OpenAiSettings();
            s.setId(OpenAiSettings.SINGLETON_ID);
            return s;
        });

        if (key.isEmpty()) {
            if (!existing.isConfigured()) {
                throw new IllegalArgumentException("OpenAI API key is required");
            }
            // blank keeps existing encrypted value
        } else {
            existing.setApiKeyEncrypted(cryptoService.encrypt(key));
        }

        existing.setId(OpenAiSettings.SINGLETON_ID);
        existing.setUpdatedAt(Instant.now().toString());
        OpenAiSettings saved = repository.save(existing);
        log.info("OpenAI API key saved (configured={})", saved.isConfigured());
        return toDto(saved);
    }

    @Transactional
    public OpenAiSettingsDto delete() {
        OpenAiSettings existing = repository.findById(OpenAiSettings.SINGLETON_ID).orElseGet(() -> {
            OpenAiSettings s = new OpenAiSettings();
            s.setId(OpenAiSettings.SINGLETON_ID);
            return s;
        });
        existing.setId(OpenAiSettings.SINGLETON_ID);
        existing.setApiKeyEncrypted("");
        existing.setUpdatedAt(Instant.now().toString());
        OpenAiSettings saved = repository.save(existing);
        log.info("OpenAI API key deleted from storage");
        return toDto(saved);
    }

    /**
     * Probe OpenAI with the given key (or saved key when blank).
     */
    public Map<String, Object> testApiKey(String apiKeyOverride) {
        String key = apiKeyOverride != null && !apiKeyOverride.isBlank()
                ? apiKeyOverride.trim()
                : getDecryptedApiKey().orElse("");
        if (key.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key is required");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", chatModel);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/models"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            result.put("httpStatus", code);
            if (code >= 200 && code < 300) {
                result.put("ok", true);
                result.put("message", "Connected to OpenAI (HTTP " + code + ")");
            } else {
                result.put("ok", false);
                result.put("message", switch (code) {
                    case 401 -> "Unauthorized — check the API key";
                    case 403 -> "Forbidden — key lacks permission";
                    case 429 -> "Rate limited — key is recognized but throttled";
                    default -> "HTTP " + code;
                });
            }
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "Could not reach OpenAI: " + e.getMessage());
            return result;
        }
    }

    public String configuredModel() {
        return chatModel;
    }

    private OpenAiSettingsDto toDto(OpenAiSettings s) {
        boolean set = s.isConfigured();
        String masked = "";
        if (set) {
            try {
                masked = mask(cryptoService.decrypt(s.getApiKeyEncrypted()));
            } catch (Exception e) {
                log.warn("Could not decrypt stored OpenAI key for masking: {}", e.toString());
                masked = "••••••••";
            }
        }
        return new OpenAiSettingsDto(masked, set, set, chatModel, s.getUpdatedAt());
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "••••••••" + key.substring(key.length() - 4);
    }
}
