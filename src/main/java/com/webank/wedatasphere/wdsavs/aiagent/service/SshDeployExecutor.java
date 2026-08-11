package com.webank.wedatasphere.wdsavs.aiagent.service;

public interface SshDeployExecutor {

    SshDeployResult deploy(SshDeployRequest request);
}
