package org.example.springai_agent_terraform_deploy_do.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException() {
        super("OpenAI key and model are not set up properly. Open Configuration - Configure AI key, save a valid key, then retry.");
    }

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
