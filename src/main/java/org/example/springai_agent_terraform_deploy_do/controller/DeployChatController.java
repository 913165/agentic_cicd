package org.example.springai_agent_terraform_deploy_do.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.springai_agent_terraform_deploy_do.config.ApplicationCatalog;
import org.example.springai_agent_terraform_deploy_do.service.AiNotConfiguredException;
import org.example.springai_agent_terraform_deploy_do.service.DeployAiChatService;
import org.example.springai_agent_terraform_deploy_do.tools.DeployResultHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@SessionAttributes("messages")
public class DeployChatController {

    private static final Logger log = LoggerFactory.getLogger(DeployChatController.class);

    private final DeployAiChatService deployAiChatService;
    private final ApplicationCatalog applicationCatalog;

    public DeployChatController(DeployAiChatService deployAiChatService,
                                ApplicationCatalog applicationCatalog) {
        this.deployAiChatService = deployAiChatService;
        this.applicationCatalog = applicationCatalog;
    }

    @GetMapping({"/", "/chat"})
    public String chatPage(Model model) {
        if (!model.containsAttribute("messages")) {
            model.addAttribute("messages", new ArrayList<Map<String, String>>());
        }
        addApplications(model);
        model.addAttribute("aiConfigured", deployAiChatService.isConfigured());
        return "chat";
    }

    @PostMapping("/chat")
    public Object chat(@RequestParam String message,
                       Model model,
                       HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) model.getAttribute("messages");
        if (messages == null) {
            messages = new ArrayList<>();
        }

        messages.add(Map.of("role", "user", "content", message));
        System.out.println(">>> CHAT user message: " + message);
        log.info(">>> CHAT user message: {}", message);

        if (!deployAiChatService.isConfigured()) {
            return notConfiguredResponse(messages, model, request);
        }

        String reply;
        try {
            reply = deployAiChatService.chat(message);
        } catch (AiNotConfiguredException e) {
            return notConfiguredResponse(messages, model, request);
        }

        String deployBlock = DeployResultHolder.takeBlock();
        if (deployBlock != null && !deployBlock.isBlank()) {
            // Prefer structured deploy card payload over free-form model prose
            reply = deployBlock;
        }

        System.out.println(">>> CHAT assistant reply: " + reply);
        log.info(">>> CHAT assistant reply: {}", reply);
        messages.add(Map.of("role", "assistant", "content", reply != null ? reply : "(no response)"));
        model.addAttribute("messages", messages);
        model.addAttribute("aiConfigured", deployAiChatService.isConfigured());
        addApplications(model);
        return "chat";
    }

    private Object notConfiguredResponse(List<Map<String, String>> messages,
                                         Model model,
                                         HttpServletRequest request) {
        String detail = new AiNotConfiguredException().getMessage();
        log.warn("AI chat rejected: OpenAI key/model not set up");

        String accept = request.getHeader("Accept");
        boolean wantsJson = accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)
                && !accept.contains(MediaType.TEXT_HTML_VALUE);

        if (wantsJson) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "OpenAI key and model are not set up properly");
            body.put("message", detail);
            body.put("status", 503);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        // Keep HTTP 200 for HTML so the chat page renders (503 would show Whitelabel /error).
        // JSON callers still get 503 above.
        messages.add(Map.of("role", "assistant", "content", detail));
        model.addAttribute("messages", messages);
        model.addAttribute("aiConfigured", false);
        model.addAttribute("aiError", detail);
        addApplications(model);
        return "chat";
    }

    private void addApplications(Model model) {
        model.addAttribute("applications", applicationCatalog.values());
    }
}
