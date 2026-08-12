package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionContextEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextEventView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionContextEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@Service
public class AiSessionContextServiceImpl implements AiSessionContextService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private final AiSessionService sessionService;
    private final AiSessionContextEventRepository contextEventRepository;

    @Value("${wdsavs.ai.session.context.max-content-chars:262144}")
    private int maxContentChars = 262144;

    public AiSessionContextServiceImpl(AiSessionService sessionService,
                                       AiSessionContextEventRepository contextEventRepository) {
        this.sessionService = sessionService;
        this.contextEventRepository = contextEventRepository;
    }

    @Override
    @Transactional
    public AiSessionContextEventView append(String sessionId, AiSessionContextAppendRequest request) {
        sessionService.validateSession(sessionId);
        if (request == null) {
            throw new IllegalArgumentException("Context event is required");
        }
        String eventId = firstNonBlank(request.getEventId(), UUID.randomUUID().toString());
        AiSessionContextEventEntity existing = contextEventRepository.findByEventId(eventId).orElse(null);
        if (existing != null) {
            if (!sessionId.equals(existing.getSessionId())) {
                throw new IllegalArgumentException("Context event belongs to another session: " + eventId);
            }
            return toView(existing);
        }
        String content = request.getContent();
        String contentRef = blankToNull(request.getContentRef());
        if (isBlank(content) && contentRef == null) {
            throw new IllegalArgumentException("Context event content or contentRef is required");
        }
        if (content != null && content.length() > Math.max(1, maxContentChars)) {
            throw new IllegalArgumentException("Context event content exceeds configured limit");
        }

        AiSessionContextEventEntity entity = new AiSessionContextEventEntity();
        entity.setEventId(eventId);
        entity.setSessionId(sessionId);
        entity.setTaskId(blankToNull(request.getTaskId()));
        entity.setSenderType(firstNonBlank(request.getSenderType(), "SYSTEM"));
        entity.setSenderId(blankToNull(request.getSenderId()));
        entity.setTargetNodeId(blankToNull(request.getTargetNodeId()));
        entity.setRole(firstNonBlank(request.getRole(), "user"));
        entity.setContent(blankToNull(content));
        entity.setContentType(firstNonBlank(request.getContentType(), "TEXT"));
        entity.setContentRef(contentRef);
        entity.setCreatedTime(String.valueOf(System.currentTimeMillis()));
        entity.setChecksum(checksum(entity));
        return toView(contextEventRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public long headCursor(String sessionId) {
        sessionService.validateSessionExists(sessionId);
        return contextEventRepository.findTopBySessionIdOrderByIdDesc(sessionId)
                .map(AiSessionContextEventEntity::getId)
                .orElse(0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiSessionContextEventView> delta(String sessionId, long afterCursor, int limit) {
        sessionService.validateSessionExists(sessionId);
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        long effectiveCursor = Math.max(afterCursor, 0L);
        return contextEventRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(
                        sessionId, effectiveCursor, PageRequest.of(0, effectiveLimit))
                .stream()
                .map(this::toView)
                .toList();
    }

    private AiSessionContextEventView toView(AiSessionContextEventEntity entity) {
        AiSessionContextEventView view = new AiSessionContextEventView();
        view.setCursor(entity.getId());
        view.setEventId(entity.getEventId());
        view.setSessionId(entity.getSessionId());
        view.setTaskId(entity.getTaskId());
        view.setSenderType(entity.getSenderType());
        view.setSenderId(entity.getSenderId());
        view.setTargetNodeId(entity.getTargetNodeId());
        view.setRole(entity.getRole());
        view.setContent(entity.getContent());
        view.setContentType(entity.getContentType());
        view.setContentRef(entity.getContentRef());
        view.setCreatedTime(entity.getCreatedTime());
        view.setChecksum(entity.getChecksum());
        return view;
    }

    private String checksum(AiSessionContextEventEntity entity) {
        String canonical = String.join("|", value(entity.getEventId()), value(entity.getSessionId()),
                value(entity.getTaskId()), value(entity.getSenderType()), value(entity.getSenderId()),
                value(entity.getTargetNodeId()), value(entity.getRole()), value(entity.getContent()),
                value(entity.getContentType()), value(entity.getContentRef()), value(entity.getCreatedTime()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to checksum context event", e);
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first.trim();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
