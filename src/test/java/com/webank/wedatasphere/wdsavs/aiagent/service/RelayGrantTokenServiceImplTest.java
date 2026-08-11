package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayGrantTokenServiceImplTest {

    private final RelayGrantTokenServiceImpl service = new RelayGrantTokenServiceImpl();

    @Test
    void validatesRequestedCapabilitySubsetAgainstBroaderGrantToken() {
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60_000L);
        String token = service.sign(
                "grant-subset-1",
                "session-subset-1",
                "source-node-1",
                "target-node-1",
                List.of("A2A_MESSAGE_SEND", "A2A_TASK_CREATE", "A2A_TASK_CANCEL"),
                expiresAt);

        assertTrue(service.validate(token,
                "grant-subset-1",
                "session-subset-1",
                "source-node-1",
                "target-node-1",
                List.of("A2A_MESSAGE_SEND"),
                expiresAt));

        assertTrue(service.validate(token,
                "grant-subset-1",
                "session-subset-1",
                "source-node-1",
                "target-node-1",
                List.of("A2A_TASK_CANCEL", "A2A_TASK_CREATE"),
                expiresAt));
    }

    @Test
    void rejectsCapabilityOutsideGrantedSet() {
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60_000L);
        String token = service.sign(
                "grant-subset-2",
                "session-subset-2",
                "source-node-2",
                "target-node-2",
                List.of("A2A_MESSAGE_SEND", "A2A_TASK_CREATE"),
                expiresAt);

        assertFalse(service.validate(token,
                "grant-subset-2",
                "session-subset-2",
                "source-node-2",
                "target-node-2",
                List.of("A2A_TASK_GET"),
                expiresAt));
    }
}
