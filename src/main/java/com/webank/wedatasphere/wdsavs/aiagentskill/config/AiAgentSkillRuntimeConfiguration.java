package com.webank.wedatasphere.wdsavs.aiagentskill.config;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aAgentService;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aPayloadPolicyService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiAuditService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiCapabilityCatalogService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayGrantService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayRegistryService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionCollaborationService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionMessageQueueService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AiAgentSkillRuntimeConfiguration {

    @Bean
    public RestTemplate aiSkillRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    public AiRelayService aiSkillLocalRelayService() {
        return new AiRelayService() {
            @Override
            public AiChatResponse chat(AiChatRequest request) {
                throw new UnsupportedOperationException("Local CC dialog is disabled in ai-agent skill runtime; use remote relay endpoint.");
            }
        };
    }

    @Bean
    public A2aAgentService aiSkillA2aAgentService(AiRelayService aiSkillLocalRelayService,
                                                  AiCapabilityCatalogService capabilityCatalogService,
                                                  AiRelayGrantService relayGrantService,
                                                  AiRelayRegistryService relayRegistryService,
                                                  AiAuditService auditService,
                                                  RestTemplate aiSkillRestTemplate,
                                                  A2aPayloadPolicyService payloadPolicyService,
                                                  AiSessionCollaborationService sessionCollaborationService,
                                                  AiSessionMessageQueueService sessionMessageQueueService) {
        A2aAgentService service = new A2aAgentService(aiSkillLocalRelayService, capabilityCatalogService, relayGrantService,
                relayRegistryService, auditService, aiSkillRestTemplate, payloadPolicyService);
        service.setSessionCollaborationService(sessionCollaborationService);
        service.setSessionMessageQueueService(sessionMessageQueueService);
        return service;
    }
}
