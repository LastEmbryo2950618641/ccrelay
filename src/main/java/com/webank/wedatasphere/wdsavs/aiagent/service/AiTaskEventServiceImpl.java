package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskEventRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AiTaskEventServiceImpl implements AiTaskEventService {

    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long KEEPALIVE_INTERVAL_MS = 15000L;
    private static final long MAX_STREAM_DURATION_MS = 10 * 60 * 1000L;

    private final AiTaskEventRepository taskEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiTaskEventServiceImpl(AiTaskEventRepository taskEventRepository) {
        this.taskEventRepository = taskEventRepository;
    }

    @Override
    public String appendEvent(String taskId, String sessionId, String eventType, Long sequenceNo, Map<String, Object> payload) {
        String now = String.valueOf(System.currentTimeMillis());
        AiTaskEventEntity entity = new AiTaskEventEntity();
        entity.setEventId(UUID.randomUUID().toString());
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setEventType(eventType);
        entity.setSequenceNo(sequenceNo == null ? 0L : sequenceNo);
        entity.setPayloadJson(writeJson(payload));
        entity.setCreatedTime(now);
        entity.setCreatedTimeMs(System.currentTimeMillis());
        taskEventRepository.save(entity);
        return entity.getEventId();
    }

    @Override
    public List<AiTaskEventView> listEvents(String taskId) {
        return taskEventRepository.findByTaskIdOrderBySequenceNoAsc(taskId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Override
    public List<AiTaskEventView> listEvents(String taskId, Long sinceSequenceNo, Long sinceCreatedTimeMs, Integer limit) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return List.of();
        }
        List<AiTaskEventEntity> entities;
        boolean hasSequence = sinceSequenceNo != null && sinceSequenceNo > 0L;
        boolean hasTime = sinceCreatedTimeMs != null && sinceCreatedTimeMs > 0L;
        var pageable = limit == null || limit <= 0 ? null : PageRequest.of(0, limit);
        if (hasSequence && hasTime) {
            entities = pageable == null
                    ? taskEventRepository.findByTaskIdAndSequenceNoGreaterThanEqualAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(
                            taskId, sinceSequenceNo, sinceCreatedTimeMs)
                    : taskEventRepository.findByTaskIdAndSequenceNoGreaterThanEqualAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(
                            taskId, sinceSequenceNo, sinceCreatedTimeMs, pageable);
        } else if (hasSequence) {
            entities = pageable == null
                    ? taskEventRepository.findByTaskIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(taskId, sinceSequenceNo)
                    : taskEventRepository.findByTaskIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(taskId, sinceSequenceNo, pageable);
        } else if (hasTime) {
            entities = pageable == null
                    ? taskEventRepository.findByTaskIdAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(taskId, sinceCreatedTimeMs)
                    : taskEventRepository.findByTaskIdAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(taskId, sinceCreatedTimeMs, pageable);
        } else {
            entities = pageable == null
                    ? taskEventRepository.findByTaskIdOrderBySequenceNoAsc(taskId)
                    : taskEventRepository.findByTaskIdOrderBySequenceNoAsc(taskId, pageable);
        }
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Override
    public ResponseEntity<StreamingResponseBody> streamEvents(String taskId) {
        StreamingResponseBody body = outputStream -> {
            long lastSequence = 0L;
            long startAt = System.currentTimeMillis();
            long lastKeepAliveAt = 0L;
            while (System.currentTimeMillis() - startAt < MAX_STREAM_DURATION_MS) {
                boolean wroteEvent = false;
                AiTaskEventView terminalEvent = null;
                for (AiTaskEventView event : listEvents(taskId)) {
                    long sequenceNo = event.getSequenceNo() == null ? 0L : event.getSequenceNo();
                    if (sequenceNo <= lastSequence) {
                        continue;
                    }
                    writeEvent(outputStream, "message", event);
                    wroteEvent = true;
                    lastSequence = sequenceNo;
                    if (isTerminal(event)) {
                        terminalEvent = event;
                    }
                }
                if (wroteEvent) {
                    outputStream.flush();
                }
                if (terminalEvent != null) {
                    break;
                }
                long now = System.currentTimeMillis();
                if (!wroteEvent && now - lastKeepAliveAt >= KEEPALIVE_INTERVAL_MS) {
                    outputStream.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    lastKeepAliveAt = now;
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            outputStream.write(": stream-end\n\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private boolean isTerminal(AiTaskEventView event) {
        if (event == null || event.getPayload() == null) {
            return false;
        }
        Object status = event.getPayload().get("status");
        if (status == null) {
            status = event.getPayload().get("a2aStatus");
        }
        if (status == null) {
            status = event.getPayload().get("latestA2aStatus");
        }
        if (status == null) {
            return false;
        }
        String value = String.valueOf(status).toUpperCase();
        return "SUCCESS".equals(value) || "FAILED".equals(value) || "CANCELLED".equals(value) || "TIMEOUT".equals(value);
    }

    private void writeEvent(java.io.OutputStream outputStream, String eventName, AiTaskEventView event) throws java.io.IOException {
        outputStream.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(("data: " + objectMapper.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private AiTaskEventView toView(AiTaskEventEntity entity) {
        AiTaskEventView view = new AiTaskEventView();
        view.setEventId(entity.getEventId());
        view.setTaskId(entity.getTaskId());
        view.setSessionId(entity.getSessionId());
        view.setEventType(entity.getEventType());
        view.setSequenceNo(entity.getSequenceNo());
        view.setCreatedTime(entity.getCreatedTime());
        Map<String, Object> payload = readJson(entity.getPayloadJson());
        view.setPayload(payload);
        view.setRequestId(stringValue(payload.get("requestId")));
        view.setTraceId(stringValue(payload.get("traceId")));
        view.setAuditId(stringValue(payload.get("auditId")));
        view.setAgentRunId(stringValue(payload.get("agentRunId")));
        return view;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize task event payload", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}

