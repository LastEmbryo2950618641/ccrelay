package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RelayAccessDecisionResponse {
    private String auditId;
    private String decision;
    private String grantId;
    private String signedToken;
    private String targetRelayEndpoint;
    private List<String> allowedCapabilities = new ArrayList<>();
    private String expiresAt;
    private String errorCode;
    private String message;
}

