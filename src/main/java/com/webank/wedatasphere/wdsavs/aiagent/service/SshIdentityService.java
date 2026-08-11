package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityStateRequest;

import java.util.Map;

public interface SshIdentityService {
    Map<String, Object> getState(String clusterId);

    Map<String, Object> saveState(SshIdentityStateRequest request);
}
