package org.example.springai_agent_terraform_deploy_do.tools;

/**
 * Captures the latest deployViaJenkins structured result for the current request thread
 * so the chat UI can render a deploy card even if the model omits the marker block.
 */
public final class DeployResultHolder {

    private static final ThreadLocal<String> LAST_BLOCK = new ThreadLocal<>();

    private DeployResultHolder() {
    }

    public static void setBlock(String block) {
        LAST_BLOCK.set(block);
    }

    public static String takeBlock() {
        String value = LAST_BLOCK.get();
        LAST_BLOCK.remove();
        return value;
    }
}
