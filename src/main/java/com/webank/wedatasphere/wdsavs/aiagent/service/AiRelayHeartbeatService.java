package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatScanResponse;

public interface AiRelayHeartbeatService {

    RelayHeartbeatResponse heartbeat(RelayHeartbeatRequest request);

    RelayHeartbeatScanResponse scanNodeAvailability();

    boolean isNodeAvailable(String nodeId);
}