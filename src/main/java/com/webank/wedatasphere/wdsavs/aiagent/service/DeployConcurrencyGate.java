package com.webank.wedatasphere.wdsavs.aiagent.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class DeployConcurrencyGate {

    private static final Permit NOOP_PERMIT = new Permit(null, null, null);
    private final ConcurrentHashMap<String, BatchGate> batches = new ConcurrentHashMap<>();

    Permit acquire(String batchId, int concurrency) {
        if (batchId == null || batchId.isBlank()) {
            return NOOP_PERMIT;
        }
        int normalizedConcurrency = Math.max(1, Math.min(32, concurrency));
        BatchGate batch = batches.compute(batchId, (key, existing) -> {
            BatchGate selected = existing == null ? new BatchGate(normalizedConcurrency) : existing;
            selected.references.incrementAndGet();
            return selected;
        });
        try {
            batch.semaphore.acquire();
            return new Permit(this, batchId, batch);
        } catch (InterruptedException exception) {
            releaseReference(batchId, batch, false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Deployment batch slot wait was interrupted", exception);
        }
    }

    int batchCount() {
        return batches.size();
    }

    private void release(String batchId, BatchGate batch) {
        releaseReference(batchId, batch, true);
    }

    private void releaseReference(String batchId, BatchGate batch, boolean releaseSemaphore) {
        if (releaseSemaphore) {
            batch.semaphore.release();
        }
        if (batch.references.decrementAndGet() == 0) {
            batches.compute(batchId, (key, current) ->
                    current == batch && batch.references.get() == 0 ? null : current);
        }
    }

    private static final class BatchGate {
        private final Semaphore semaphore;
        private final AtomicInteger references = new AtomicInteger();

        private BatchGate(int concurrency) {
            this.semaphore = new Semaphore(concurrency, true);
        }
    }

    static final class Permit implements AutoCloseable {
        private final DeployConcurrencyGate owner;
        private final String batchId;
        private final BatchGate batch;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(DeployConcurrencyGate owner, String batchId, BatchGate batch) {
            this.owner = owner;
            this.batchId = batchId;
            this.batch = batch;
        }

        @Override
        public void close() {
            if (owner != null && closed.compareAndSet(false, true)) {
                owner.release(batchId, batch);
            }
        }
    }
}
