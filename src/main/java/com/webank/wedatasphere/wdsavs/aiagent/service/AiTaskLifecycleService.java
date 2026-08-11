package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCancelRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskStatusView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;

public interface AiTaskLifecycleService {

    AiTaskCreateResponse createTask(AiTaskCreateRequest request);

    AiTaskView getTask(String taskId);

    AiTaskStatusView getTaskStatus(String taskId);

    Boolean cancelTask(String taskId, AiTaskCancelRequest request);

    int recoverInterruptedTasks();
}
