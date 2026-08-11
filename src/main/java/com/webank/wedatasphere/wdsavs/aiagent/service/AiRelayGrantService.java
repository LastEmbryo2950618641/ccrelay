package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRenewRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRevokeRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;

public interface AiRelayGrantService {

    RelayAccessDecisionResponse requestAccess(RelayAccessRequest request);

    RelayGrantView renewGrant(RelayGrantRenewRequest request);

    Boolean revokeGrant(RelayGrantRevokeRequest request);

    RelayGrantView getGrant(String grantId);

    RelayAccessDecisionResponse activateGrantIfReady(String grantId);

    RelayGrantValidateResponse validateGrant(RelayGrantValidateRequest request);
}
