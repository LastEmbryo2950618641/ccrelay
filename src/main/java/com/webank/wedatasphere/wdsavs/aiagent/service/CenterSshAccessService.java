package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPreflightRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPreflightResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPublicKeyView;

public interface CenterSshAccessService {

    CenterSshPublicKeyView getPublicKey();

    CenterSshPreflightResponse preflight(CenterSshPreflightRequest request);
}
