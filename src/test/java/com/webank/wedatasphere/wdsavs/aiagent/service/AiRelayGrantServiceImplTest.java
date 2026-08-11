package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayGrantEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRenewRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRevokeRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayGrantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRelayGrantServiceImplTest {

    @Test
    void requestAccessUsesConfiguredDefaultTtlWhenRequestTtlMissing() {
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiRelayHeartbeatService heartbeatService = mock(AiRelayHeartbeatService.class);
        AiRelayRegistryService relayRegistryService = mock(AiRelayRegistryService.class);
        RelayGrantTokenService relayGrantTokenService = new RelayGrantTokenServiceImpl();
        AtomicReference<AiRelayGrantEntity> savedGrant = new AtomicReference<>();
        long defaultTtlMs = 123_456L;

        when(grantRepository.findByRequestId("req-default-ttl")).thenReturn(Optional.empty());
        when(grantRepository.save(any(AiRelayGrantEntity.class))).thenAnswer(invocation -> {
            AiRelayGrantEntity entity = invocation.getArgument(0);
            savedGrant.set(entity);
            return entity;
        });
        when(grantRepository.findByGrantId(any())).thenAnswer(invocation -> Optional.ofNullable(savedGrant.get()));
        when(heartbeatService.isNodeAvailable(eq("target-node:19091"))).thenReturn(true);

        RelayNodeView node = new RelayNodeView();
        node.setNodeId("target-node:19091");
        node.setRelayEndpoint("http://target-node:19091");
        node.setStatus("AVAILABLE");
        when(relayRegistryService.getNode("target-node:19091")).thenReturn(node);

        AiRelayGrantServiceImpl service = new AiRelayGrantServiceImpl(
                grantRepository,
                heartbeatService,
                relayGrantTokenService,
                relayRegistryService,
                defaultTtlMs,
                true,
                true,
                true,
                true,
                List.of("*"));

        RelayAccessRequest request = new RelayAccessRequest();
        request.setSessionId("session-default-ttl");
        request.setRequestId("req-default-ttl");
        request.setSourceNodeId("source-node:18091");
        request.setTargetNodeId("target-node:19091");
        request.setReason("default ttl test");
        request.setRequiredCapabilities(List.of("A2A_TASK_CREATE"));

        long before = System.currentTimeMillis();
        RelayAccessDecisionResponse response = service.requestAccess(request);
        long after = System.currentTimeMillis();

        assertEquals("ALLOW", response.getDecision());
        assertNotNull(response.getGrantId());
        assertNotNull(response.getSignedToken());
        assertEquals(List.of("A2A_TASK_CREATE"), response.getAllowedCapabilities());

        AiRelayGrantEntity entity = savedGrant.get();
        assertNotNull(entity);
        long expiresAt = Long.parseLong(entity.getExpireAt());
        assertTrue(expiresAt >= before + defaultTtlMs);
        assertTrue(expiresAt <= after + defaultTtlMs + 2_000L);
    }

    @Test
    void revokeImmediateInvalidatesGrantValidation() {
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiRelayHeartbeatService heartbeatService = mock(AiRelayHeartbeatService.class);
        AiRelayRegistryService relayRegistryService = mock(AiRelayRegistryService.class);
        RelayGrantTokenService relayGrantTokenService = new RelayGrantTokenServiceImpl();
        AtomicReference<AiRelayGrantEntity> savedGrant = new AtomicReference<>();

        when(grantRepository.findByRequestId("req-revoke-1")).thenReturn(Optional.empty());
        when(grantRepository.save(any(AiRelayGrantEntity.class))).thenAnswer(invocation -> {
            AiRelayGrantEntity entity = invocation.getArgument(0);
            savedGrant.set(entity);
            return entity;
        });
        when(grantRepository.findByGrantId(any())).thenAnswer(invocation -> Optional.ofNullable(savedGrant.get()));
        when(heartbeatService.isNodeAvailable(eq("target-node:19091"))).thenReturn(true);

        RelayNodeView node = new RelayNodeView();
        node.setNodeId("target-node:19091");
        node.setRelayEndpoint("http://target-node:19091");
        node.setStatus("AVAILABLE");
        when(relayRegistryService.getNode("target-node:19091")).thenReturn(node);

        AiRelayGrantServiceImpl service = new AiRelayGrantServiceImpl(
                grantRepository,
                heartbeatService,
                relayGrantTokenService,
                relayRegistryService,
                60_000L,
                true,
                true,
                true,
                true,
                List.of("*"));

        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId("session-revoke-1");
        accessRequest.setRequestId("req-revoke-1");
        accessRequest.setSourceNodeId("source-node:18091");
        accessRequest.setTargetNodeId("target-node:19091");
        accessRequest.setRequiredCapabilities(List.of("A2A_MESSAGE_SEND"));

        RelayAccessDecisionResponse decision = service.requestAccess(accessRequest);
        AiRelayGrantEntity entity = savedGrant.get();
        assertEquals("ACTIVE", entity.getStatus());

        RelayGrantRevokeRequest revokeRequest = new RelayGrantRevokeRequest();
        revokeRequest.setGrantId(entity.getGrantId());
        revokeRequest.setReason("manual revoke");
        assertTrue(service.revokeGrant(revokeRequest));
        assertEquals("REVOKED", entity.getStatus());
        assertNotNull(entity.getRevokedAt());

        RelayGrantValidateRequest validateRequest = new RelayGrantValidateRequest();
        validateRequest.setGrantId(entity.getGrantId());
        validateRequest.setSignedToken(decision.getSignedToken());
        validateRequest.setAllowedCapabilities(List.of("A2A_MESSAGE_SEND"));

        RelayGrantValidateResponse validation = service.validateGrant(validateRequest);
        assertFalse(validation.getValid());
        assertEquals("REVOKED", validation.getStatus());
        assertEquals("Grant is not active", validation.getMessage());
    }

    @Test
    void renewGrantExtendsWaitingDeployGrantWithoutSigningToken() {
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiRelayHeartbeatService heartbeatService = mock(AiRelayHeartbeatService.class);
        AiRelayRegistryService relayRegistryService = mock(AiRelayRegistryService.class);
        RelayGrantTokenService relayGrantTokenService = new RelayGrantTokenServiceImpl();
        AtomicReference<AiRelayGrantEntity> savedGrant = new AtomicReference<>();

        when(grantRepository.findByRequestId("req-waiting-deploy-renew")).thenReturn(Optional.empty());
        when(grantRepository.save(any(AiRelayGrantEntity.class))).thenAnswer(invocation -> {
            AiRelayGrantEntity entity = invocation.getArgument(0);
            savedGrant.set(entity);
            return entity;
        });
        when(grantRepository.findByGrantId(any())).thenAnswer(invocation -> Optional.ofNullable(savedGrant.get()));
        when(heartbeatService.isNodeAvailable(eq("target-node:19091"))).thenReturn(false);

        RelayNodeView node = new RelayNodeView();
        node.setNodeId("target-node:19091");
        node.setRelayEndpoint("http://target-node:19091");
        node.setStatus("UNAVAILABLE");
        when(relayRegistryService.getNode("target-node:19091")).thenReturn(node);

        AiRelayGrantServiceImpl service = new AiRelayGrantServiceImpl(
                grantRepository,
                heartbeatService,
                relayGrantTokenService,
                relayRegistryService,
                60_000L,
                true,
                true,
                true,
                true,
                List.of("*"));

        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId("session-waiting-deploy-renew");
        accessRequest.setRequestId("req-waiting-deploy-renew");
        accessRequest.setSourceNodeId("source-node:18091");
        accessRequest.setTargetNodeId("target-node:19091");
        accessRequest.setRequiredCapabilities(List.of("A2A_MESSAGE_SEND"));

        RelayAccessDecisionResponse decision = service.requestAccess(accessRequest);
        assertEquals("ALLOW_WITH_DEPLOY", decision.getDecision());

        AiRelayGrantEntity entity = savedGrant.get();
        assertNotNull(entity);
        assertEquals("WAITING_DEPLOY", entity.getStatus());
        assertNull(entity.getSignedToken());
        long previousExpiresAt = Long.parseLong(entity.getExpireAt());

        RelayGrantRenewRequest renewRequest = new RelayGrantRenewRequest();
        renewRequest.setGrantId(entity.getGrantId());
        renewRequest.setTtlMs(120_000L);

        RelayGrantView renewed = service.renewGrant(renewRequest);
        assertEquals(entity.getGrantId(), renewed.getGrantId());
        assertEquals("WAITING_DEPLOY", renewed.getStatus());
        assertNull(savedGrant.get().getSignedToken());
        assertTrue(Long.parseLong(savedGrant.get().getExpireAt()) > previousExpiresAt);
    }

    @Test
    void requestAccessRejectsClosedSessionWhenSessionServiceIsConfigured() {
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiRelayHeartbeatService heartbeatService = mock(AiRelayHeartbeatService.class);
        AiRelayRegistryService relayRegistryService = mock(AiRelayRegistryService.class);
        AiSessionService sessionService = mock(AiSessionService.class);
        RelayGrantTokenService relayGrantTokenService = new RelayGrantTokenServiceImpl();

        org.mockito.Mockito.doThrow(new IllegalArgumentException("Session is not open: session-closed-1"))
                .when(sessionService).validateSession("session-closed-1");

        AiRelayGrantServiceImpl service = new AiRelayGrantServiceImpl(
                grantRepository,
                heartbeatService,
                relayGrantTokenService,
                relayRegistryService,
                null,
                null,
                null,
                sessionService,
                60_000L,
                true,
                true,
                true,
                true,
                List.of("*"));

        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId("session-closed-1");
        accessRequest.setRequestId("req-closed-session");
        accessRequest.setSourceNodeId("source-node:18091");
        accessRequest.setTargetNodeId("target-node:19091");
        accessRequest.setRequiredCapabilities(List.of("A2A_MESSAGE_SEND"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.requestAccess(accessRequest));

        assertEquals("Session is not open: session-closed-1", exception.getMessage());
        verify(sessionService).validateSession("session-closed-1");
        verify(grantRepository, never()).save(any(AiRelayGrantEntity.class));
    }

}
