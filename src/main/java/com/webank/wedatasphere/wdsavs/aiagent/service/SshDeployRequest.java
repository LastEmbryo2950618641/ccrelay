package com.webank.wedatasphere.wdsavs.aiagent.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SshDeployRequest {
    private String host;
    private Integer port = 22;
    private String username;
    private Integer relayPort;
    private Boolean replaceExistingRelay = false;
    private String sourceHost;
    private Integer sourcePort = 22;
    private String sourceUsername;
    private String scriptPath;
    private String artifactPath;
    private String remoteDirectory;
    private Long timeoutMs;
    private List<String> commandArguments = new ArrayList<>();
    private transient SshDeployProgressListener progressListener;
}
