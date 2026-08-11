package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayNodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AiRelayRegistryServiceImpl implements AiRelayRegistryService {

    private final AiRelayNodeRepository relayNodeRepository;
    private final AiAuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AiRelayRegistryServiceImpl(AiRelayNodeRepository relayNodeRepository,
                                      AiAuditService auditService) {
        this.relayNodeRepository = relayNodeRepository;
        this.auditService = auditService;
    }

    AiRelayRegistryServiceImpl(AiRelayNodeRepository relayNodeRepository) {
        this(relayNodeRepository, null);
    }

    @Override
    public RelayRegisterResponse register(RelayRegisterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("RelayRegisterRequest is required");
        }
        String confirmedNodeId = confirmNodeId(request);
        String now = String.valueOf(System.currentTimeMillis());
        AiRelayNodeEntity entity = relayNodeRepository.findByNodeId(confirmedNodeId).orElseGet(AiRelayNodeEntity::new);
        entity.setNodeId(confirmedNodeId);
        entity.setHost(request.getHost());
        entity.setPort(request.getPort());
        entity.setRelayEndpoint(request.getRelayEndpoint());
        entity.setVersion(request.getVersion());
        entity.setProtocolVersion(request.getProtocolVersion());
        entity.setStatus(entity.getStatus() == null ? "REGISTERING" : entity.getStatus());
        entity.setCapabilitiesJson(writeJsonList(request.getCapabilities()));
        entity.setWorkspaceRoot(request.getWorkspaceRoot());
        entity.setEnvironmentSummaryJson(writeJsonMap(request.getEnvironmentSummary()));
        entity.setRegisterTime(entity.getRegisterTime() == null ? now : entity.getRegisterTime());
        entity.setCreateTime(entity.getCreateTime() == null ? now : entity.getCreateTime());
        entity.setUpdateTime(now);
        relayNodeRepository.save(entity);

        String auditId = recordRegisterAudit(entity, request);
        return new RelayRegisterResponse(auditId, entity.getNodeId(), entity.getStatus(), true, entity.getRegisterTime());
    }

    @Override
    public RelayNodeView getNode(String nodeId) {
        return toView(relayNodeRepository.findByNodeId(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Relay node not found: " + nodeId)));
    }

    @Override
    public List<RelayNodeView> listNodes() {
        return relayNodeRepository.findAll().stream().map(this::toView).collect(Collectors.toList());
    }

    private String recordRegisterAudit(AiRelayNodeEntity entity, RelayRegisterRequest request) {
        if (auditService == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("host", request.getHost());
        detail.put("port", request.getPort());
        detail.put("relayEndpoint", request.getRelayEndpoint());
        detail.put("protocolVersion", request.getProtocolVersion());
        detail.put("version", request.getVersion());
        detail.put("workspaceRoot", request.getWorkspaceRoot());
        detail.put("capabilities", request.getCapabilities() == null ? List.of() : request.getCapabilities());
        detail.put("environmentSummary", request.getEnvironmentSummary() == null ? Map.of() : request.getEnvironmentSummary());
        return auditService.record(null, null, entity.getNodeId(), null,
                AuditEventType.NODE_REGISTERED, entity.getStatus(), detail, "NODE", entity.getNodeId());
    }

    private String confirmNodeId(RelayRegisterRequest request) {
        String host = normalize(request.getHost());
        Integer port = request.getPort();
        if (host != null && port != null && port > 0) {
            return host + ":" + port;
        }
        String nodeId = normalize(request.getNodeId());
        if (nodeId != null) {
            return nodeId;
        }
        throw new IllegalArgumentException("nodeId or host+port is required");
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private RelayNodeView toView(AiRelayNodeEntity entity) {
        RelayNodeView view = new RelayNodeView();
        view.setNodeId(entity.getNodeId());
        view.setHost(entity.getHost());
        view.setPort(entity.getPort());
        view.setRelayEndpoint(entity.getRelayEndpoint());
        view.setVersion(entity.getVersion());
        view.setProtocolVersion(entity.getProtocolVersion());
        view.setStatus(entity.getStatus());
        view.setCapabilities(readJsonList(entity.getCapabilitiesJson()));
        view.setWorkspaceRoot(entity.getWorkspaceRoot());
        view.setEnvironmentSummary(readJsonMap(entity.getEnvironmentSummaryJson()));
        view.setLastHeartbeatTime(entity.getLastHeartbeatTime());
        view.setRegisterTime(entity.getRegisterTime());
        return view;
    }

    private String writeJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize capabilities", e);
        }
    }

    private String writeJsonMap(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize environment summary", e);
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> readJsonMap(String json) {
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
