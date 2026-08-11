package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SshIdentityStateRequest {
    private String clusterId = "default";
    private Boolean replaceSnapshot = true;
    private String accountMode;
    private Boolean dedicatedAccountCreationAllowed;
    private String dedicatedUsername;
    private String dedicatedAccountStatus;
    private String clusterKeyMode;
    private String clusterKeyFingerprint;
    private String secretConfigRef;
    private String centerNodeId;
    private String operation;
    private String operatorId;
    private Long expectedRevision;
    private List<SshIdentityNodeStateRequest> nodes = new ArrayList<>();
    private List<SshIdentityTrustEdgeRequest> trustEdges = new ArrayList<>();
}
