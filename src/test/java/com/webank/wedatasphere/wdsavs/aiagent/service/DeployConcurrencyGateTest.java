package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployConcurrencyGateTest {

    @Test
    void limitsActualWorkWithinTheSameDeploymentBatch() throws Exception {
        DeployConcurrencyGate gate = new DeployConcurrencyGate();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch completed = new CountDownLatch(8);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        for (int index = 0; index < 8; index++) {
            executor.submit(() -> {
                try (DeployConcurrencyGate.Permit ignored = gate.acquire("batch-a", 3)) {
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    Thread.sleep(40L);
                    active.decrementAndGet();
                } catch (Throwable throwable) {
                    synchronized (failures) {
                        failures.add(throwable);
                    }
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(3, maximum.get());
        assertEquals(0, gate.batchCount());
    }
}
