package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationBatchView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationView;
import com.webank.wedatasphere.wdsavs.aiagent.service.TaskObservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skill")
public class SkillObservationController {

    private final TaskObservationService observationService;

    public SkillObservationController(TaskObservationService observationService) {
        this.observationService = observationService;
    }

    @GetMapping({
            "/observations/tasks/{taskId}",
            "/tasks/{taskId}/observation",
            "/a2a/tasks/{taskId}/observation"
    })
    public TaskObservationView observeTask(@PathVariable String taskId, @ModelAttribute TaskObservationQuery query) {
        query.setTaskId(taskId);
        return observationService.observe(query);
    }

    @GetMapping({"/observations/tasks", "/tasks/observations"})
    public TaskObservationBatchView observeTasks(@ModelAttribute TaskObservationQuery query) {
        return observationService.observeBatch(query);
    }
}
