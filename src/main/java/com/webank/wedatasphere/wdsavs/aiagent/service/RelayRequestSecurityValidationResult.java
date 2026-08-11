package com.webank.wedatasphere.wdsavs.aiagent.service;

public class RelayRequestSecurityValidationResult {

    private final boolean valid;
    private final String status;
    private final String message;

    public RelayRequestSecurityValidationResult(boolean valid, String status, String message) {
        this.valid = valid;
        this.status = status;
        this.message = message;
    }

    public boolean isValid() {
        return valid;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
