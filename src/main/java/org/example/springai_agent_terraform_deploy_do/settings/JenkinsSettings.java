package org.example.springai_agent_terraform_deploy_do.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "jenkins_settings")
public class JenkinsSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "jenkins_url", nullable = false, length = 512)
    private String jenkinsUrl = "";

    @Column(name = "jenkins_user", nullable = false, length = 128)
    private String jenkinsUser = "";

    @Column(name = "jenkins_token", nullable = false, length = 512)
    private String jenkinsToken = "";

    @Column(name = "updated_at", length = 64)
    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJenkinsUrl() {
        return jenkinsUrl;
    }

    public void setJenkinsUrl(String jenkinsUrl) {
        this.jenkinsUrl = jenkinsUrl;
    }

    public String getJenkinsUser() {
        return jenkinsUser;
    }

    public void setJenkinsUser(String jenkinsUser) {
        this.jenkinsUser = jenkinsUser;
    }

    public String getJenkinsToken() {
        return jenkinsToken;
    }

    public void setJenkinsToken(String jenkinsToken) {
        this.jenkinsToken = jenkinsToken;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isConfigured() {
        return notBlank(jenkinsUrl) && notBlank(jenkinsUser) && notBlank(jenkinsToken);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
