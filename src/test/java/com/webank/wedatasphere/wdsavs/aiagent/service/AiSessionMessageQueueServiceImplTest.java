package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionMessageQueueEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageDispatchResult;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionMessageQueueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSessionMessageQueueServiceImplTest {

    private final List<AiSessionMessageQueueServiceImpl> services = new ArrayList<>();

    @AfterEach
    void tearDown() {
        services.forEach(AiSessionMessageQueueServiceImpl::stop);
    }

    @Test
    void preservesFifoForSameSessionAndTarget() throws Exception {
        InMemoryRepository storage = new InMemoryRepository();
        AiSessionMessageQueueServiceImpl service = service(storage);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allCompleted = new CountDownLatch(2);
        List<String> order = new CopyOnWriteArrayList<>();
        service.registerDispatcher(params -> {
            String requestId = String.valueOf(params.get("requestId"));
            order.add(requestId + "-start");
            if ("request-1".equals(requestId)) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            order.add(requestId + "-end");
            allCompleted.countDown();
            return new AiChatResponse(requestId, "SUCCESS", requestId);
        });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            caller.submit(() -> service.submit(params("session-1", "node-1", "request-1"), "request-1", true));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            AiSessionMessageDispatchResult second = service.submit(
                    params("session-1", "node-1", "request-2"), "request-2", true);
            assertTrue(second.isQueued());
            releaseFirst.countDown();
            assertTrue(allCompleted.await(4, TimeUnit.SECONDS));
            assertEquals(List.of("request-1-start", "request-1-end", "request-2-start", "request-2-end"), order);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void runsDifferentTargetsAndSessionsInParallel() throws Exception {
        InMemoryRepository storage = new InMemoryRepository();
        AiSessionMessageQueueServiceImpl service = service(storage);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        service.registerDispatcher(params -> {
            started.countDown();
            await(release);
            return new AiChatResponse("ok", "SUCCESS", String.valueOf(params.get("requestId")));
        });

        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            callers.submit(() -> service.submit(params("session-1", "node-a", "request-a"), "request-a", false));
            callers.submit(() -> service.submit(params("session-1", "node-b", "request-b"), "request-b", false));
            callers.submit(() -> service.submit(params("session-2", "node-a", "request-c"), "request-c", false));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            release.countDown();
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void deduplicatesRequestAndRetriesBusyRelay() throws Exception {
        InMemoryRepository storage = new InMemoryRepository();
        AiSessionMessageQueueServiceImpl service = service(storage);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        service.registerDispatcher(params -> {
            if (calls.incrementAndGet() == 1) {
                return new AiChatResponse("busy", "BUSY", null);
            }
            completed.countDown();
            return new AiChatResponse("done", "SUCCESS", "trace");
        });

        AiSessionMessageDispatchResult first = service.submit(
                params("session-1", "node-1", "request-1"), "request-1", true);
        AiSessionMessageDispatchResult duplicate = service.submit(
                params("session-1", "node-1", "request-1"), "request-1", true);

        assertTrue(first.isQueued());
        assertTrue(duplicate.isQueued());
        assertEquals(first.getQueueId(), duplicate.getQueueId());
        assertTrue(completed.await(4, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        AiSessionMessageQueueEntity entity = storage.items.get(0);
        assertEquals(AiSessionMessageQueueServiceImpl.SUCCEEDED, entity.getStatus());
        assertTrue(entity.getDeferred());
    }

    @Test
    void recoversExpiredRunningLeaseAndCancelsSession() {
        InMemoryRepository storage = new InMemoryRepository();
        AiSessionMessageQueueEntity expired = entity("session-1", "node-1", "request-1");
        expired.setStatus(AiSessionMessageQueueServiceImpl.RUNNING);
        expired.setLeaseUntil(System.currentTimeMillis() - 1L);
        storage.save(expired);

        AiSessionMessageQueueServiceImpl service = service(storage);
        service.cancelSession("session-1");

        assertEquals(AiSessionMessageQueueServiceImpl.CANCELED, expired.getStatus());
        assertFalse(Boolean.TRUE.equals(expired.getWakeDispatched()));
    }

    private AiSessionMessageQueueServiceImpl service(InMemoryRepository storage) {
        AiSessionMessageQueueServiceImpl service = new AiSessionMessageQueueServiceImpl(
                storage.repository, new ObjectMapper(), 4, 4, 5000L, 50L);
        services.add(service);
        service.start();
        return service;
    }

    private Map<String, Object> params(String sessionId, String targetNodeId, String requestId) {
        return Map.of(
                "sessionId", sessionId,
                "sourceNodeId", "source",
                "targetNodeId", targetNodeId,
                "requestId", requestId);
    }

    private AiSessionMessageQueueEntity entity(String sessionId, String targetNodeId, String requestId) {
        AiSessionMessageQueueEntity entity = new AiSessionMessageQueueEntity();
        entity.setQueueId(requestId);
        entity.setSessionId(sessionId);
        entity.setSourceNodeId("source");
        entity.setTargetNodeId(targetNodeId);
        entity.setRequestId(requestId);
        entity.setStatus(AiSessionMessageQueueServiceImpl.PENDING);
        entity.setRequestPayloadJson("{}");
        entity.setAttemptCount(0);
        entity.setMaxAttempts(4);
        entity.setDeferred(false);
        entity.setWakeRequired(false);
        entity.setWakeDispatched(false);
        entity.setCreateTime(String.valueOf(System.currentTimeMillis()));
        entity.setUpdateTime(entity.getCreateTime());
        return entity;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class InMemoryRepository {
        private final List<AiSessionMessageQueueEntity> items = new CopyOnWriteArrayList<>();
        private final AtomicLong ids = new AtomicLong();
        private final AiSessionMessageQueueRepository repository = mock(AiSessionMessageQueueRepository.class);

        private InMemoryRepository() {
            when(repository.save(any())).thenAnswer(invocation -> save(invocation.getArgument(0)));
            when(repository.saveAndFlush(any())).thenAnswer(invocation -> save(invocation.getArgument(0)));
            when(repository.findBySessionIdAndTargetNodeIdAndRequestId(anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> items.stream().filter(item ->
                                    invocation.getArgument(0).equals(item.getSessionId())
                                            && invocation.getArgument(1).equals(item.getTargetNodeId())
                                            && invocation.getArgument(2).equals(item.getRequestId()))
                            .findFirst());
            when(repository.findByStatusInOrderByCreateTimeAscIdAsc(anyCollection()))
                    .thenAnswer(invocation -> byStatuses(invocation.getArgument(0)));
            when(repository.findBySessionIdAndTargetNodeIdAndStatusInOrderByCreateTimeAscIdAsc(
                    anyString(), anyString(), anyCollection())).thenAnswer(invocation -> byStatuses(invocation.getArgument(2)).stream()
                    .filter(item -> invocation.getArgument(0).equals(item.getSessionId())
                            && invocation.getArgument(1).equals(item.getTargetNodeId())).toList());
            when(repository.findBySessionIdAndStatusIn(anyString(), anyCollection())).thenAnswer(invocation ->
                    byStatuses(invocation.getArgument(1)).stream()
                            .filter(item -> invocation.getArgument(0).equals(item.getSessionId())).toList());
            when(repository.findByStatusAndWakeRequiredTrueAndWakeDispatchedFalseOrderByUpdateTimeAsc(anyString()))
                    .thenAnswer(invocation -> items.stream().filter(item -> invocation.getArgument(0).equals(item.getStatus())
                                    && Boolean.TRUE.equals(item.getWakeRequired())
                                    && !Boolean.TRUE.equals(item.getWakeDispatched()))
                            .sorted(Comparator.comparing(AiSessionMessageQueueEntity::getUpdateTime)).toList());
        }

        private AiSessionMessageQueueEntity save(AiSessionMessageQueueEntity entity) {
            if (entity.getId() == null) {
                entity.setId(ids.incrementAndGet());
                items.add(entity);
            }
            return entity;
        }

        private List<AiSessionMessageQueueEntity> byStatuses(Collection<String> statuses) {
            return items.stream().filter(item -> statuses.contains(item.getStatus()))
                    .sorted(Comparator.comparing(AiSessionMessageQueueEntity::getCreateTime)
                            .thenComparing(AiSessionMessageQueueEntity::getId))
                    .toList();
        }
    }
}
