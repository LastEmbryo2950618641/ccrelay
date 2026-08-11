package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.util.Map;

@FunctionalInterface
public interface ReactEventWriter {
    void appendEvent(String eventType, Map<String, Object> payload);
}
