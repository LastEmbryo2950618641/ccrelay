package com.webank.wedatasphere.wdsavs.aiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class A2aAgentCapabilities {

    private Boolean streaming = true;
    private Boolean remoteRelay = true;
    private Boolean pushNotifications = false;
    private Boolean stateTransitionHistory = false;

    @JsonIgnore
    public Boolean getSupportsStreaming() {
        return streaming;
    }

    @JsonIgnore
    public Boolean getSupportsRemoteRelay() {
        return remoteRelay;
    }

    @JsonIgnore
    public Boolean getSupportsPushNotifications() {
        return pushNotifications;
    }

    @JsonIgnore
    public Boolean getSupportsStateTransitionHistory() {
        return stateTransitionHistory;
    }
}
