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
@Table(name = "wdsavs_ai_runtime_config")
public class AiRuntimeConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", unique = true)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "MEDIUMTEXT")
    private String configValue;

    @Column(name = "masked_value")
    private String maskedValue;

    @Column(name = "value_fingerprint")
    private String valueFingerprint;

    @Column(name = "config_type")
    private String configType;

    @Column(name = "dynamic_enabled")
    private Boolean dynamicEnabled;

    @Column(name = "restart_required")
    private Boolean restartRequired;

    @Column(name = "secret")
    private Boolean secret;

    @Column(name = "version")
    private Long version;

    @Column(name = "description")
    private String description;

    @Column(name = "create_user")
    private String createUser;

    @Column(name = "modify_user")
    private String modifyUser;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
