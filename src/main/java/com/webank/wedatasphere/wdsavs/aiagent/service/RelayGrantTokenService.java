package com.webank.wedatasphere.wdsavs.aiagent.service;

import java.util.List;

public interface RelayGrantTokenService {

    String sign(String grantId, String sessionId, String sourceNodeId, String targetNodeId,
                List<String> allowedCapabilities, String expiresAt);

    boolean validate(String token, String grantId, String sessionId, String sourceNodeId,
                     String targetNodeId, List<String> allowedCapabilities, String expiresAt);
}
