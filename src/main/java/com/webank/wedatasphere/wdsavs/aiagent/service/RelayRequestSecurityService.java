package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;

public interface RelayRequestSecurityService {

    RelayGrantValidateRequest sign(RelayGrantValidateRequest request);

    RelayRequestSecurityValidationResult validate(RelayGrantValidateRequest request, boolean consumeNonce);
}
