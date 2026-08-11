package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

public interface A2aTaskService {

    A2aTaskCreateResponse createTask(A2aTaskCreateRequest request);

    Object getTask(String taskId, Map<String, Object> context);

    ResponseEntity<StreamingResponseBody> streamTaskEvents(String taskId, Map<String, Object> context);

    Object cancelTask(String taskId, Map<String, Object> context);
}
