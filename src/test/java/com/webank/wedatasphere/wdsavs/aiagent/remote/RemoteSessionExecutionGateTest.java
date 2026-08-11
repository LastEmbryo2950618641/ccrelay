package com.webank.wedatasphere.wdsavs.aiagent.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteSessionExecutionGateTest {

    private final RemoteSessionExecutionGate gate = new RemoteSessionExecutionGate(4);

    @AfterEach
    void tearDown() {
        gate.shutdownNow();
    }

    @Test
    void serializesSameSessionAndReturnsBusyToSynchronousCalls() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<String> order = new CopyOnWriteArrayList<>();

        gate.submit("session-1", "task-1", () -> {
            order.add("first-start");
            firstStarted.countDown();
            await(releaseFirst);
            order.add("first-end");
            completed.countDown();
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        gate.submit("session-1", "task-2", () -> {
            order.add("second");
            completed.countDown();
        });
        RemoteSessionExecutionGate.ImmediateResult<String> immediate =
                gate.tryExecute("session-1", () -> "unexpected");
        assertTrue(immediate.isBusy());

        releaseFirst.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("first-start", "first-end", "second"), order);
    }

    @Test
    void executesDifferentSessionsInParallel() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);

        gate.submit("session-a", "task-a", () -> runConcurrent(bothStarted, release, completed));
        gate.submit("session-b", "task-b", () -> runConcurrent(bothStarted, release, completed));

        assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
    }

    private void runConcurrent(CountDownLatch started, CountDownLatch release, CountDownLatch completed) {
        started.countDown();
        await(release);
        completed.countDown();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
