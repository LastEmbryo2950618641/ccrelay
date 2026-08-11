package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiSessionContextDeltaRequest {

    private String sessionId;
    private long afterCursor;
    private int limit = 200;
    private RelayGrantValidateRequest grant;
}
