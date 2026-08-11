package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiModelProviderConfig {

    private Long id;
    private String providerCode;
    private String displayName;
    private String protocol;
    private String baseUrl;
    private String apiPath;
    private String apiKey;
    private String apiKeyMasked;
    private String modelName;
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    private Double presencePenalty;
    private Double frequencyPenalty;
    private String extraBodyJson;
    private String extraHeadersJson;
    private Boolean enabled;
    private Boolean defaultConfig;
    private String createUser;
    private String createTime;
    private String modifyUser;
    private String modifyTime;
}
