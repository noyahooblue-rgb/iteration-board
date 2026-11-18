package com.iterationboard.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI 平台配置：密钥只由环境变量注入，不入库、不进仓库明文。 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String baseUrl = "";
    private String apiKey = "";
    private String textModel = "";
    private String visionModel = "";
    /** mock | live */
    private String mode = "mock";
    private int timeoutSeconds = 60;
    private boolean platformEnabled = true;
    private String promptsDir = "config/prompts";

    public boolean isMock() { return !"live".equalsIgnoreCase(mode); }

    /** live 模式下文本模型是否已配齐（地址+密钥+模型名）。 */
    public boolean liveConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank()
                && textModel != null && !textModel.isBlank();
    }

    public boolean visionConfigured() {
        return isMock() || (liveConfigured() && visionModel != null && !visionModel.isBlank());
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getTextModel() { return textModel; }
    public void setTextModel(String v) { textModel = v; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String v) { visionModel = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { mode = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public boolean isPlatformEnabled() { return platformEnabled; }
    public void setPlatformEnabled(boolean v) { platformEnabled = v; }
    public String getPromptsDir() { return promptsDir; }
    public void setPromptsDir(String v) { promptsDir = v; }
}
