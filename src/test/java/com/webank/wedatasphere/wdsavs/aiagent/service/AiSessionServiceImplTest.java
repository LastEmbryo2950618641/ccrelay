package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayGrantEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayGrantRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionServiceImplTest {

    @Test
    void closeSessionRevokesSessionGrantsImmediately() {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiSessionEntity session = new AiSessionEntity();
        session.setSessionId("session-close-1");
        session.setStatus("OPEN");
        AiRelayGrantEntity activeGrant = grant("grant-active", "ACTIVE");
        AiRelayGrantEntity waitingGrant = grant("grant-waiting", "WAITING_DEPLOY");
        AiRelayGrantEntity revokedGrant = grant("grant-revoked", "REVOKED");

        when(sessionRepository.findBySessionId("session-close-1")).thenReturn(Optional.of(session));
        when(grantRepository.findBySessionIdOrderByCreateTimeDesc("session-close-1"))
                .thenReturn(List.of(activeGrant, waitingGrant, revokedGrant));
        when(sessionRepository.save(any(AiSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(grantRepository.save(any(AiRelayGrantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiSessionServiceImpl service = new AiSessionServiceImpl(sessionRepository, grantRepository);
        service.closeSession("session-close-1");

        assertEquals("CLOSED", session.getStatus());
        assertEquals("REVOKED", activeGrant.getStatus());
        assertEquals("REVOKED", waitingGrant.getStatus());
        assertEquals("REVOKED", revokedGrant.getStatus());
        assertEquals("session closed", activeGrant.getReason());
        assertEquals("session closed", waitingGrant.getReason());
        verify(grantRepository).save(activeGrant);
        verify(grantRepository).save(waitingGrant);
    }

    @Test
    void validateSessionRejectsClosedSession() {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiSessionEntity session = new AiSessionEntity();
        session.setSessionId("session-closed-1");
        session.setStatus("CLOSED");

        when(sessionRepository.findBySessionId("session-closed-1")).thenReturn(Optional.of(session));

        AiSessionServiceImpl service = new AiSessionServiceImpl(sessionRepository, grantRepository);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.validateSession("session-closed-1"));

        assertTrue(exception.getMessage().contains("Session is not open"));
    }

    @Test
    void validateSessionExistsAllowsClosedSession() {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiRelayGrantRepository grantRepository = mock(AiRelayGrantRepository.class);
        AiSessionEntity session = new AiSessionEntity();
        session.setSessionId("session-closed-1");
        session.setStatus("CLOSED");

        when(sessionRepository.findBySessionId("session-closed-1")).thenReturn(Optional.of(session));

        AiSessionServiceImpl service = new AiSessionServiceImpl(sessionRepository, grantRepository);
        service.validateSessionExists("session-closed-1");

        verify(sessionRepository).findBySessionId("session-closed-1");
    }

    private AiRelayGrantEntity grant(String grantId, String status) {
        AiRelayGrantEntity entity = new AiRelayGrantEntity();
        entity.setGrantId(grantId);
        entity.setStatus(status);
        return entity;
    }
}
