package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "wdsavs_ai_model_config")
public class AiModelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_code")
    private String providerCode;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "protocol")
    private String protocol;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "api_path")
    private String apiPath;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "top_p")
    private Double topP;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "presence_penalty")
    private Double presencePenalty;

    @Column(name = "frequency_penalty")
    private Double frequencyPenalty;

    @Column(name = "extra_body_json", columnDefinition = "MEDIUMTEXT")
    private String extraBodyJson;

    @Column(name = "extra_headers_json", columnDefinition = "MEDIUMTEXT")
    private String extraHeadersJson;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "default_config")
    private Boolean defaultConfig;

    @Column(name = "create_user")
    private String createUser;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "modify_user")
    private String modifyUser;

    @Column(name = "modify_time")
    private String modifyTime;
}
