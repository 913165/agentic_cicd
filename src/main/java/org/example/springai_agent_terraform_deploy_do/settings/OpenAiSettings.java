package org.example.springai_agent_terraform_deploy_do.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "openai_settings")
public class OpenAiSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** AES-GCM encrypted API key (Base64). Never expose raw value via API. */
    @Lob
    @Column(name = "api_key_encrypted", nullable = false)
    private String apiKeyEncrypted = "";

    @Column(name = "updated_at", length = 64)
    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted == null ? "" : apiKeyEncrypted;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isConfigured() {
        return apiKeyEncrypted != null && !apiKeyEncrypted.isBlank();
    }
}
