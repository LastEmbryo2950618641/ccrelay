package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextDeltaRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayRequestSecurityService;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RemoteSessionContextSynchronizer {

    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 10;
    static final String MODEL_SESSION_STARTED_METADATA = "modelSessionStarted";

    private final RestTemplate restTemplate;
    private final RelayRequestSecurityService requestSecurityService;
    private final RemoteSessionContextStateStore stateStore;

    RemoteSessionContextSynchronizer(RestTemplate restTemplate,
                                     RelayRequestSecurityService requestSecurityService,
                                     RemoteSessionContextStateStore stateStore) {
        this.restTemplate = restTemplate;
        this.requestSecurityService = requestSecurityService;
        this.stateStore = stateStore;
    }

    SyncState synchronize(AiChatRequest request, String localNodeId) {
        if (request == null || request.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = mapValue(request.getMetadata());
        String sessionId = stringValue(metadata.get("sessionId"));
        String endpoint = stringValue(metadata.get("centerContextDeltaEndpoint"));
        RelayGrantValidateRequest grant = relayGrant(metadata);
        if (isBlank(sessionId) || isBlank(endpoint) || grant == null) {
            return null;
        }

        long appliedCursor = stateStore.cursor(sessionId);
        long expectedHead = longValue(metadata.get("contextHeadCursor"));
        List<AiChatMessage> contextMessages = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<?, ?> response = fetchDelta(endpoint, sessionId, appliedCursor, grant);
            if (response == null) {
                break;
            }
            Object eventsValue = response.get("events");
            if (eventsValue instanceof List<?> events) {
                for (Object eventValue : events) {
                    Map<String, Object> event = mapValue(eventValue);
                    appendMessage(contextMessages, event, sessionId, localNodeId);
                    appliedCursor = Math.max(appliedCursor, longValue(event.get("cursor")));
                }
            }
            expectedHead = Math.max(expectedHead, longValue(response.get("headCursor")));
            boolean hasEvents = eventsValue instanceof List<?> events && !events.isEmpty();
            if (appliedCursor >= expectedHead || !hasEvents) {
                break;
            }
        }

        metadata.put("modelSessionId", stateStore.modelSessionId(sessionId, localNodeId));
        metadata.put("resumeModelSession", stateStore.modelSessionStarted(sessionId));
        if (!contextMessages.isEmpty()) {
            request.setMessages(contextMessages);
        }
        request.setMetadata(metadata);
        return new SyncState(sessionId, appliedCursor);
    }

    void recordExecution(SyncState syncState, AiChatResponse response) {
        if (syncState == null || response == null) {
            return;
        }
        Map<String, Object> responseMetadata = response.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getMetadata());
        if (Boolean.parseBoolean(String.valueOf(responseMetadata.remove(MODEL_SESSION_STARTED_METADATA)))) {
            stateStore.markModelSessionStarted(syncState.sessionId());
        }
        response.setMetadata(responseMetadata);
        if ("SUCCESS".equalsIgnoreCase(response.getStatus())) {
            stateStore.markApplied(syncState.sessionId(), syncState.cursor());
        }
    }

    private Map<?, ?> fetchDelta(String endpoint, String sessionId, long afterCursor,
                                 RelayGrantValidateRequest grant) {
        AiSessionContextDeltaRequest request = new AiSessionContextDeltaRequest();
        request.setSessionId(sessionId);
        request.setAfterCursor(afterCursor);
        request.setLimit(PAGE_LIMIT);
        request.setGrant(requestSecurityService.sign(grant));
        return restTemplate.postForObject(endpoint, request, Map.class);
    }

    private void appendMessage(List<AiChatMessage> messages, Map<String, Object> event,
                               String sessionId, String localNodeId) {
        String role = firstNonBlank(stringValue(event.get("role")), "user");
        boolean alreadyInLocalModelSession = stateStore.initialized(sessionId)
                && "assistant".equalsIgnoreCase(role)
                && !isBlank(localNodeId)
                && localNodeId.equals(stringValue(event.get("senderId")));
        if (alreadyInLocalModelSession) {
            return;
        }
        String content = stringValue(event.get("content"));
        if (!isBlank(content)) {
            messages.add(contextMessage(role, content, event));
            return;
        }
        String contentRef = stringValue(event.get("contentRef"));
        if (!isBlank(contentRef)) {
            messages.add(contextMessage(role, "Context reference: " + contentRef, event));
        }
    }

    private AiChatMessage contextMessage(String role, String content, Map<String, Object> event) {
        AiChatMessage message = new AiChatMessage(role, content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyIfPresent(metadata, event, "eventId");
        copyIfPresent(metadata, event, "taskId");
        copyIfPresent(metadata, event, "senderType");
        copyIfPresent(metadata, event, "senderId");
        copyIfPresent(metadata, event, "cursor");
        copyIfPresent(metadata, event, "createdTime");
        copyIfPresent(metadata, event, "contentType");
        copyIfPresent(metadata, event, "contentRef");
        List<String> targetNodeIds = targetNodeIds(event.get("targetNodeId"));
        if (!targetNodeIds.isEmpty()) {
            metadata.put("targetNodeIds", targetNodeIds);
        }
        message.setMetadata(metadata);
        return message;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private List<String> targetNodeIds(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                addTargetNodeIds(result, item);
            }
        } else {
            addTargetNodeIds(result, value);
        }
        return result;
    }

    private void addTargetNodeIds(List<String> targetNodeIds, Object value) {
        if (value == null) {
            return;
        }
        for (String item : String.valueOf(value).split(",")) {
            if (!item.isBlank()) {
                targetNodeIds.add(item.trim());
            }
        }
    }

    private RelayGrantValidateRequest relayGrant(Map<String, Object> metadata) {
        Map<String, Object> relayGrant = mapValue(metadata.get("relayGrant"));
        if (relayGrant.isEmpty()) {
            return null;
        }
        RelayGrantValidateRequest request = new RelayGrantValidateRequest();
        request.setGrantId(stringValue(relayGrant.get("grantId")));
        request.setSessionId(stringValue(relayGrant.get("sessionId")));
        request.setSourceNodeId(stringValue(relayGrant.get("sourceNodeId")));
        request.setTargetNodeId(stringValue(relayGrant.get("targetNodeId")));
        request.setSignedToken(stringValue(relayGrant.get("signedToken")));
        request.setExpiresAt(stringValue(relayGrant.get("expiresAt")));
        request.setAllowedCapabilities(stringList(relayGrant.get("allowedCapabilities")));
        return request;
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return result;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    record SyncState(String sessionId, long cursor) {
    }
}
