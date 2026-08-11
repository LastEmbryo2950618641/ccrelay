package com.webank.wedatasphere.wdsavs.aiagent.model;

public class RelayGrantValidateResponse {
    private Boolean valid;
    private String status;
    private String message;

    public RelayGrantValidateResponse() {
    }

    public RelayGrantValidateResponse(Boolean valid, String status, String message) {
        this.valid = valid;
        this.status = status;
        this.message = message;
    }

    public Boolean getValid() {
        return valid;
    }

    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
