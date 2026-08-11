package com.webank.wedatasphere.wdsavs.aiagent.service;

@FunctionalInterface
public interface SshDeployProgressListener {

    void onProgress(SshDeployProgress progress);
}
