package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionContextEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionContextEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionContextServiceImplTest {

    @Test
    void appendsModelVisibleEventAndReturnsStableEventOnRetry() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextEventRepository repository = mock(AiSessionContextEventRepository.class);
        AiSessionContextServiceImpl service = new AiSessionContextServiceImpl(sessionService, repository);
        AiSessionContextAppendRequest request = new AiSessionContextAppendRequest();
        request.setEventId("context-event-1");
        request.setRole("user");
        request.setSenderType("CODEX");
        request.setSenderId("operator-1");
        request.setContent("查看节点状态");
        AiSessionContextEventEntity saved = new AiSessionContextEventEntity();
        saved.setId(11L);
        saved.setEventId("context-event-1");
        saved.setSessionId("session-1");
        saved.setRole("user");
        saved.setContent("查看节点状态");
        saved.setContentType("TEXT");
        saved.setCreatedTime("100");
        saved.setChecksum("checksum");
        when(repository.findByEventId("context-event-1")).thenReturn(Optional.empty(), Optional.of(saved));
        when(repository.saveAndFlush(any(AiSessionContextEventEntity.class))).thenAnswer(invocation -> {
            AiSessionContextEventEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return entity;
        });

        var first = service.append("session-1", request);
        var retry = service.append("session-1", request);

        assertEquals(11L, first.getCursor());
        assertEquals(first.getEventId(), retry.getEventId());
        assertEquals("checksum", retry.getChecksum());
    }

    @Test
    void rejectsEmptyContextEvent() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextEventRepository repository = mock(AiSessionContextEventRepository.class);
        AiSessionContextServiceImpl service = new AiSessionContextServiceImpl(sessionService, repository);
        AiSessionContextAppendRequest request = new AiSessionContextAppendRequest();

        assertThrows(IllegalArgumentException.class, () -> service.append("session-1", request));
    }

    @Test
    void readsOnlyEventsAfterSessionCursorWithBoundedLimit() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextEventRepository repository = mock(AiSessionContextEventRepository.class);
        AiSessionContextServiceImpl service = new AiSessionContextServiceImpl(sessionService, repository);
        AiSessionContextEventEntity event = new AiSessionContextEventEntity();
        event.setId(12L);
        event.setEventId("context-event-2");
        event.setSessionId("session-1");
        event.setRole("assistant");
        event.setContent("节点状态正常");
        event.setContentType("TEXT");
        event.setCreatedTime("101");
        event.setChecksum("checksum-2");
        when(repository.findBySessionIdAndIdGreaterThanOrderByIdAsc(eq("session-1"), eq(10L), any(Pageable.class)))
                .thenReturn(List.of(event));

        var result = service.delta("session-1", 10L, 5000);

        assertEquals(1, result.size());
        assertEquals(12L, result.get(0).getCursor());
        verify(sessionService).validateSessionExists("session-1");
    }
}
