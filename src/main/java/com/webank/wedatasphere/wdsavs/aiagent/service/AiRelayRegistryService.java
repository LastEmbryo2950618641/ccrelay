package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterResponse;

import java.util.List;

public interface AiRelayRegistryService {

    RelayRegisterResponse register(RelayRegisterRequest request);

    RelayNodeView getNode(String nodeId);

    List<RelayNodeView> listNodes();
}
