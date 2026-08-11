package com.webank.wedatasphere.wdsavs.aiagent.model;

import java.util.ArrayList;
import java.util.List;

public class RelayGrantValidateRequest {
    private String grantId;
    private String sessionId;
    private String sourceNodeId;
    private String targetNodeId;
    private List<String> allowedCapabilities = new ArrayList<>();
    private String expiresAt;
    private String signedToken;
    private String requestTimestamp;
    private String requestNonce;
    private String requestSignature;

    public String getGrantId() {
        return grantId;
    }

    public void setGrantId(String grantId) {
        this.grantId = grantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(String targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public List<String> getAllowedCapabilities() {
        return allowedCapabilities;
    }

    public void setAllowedCapabilities(List<String> allowedCapabilities) {
        this.allowedCapabilities = allowedCapabilities == null ? new ArrayList<>() : allowedCapabilities;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getSignedToken() {
        return signedToken;
    }

    public void setSignedToken(String signedToken) {
        this.signedToken = signedToken;
    }

    public String getRequestTimestamp() {
        return requestTimestamp;
    }

    public void setRequestTimestamp(String requestTimestamp) {
        this.requestTimestamp = requestTimestamp;
    }

    public String getRequestNonce() {
        return requestNonce;
    }

    public void setRequestNonce(String requestNonce) {
        this.requestNonce = requestNonce;
    }

    public String getRequestSignature() {
        return requestSignature;
    }

    public void setRequestSignature(String requestSignature) {
        this.requestSignature = requestSignature;
    }
}
