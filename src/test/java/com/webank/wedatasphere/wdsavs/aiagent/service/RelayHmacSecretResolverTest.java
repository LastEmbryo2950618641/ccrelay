package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelayHmacSecretResolverTest {

    private static final String SYSTEM_PROPERTY = "wdsavs.ai.hmac.secret";

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(SYSTEM_PROPERTY);
    }

    @Test
    void explicitSystemSecretWinsOverStaleRuntimeConfig() {
        System.setProperty(SYSTEM_PROPERTY, "deployed-shared-secret");
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getString("wdsavs.ai.relay.hmac-secret", null))
                .thenReturn("stale-development-secret");

        RelayGrantTokenServiceImpl signer = new RelayGrantTokenServiceImpl(runtimeConfigService);
        RelayGrantTokenServiceImpl verifier = new RelayGrantTokenServiceImpl();
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60_000L);
        String token = signer.sign("grant-1", "session-1", "source-1", "target-1",
                List.of("A2A_MESSAGE_SEND"), expiresAt);

        assertTrue(verifier.validate(token, "grant-1", "session-1", "source-1", "target-1",
                List.of("A2A_MESSAGE_SEND"), expiresAt));
    }
}
