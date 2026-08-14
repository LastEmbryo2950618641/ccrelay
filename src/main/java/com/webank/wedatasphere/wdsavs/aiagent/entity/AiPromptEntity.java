package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Data
@Entity
@Table(name = "wdsavs_ai_prompt", uniqueConstraints = @UniqueConstraint(name = "uk_ai_prompt_id", columnNames = "prompt_id"))
public class AiPromptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prompt_id", nullable = false, length = 64)
    private String promptId;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "prompt_order", nullable = false)
    private Integer promptOrder;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "content_path", nullable = false, length = 1024)
    private String contentPath;

    @Column(name = "content_size", nullable = false)
    private Long contentSize;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
