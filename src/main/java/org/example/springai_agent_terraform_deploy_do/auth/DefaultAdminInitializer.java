package org.example.springai_agent_terraform_deploy_do.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DefaultAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminInitializer.class);

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.security.default-admin.username:admin}")
    private String defaultUsername;

    @Value("${app.security.default-admin.password:admin}")
    private String defaultPassword;

    public DefaultAdminInitializer(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appUserRepository.existsByUsernameIgnoreCase(defaultUsername)) {
            log.debug("Default admin user '{}' already exists", defaultUsername);
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(defaultUsername.trim());
        admin.setPasswordHash(passwordEncoder.encode(defaultPassword));
        admin.setRole("ROLE_ADMIN");
        admin.setEnabled(true);
        appUserRepository.save(admin);
        log.info("Created default admin user '{}' (change password for production)", defaultUsername);
    }
}
