package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

public interface AiTaskEventService {

    String appendEvent(String taskId, String sessionId, String eventType, Long sequenceNo, Map<String, Object> payload);

    List<AiTaskEventView> listEvents(String taskId);

    List<AiTaskEventView> listEvents(String taskId, Long sinceSequenceNo, Long sinceCreatedTimeMs, Integer limit);

    ResponseEntity<StreamingResponseBody> streamEvents(String taskId);
}
