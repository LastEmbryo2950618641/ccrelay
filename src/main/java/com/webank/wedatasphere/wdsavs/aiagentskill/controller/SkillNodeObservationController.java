package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationBatchView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import com.webank.wedatasphere.wdsavs.aiagent.service.TaskObservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/skill/ui")
public class SkillNodeObservationController {

    private final AiTaskRepository taskRepository;
    private final AiSessionRepository sessionRepository;
    private final TaskObservationService observationService;

    public SkillNodeObservationController(AiTaskRepository taskRepository,
                                          AiSessionRepository sessionRepository,
                                          TaskObservationService observationService) {
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
        this.observationService = observationService;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions(
            @RequestParam(required = false, defaultValue = "50") Integer sessionLimit) {
        int boundedLimit = Math.max(1, Math.min(sessionLimit == null ? 50 : sessionLimit, 200));
        Map<String, List<AiTaskEntity>> tasksBySession = taskRepository.findAll().stream()
                .filter(task -> task.getSessionId() != null && !task.getSessionId().trim().isEmpty())
                .collect(Collectors.groupingBy(AiTaskEntity::getSessionId));
        return sessionRepository.findAll().stream()
                .sorted(Comparator.comparing(AiSessionEntity::getUpdateTime,
                        Comparator.nullsLast(String::compareTo)).reversed())
                .limit(boundedLimit)
                .map(session -> sessionView(session, tasksBySession.getOrDefault(session.getSessionId(), List.of())))
                .toList();
    }

    @GetMapping("/node-observations")
    public TaskObservationBatchView observeNode(
            @RequestParam String nodeId,
            @RequestParam(required = false, defaultValue = "30") Integer taskLimit,
            @RequestParam(required = false, defaultValue = "100") Integer eventLimit,
            @RequestParam(required = false, defaultValue = "100") Integer tailLines,
            @RequestParam(required = false, defaultValue = "65536") Long maxBytes) {
        String targetNodeId = nodeId == null ? "" : nodeId.trim();
        if (targetNodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId is required");
        }
        int boundedTaskLimit = Math.max(1, Math.min(taskLimit == null ? 30 : taskLimit, 100));
        return observeTasks(taskRepository.findAll().stream()
                .filter(task -> targetNodeId.equals(task.getTargetNodeId()))
                .sorted(Comparator.comparing(AiTaskEntity::getCreateTime,
                        Comparator.nullsLast(String::compareTo)).reversed())
                .limit(boundedTaskLimit)
                .toList(), targetNodeId, eventLimit, tailLines, maxBytes);
    }

    @GetMapping("/session-observations")
    public TaskObservationBatchView observeSessionNode(
            @RequestParam String sessionId,
            @RequestParam String nodeId,
            @RequestParam(required = false, defaultValue = "100") Integer eventLimit,
            @RequestParam(required = false, defaultValue = "100") Integer tailLines,
            @RequestParam(required = false, defaultValue = "65536") Long maxBytes) {
        String targetSessionId = sessionId == null ? "" : sessionId.trim();
        String targetNodeId = nodeId == null ? "" : nodeId.trim();
        if (targetSessionId.isEmpty() || targetNodeId.isEmpty()) {
            throw new IllegalArgumentException("sessionId and nodeId are required");
        }
        List<AiTaskEntity> tasks = taskRepository.findAll().stream()
                .filter(task -> targetSessionId.equals(task.getSessionId()))
                .filter(task -> targetNodeId.equals(task.getTargetNodeId()))
                .sorted(Comparator.comparing(AiTaskEntity::getCreateTime,
                        Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
        return observeTasks(tasks, targetNodeId, eventLimit, tailLines, maxBytes);
    }

    private TaskObservationBatchView observeTasks(List<AiTaskEntity> tasks,
                                                  String targetNodeId,
                                                  Integer eventLimit,
                                                  Integer tailLines,
                                                  Long maxBytes) {
        TaskObservationQuery query = new TaskObservationQuery();
        query.setTaskIds(tasks.stream()
                .map(AiTaskEntity::getTaskId)
                .filter(taskId -> taskId != null && !taskId.trim().isEmpty())
                .toList());
        query.setTargetNodeId(targetNodeId);
        query.setLimit(Math.max(1, Math.min(eventLimit == null ? 100 : eventLimit, 500)));
        query.setTailLines(Math.max(1, Math.min(tailLines == null ? 100 : tailLines, 1000)));
        query.setMaxBytes(Math.max(4096L, Math.min(maxBytes == null ? 65536L : maxBytes, 524288L)));
        return observationService.observeBatch(query);
    }

    private Map<String, Object> sessionView(AiSessionEntity session, List<AiTaskEntity> tasks) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", session.getSessionId());
        view.put("status", session.getStatus());
        view.put("sessionType", session.getSessionType());
        view.put("initiatorType", session.getInitiatorType());
        view.put("initiatorId", session.getInitiatorId());
        view.put("sourceNodeId", session.getSourceNodeId());
        view.put("createTime", session.getCreateTime());
        view.put("updateTime", session.getUpdateTime());
        view.put("taskCount", tasks.size());
        view.put("nodeIds", tasks.stream()
                .map(AiTaskEntity::getTargetNodeId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList());
        return view;
    }
}
