package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRuntimeConfigEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiAuditLogRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRuntimeConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceImplTest {

    @Test
    void setConfigUpdatesValueAndSeedsSystemProperty() {
        AiRuntimeConfigRepository configRepository = mock(AiRuntimeConfigRepository.class);
        AiAuditLogRepository auditLogRepository = mock(AiAuditLogRepository.class);
        AiAuditService auditService = mock(AiAuditService.class);

        when(configRepository.findByConfigKey("wdsavs.ai.relay.grant.default-ttl-ms")).thenReturn(Optional.empty());
        when(configRepository.save(any(AiRuntimeConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");

        RuntimeConfigServiceImpl service = new RuntimeConfigServiceImpl(configRepository, auditLogRepository, auditService);

        RuntimeConfigView view = service.setConfig("wdsavs.ai.relay.grant.default-ttl-ms", "120000", "operator-1", "update ttl");

        assertEquals("wdsavs.ai.relay.grant.default-ttl-ms", view.getKey());
        assertEquals("120000", view.getValue());
        assertTrue(view.getDynamic());
        assertFalse(view.getSecret());
        assertEquals("120000", System.getProperty("wdsavs.ai.relay.grant.default-ttl-ms"));
    }

    @Test
    void secretConfigIsMaskedOnReadButRawOnTypedLookup() {
        AiRuntimeConfigRepository configRepository = mock(AiRuntimeConfigRepository.class);
        AiAuditLogRepository auditLogRepository = mock(AiAuditLogRepository.class);
        AiAuditService auditService = mock(AiAuditService.class);
        AtomicReference<AiRuntimeConfigEntity> saved = new AtomicReference<>();

        when(configRepository.findByConfigKey("wdsavs.ai.relay.hmac-secret")).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(configRepository.save(any(AiRuntimeConfigEntity.class))).thenAnswer(invocation -> {
            AiRuntimeConfigEntity entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-2");

        RuntimeConfigServiceImpl service = new RuntimeConfigServiceImpl(configRepository, auditLogRepository, auditService);
        RuntimeConfigView view = service.setSecretConfig("wdsavs.ai.relay.hmac-secret", "top-secret-value", "operator-1", "rotate");

        assertNull(view.getValue());
        assertNotNull(view.getMaskedValue());
        assertTrue(view.getMaskedValue().startsWith("****"));
        assertTrue(view.getRestartRequired());
        assertEquals("top-secret-value", service.getString("wdsavs.ai.relay.hmac-secret", null));
    }
}
