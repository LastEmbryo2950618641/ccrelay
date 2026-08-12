package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationBatchView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import com.webank.wedatasphere.wdsavs.aiagent.service.TaskObservationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillNodeObservationControllerTest {

    @Test
    void listsSessionsWithTheirTargetNodes() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        TaskObservationService observationService = mock(TaskObservationService.class);
        AiSessionEntity session = session("session-1", "OPEN", "20");
        when(sessionRepository.findAll()).thenReturn(List.of(session));
        when(taskRepository.findAll()).thenReturn(List.of(
                task("task-1", "session-1", "node-a:18192", "10"),
                task("task-2", "session-1", "node-b:18192", "11"),
                task("task-3", "session-1", "node-a:18192", "12")));
        SkillNodeObservationController controller = new SkillNodeObservationController(
                taskRepository, sessionRepository, observationService);

        List<Map<String, Object>> sessions = controller.listSessions(50);

        assertEquals(1, sessions.size());
        assertEquals("session-1", sessions.get(0).get("sessionId"));
        assertEquals("服务状态检查", sessions.get(0).get("title"));
        assertEquals(3, sessions.get(0).get("taskCount"));
        assertEquals(List.of("node-a:18192", "node-b:18192"), sessions.get(0).get("nodeIds"));
    }

    @Test
    void observesOnlyTasksFromSelectedSessionAndNode() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        TaskObservationService observationService = mock(TaskObservationService.class);
        when(taskRepository.findAll()).thenReturn(List.of(
                task("task-old", "session-1", "node-a:18192", "10"),
                task("task-latest", "session-1", "node-a:18192", "20"),
                task("task-other-node", "session-1", "node-b:18192", "30"),
                task("task-other-session", "session-2", "node-a:18192", "40")));
        TaskObservationBatchView expected = new TaskObservationBatchView();
        when(observationService.observeBatch(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        SkillNodeObservationController controller = new SkillNodeObservationController(
                taskRepository, sessionRepository, observationService);

        TaskObservationBatchView actual = controller.observeSessionNode(
                "session-1", "node-a:18192", 80, 60, 32768L);

        assertEquals(expected, actual);
        ArgumentCaptor<TaskObservationQuery> query = ArgumentCaptor.forClass(TaskObservationQuery.class);
        verify(observationService).observeBatch(query.capture());
        assertEquals(List.of("task-latest", "task-old"), query.getValue().getTaskIds());
        assertEquals("node-a:18192", query.getValue().getTargetNodeId());
        assertEquals(80, query.getValue().getLimit());
        assertEquals(60, query.getValue().getTailLines());
        assertEquals(32768L, query.getValue().getMaxBytes());
    }

    private AiSessionEntity session(String sessionId, String status, String updateTime) {
        AiSessionEntity entity = new AiSessionEntity();
        entity.setSessionId(sessionId);
        entity.setStatus(status);
        entity.setTitle("服务状态检查");
        entity.setUpdateTime(updateTime);
        return entity;
    }

    private AiTaskEntity task(String taskId, String sessionId, String nodeId, String createTime) {
        AiTaskEntity entity = new AiTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setTargetNodeId(nodeId);
        entity.setCreateTime(createTime);
        return entity;
    }
}
