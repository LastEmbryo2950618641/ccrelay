package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AgentControlState;

@FunctionalInterface
public interface ReactControlStateReader {
    AgentControlState readControlState();
}
