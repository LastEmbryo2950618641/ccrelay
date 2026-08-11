package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.SshClusterIdentityPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SshClusterIdentityPolicyRepository extends JpaRepository<SshClusterIdentityPolicyEntity, String> {
}
