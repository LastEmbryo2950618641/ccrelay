package com.webank.wedatasphere.wdsavs.aiagentskill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EntityScan(basePackages = "com.webank.wedatasphere.wdsavs.aiagent.entity")
@EnableJpaRepositories(basePackages = "com.webank.wedatasphere.wdsavs.aiagent.repository")
@ComponentScan(basePackages = {
        "com.webank.wedatasphere.wdsavs.aiagent.service",
        "com.webank.wedatasphere.wdsavs.aiagentskill"
})
public class AiAgentSkillRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentSkillRuntimeApplication.class, args);
    }
}
