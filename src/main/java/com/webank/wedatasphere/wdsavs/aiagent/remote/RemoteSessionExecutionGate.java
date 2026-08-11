package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

final class RemoteSessionExecutionGate {

    private final Map<String, SessionQueue> queues = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    RemoteSessionExecutionGate(int maxConcurrentSessions) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, maxConcurrentSessions));
    }

    <T> ImmediateResult<T> tryExecute(String sessionId, Callable<T> action) throws Exception {
        String key = key(sessionId, null);
        SessionQueue queue = queues.computeIfAbsent(key, ignored -> new SessionQueue());
        synchronized (queue) {
            if (queue.running || !queue.pending.isEmpty()) {
                return ImmediateResult.busy();
            }
            queue.running = true;
        }
        try {
            return ImmediateResult.completed(action.call());
        } finally {
            complete(key, queue);
        }
    }

    Future<?> submit(String sessionId, String fallbackId, Runnable action) {
        String key = key(sessionId, fallbackId);
        SessionQueue queue = queues.computeIfAbsent(key, ignored -> new SessionQueue());
        FutureTask<Void> task = new FutureTask<>(action, null);
        synchronized (queue) {
            queue.pending.addLast(task);
            scheduleNext(key, queue);
        }
        return task;
    }

    void shutdownNow() {
        executor.shutdownNow();
    }

    private void scheduleNext(String key, SessionQueue queue) {
        if (queue.running) {
            return;
        }
        FutureTask<?> next = queue.pending.pollFirst();
        if (next == null) {
            queues.remove(key, queue);
            return;
        }
        queue.running = true;
        executor.execute(() -> {
            try {
                next.run();
            } finally {
                complete(key, queue);
            }
        });
    }

    private void complete(String key, SessionQueue queue) {
        synchronized (queue) {
            queue.running = false;
            scheduleNext(key, queue);
        }
    }

    private String key(String sessionId, String fallbackId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId.trim();
        }
        if (fallbackId != null && !fallbackId.isBlank()) {
            return "task:" + fallbackId.trim();
        }
        return "anonymous:" + UUID.randomUUID();
    }

    static final class ImmediateResult<T> {
        private final boolean busy;
        private final T value;

        private ImmediateResult(boolean busy, T value) {
            this.busy = busy;
            this.value = value;
        }

        static <T> ImmediateResult<T> busy() {
            return new ImmediateResult<>(true, null);
        }

        static <T> ImmediateResult<T> completed(T value) {
            return new ImmediateResult<>(false, value);
        }

        boolean isBusy() {
            return busy;
        }

        T getValue() {
            return value;
        }
    }

    private static final class SessionQueue {
        private final Deque<FutureTask<?>> pending = new ArrayDeque<>();
        private boolean running;
    }
}
