package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskLifecycleServiceImplTimeoutTest {

    @Test
    void shouldNotTimeoutTaskWaitingForUserInput() {
        AiTaskRepository repository = mock(AiTaskRepository.class);
        AiTaskLifecycleServiceImpl service = service(repository);
        AiTaskEntity task = expiredTask("WAITING_USER_INPUT", "SSH_CREDENTIAL_REQUIRED");
        when(repository.findByTaskId(task.getTaskId())).thenReturn(Optional.of(task));

        assertEquals("WAITING_USER_INPUT", service.getTaskStatus(task.getTaskId()).getStatus());
        assertEquals("SSH_CREDENTIAL_REQUIRED", task.getCurrentStage());
        verify(repository, never()).save(any(AiTaskEntity.class));
    }

    @Test
    void shouldStillTimeoutRunningTask() {
        AiTaskRepository repository = mock(AiTaskRepository.class);
        AiTaskLifecycleServiceImpl service = service(repository);
        AiTaskEntity task = expiredTask("RUNNING", "EXECUTING");
        when(repository.findByTaskId(task.getTaskId())).thenReturn(Optional.of(task));

        assertEquals("TIMEOUT", service.getTaskStatus(task.getTaskId()).getStatus());
        assertEquals("TASK_TIMEOUT", task.getErrorCode());
        verify(repository).save(task);
    }

    private AiTaskLifecycleServiceImpl service(AiTaskRepository repository) {
        return new AiTaskLifecycleServiceImpl(
                repository,
                mock(AiSessionService.class),
                mock(AiTaskEventService.class),
                mock(AiRelayDeployService.class));
    }

    private AiTaskEntity expiredTask(String status, String stage) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setStatus(status);
        task.setCurrentStage(stage);
        task.setTimeoutMs(1L);
        task.setCreateTime(String.valueOf(System.currentTimeMillis() - 10_000L));
        return task;
    }
}
