package org.example.springai_agent_terraform_deploy_do.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DeploymentSimulationTool {

    private static final Logger log = LoggerFactory.getLogger(DeploymentSimulationTool.class);

    @Value("${app.terraform.dir:terraform}")
    private String terraformDir;

    @Value("${app.terraform.executable:terraform}")
    private String terraformExecutable;

    @Value("${app.terraform.timeout-minutes:15}")
    private long timeoutMinutes;

    @Tool(description = "Deploy an application by running terraform apply in the project's terraform directory. Use when the user asks to deploy or apply infrastructure.")
    public String simulateDeployment(
            @ToolParam(description = "Application or service name to deploy") String applicationName,
            @ToolParam(description = "Target environment, e.g. dev, staging, or prod") String environment,
            @ToolParam(description = "Optional version or image tag to deploy", required = false) String version) {

        String resolvedVersion = (version == null || version.isBlank()) ? "latest" : version.trim();

        String callMsg = ">>> TOOL simulateDeployment CALLED: application=%s, environment=%s, version=%s, terraformDir=%s"
                .formatted(applicationName, environment, resolvedVersion, terraformDir);
        System.out.println(callMsg);
        log.info(callMsg);

        File workingDir = resolveTerraformDir();
        if (!workingDir.isDirectory()) {
            String err = "Terraform directory not found: " + workingDir.getAbsolutePath();
            log.error(err);
            return err;
        }

        try {
            CommandResult initResult = runTerraform(workingDir, List.of("init", "-input=false", "-no-color"));
            if (initResult.exitCode() != 0) {
                return formatFailure("terraform init", applicationName, environment, resolvedVersion, initResult);
            }

            CommandResult applyResult = runTerraform(workingDir, List.of("apply", "-auto-approve", "-input=false", "-no-color"));
            if (applyResult.exitCode() != 0) {
                return formatFailure("terraform apply", applicationName, environment, resolvedVersion, applyResult);
            }

            String result = """
                    Terraform deployment successful.
                    application=%s
                    environment=%s
                    version=%s
                    status=SUCCESS
                    workingDir=%s
                    timestamp=%s

                    --- terraform apply output ---
                    %s
                    """.formatted(
                    applicationName,
                    environment,
                    resolvedVersion,
                    workingDir.getAbsolutePath(),
                    Instant.now(),
                    applyResult.output()
            ).trim();

            System.out.println(">>> TOOL simulateDeployment RESULT: SUCCESS");
            log.info(">>> TOOL simulateDeployment RESULT: SUCCESS ({} chars output)", applyResult.output().length());
            return result;
        } catch (Exception e) {
            String err = "Terraform execution error: " + e.getMessage();
            System.out.println(">>> TOOL simulateDeployment ERROR: " + err);
            log.error("Terraform execution failed", e);
            return err;
        }
    }

    private File resolveTerraformDir() {
        File dir = new File(terraformDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), terraformDir);
        }
        return dir;
    }

    private CommandResult runTerraform(File workingDir, List<String> args)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(terraformExecutable);
        command.addAll(args);

        log.info("Running {} in {}", command, workingDir.getAbsolutePath());
        System.out.println(">>> Running: " + String.join(" ", command) + " (cwd=" + workingDir.getAbsolutePath() + ")");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Failed to read terraform output: " + e.getMessage();
            }
        });

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Terraform timed out after " + timeoutMinutes + " minutes: " + command);
        }

        String output;
        try {
            output = outputFuture.get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IOException("Failed waiting for terraform output: " + e.getMessage(), e);
        }
        int exitCode = process.exitValue();
        log.info("Command {} finished with exitCode={}", command, exitCode);
        return new CommandResult(exitCode, output.isBlank() ? "(no output)" : output.trim());
    }

    private String formatFailure(String step, String applicationName, String environment,
                                 String version, CommandResult result) {
        String msg = """
                Terraform deployment failed during %s.
                application=%s
                environment=%s
                version=%s
                status=FAILED
                exitCode=%d
                timestamp=%s

                --- output ---
                %s
                """.formatted(
                step,
                applicationName,
                environment,
                version,
                result.exitCode(),
                Instant.now(),
                result.output()
        ).trim();
        System.out.println(">>> TOOL simulateDeployment RESULT: FAILED (" + step + ")");
        log.error(msg);
        return msg;
    }

    private record CommandResult(int exitCode, String output) {
    }
}
