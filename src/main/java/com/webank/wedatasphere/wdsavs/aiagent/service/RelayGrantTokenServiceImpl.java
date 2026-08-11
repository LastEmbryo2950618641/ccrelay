package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RelayGrantTokenServiceImpl implements RelayGrantTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final RuntimeConfigService runtimeConfigService;

    public RelayGrantTokenServiceImpl() {
        this(null);
    }

    @Autowired
    public RelayGrantTokenServiceImpl(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @Override
    public String sign(String grantId, String sessionId, String sourceNodeId, String targetNodeId,
                       List<String> allowedCapabilities, String expiresAt) {
        String payload = canonicalPayload(grantId, sessionId, sourceNodeId, targetNodeId, allowedCapabilities, expiresAt);
        String signature = hmac(payload, resolveSecret());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;
    }

    @Override
    public boolean validate(String token, String grantId, String sessionId, String sourceNodeId,
                            String targetNodeId, List<String> allowedCapabilities, String expiresAt) {
        if (token == null || !token.contains(".")) {
            return false;
        }
        String[] parts = token.split("\\.", 2);
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] payloadParts = payload.split("\\|", -1);
        if (payloadParts.length != 6) {
            return false;
        }
        String expectedSignature = hmac(payload, resolveSecret());
        if (!expectedSignature.equals(parts[1])) {
            return false;
        }
        if (!stringValue(grantId).equals(payloadParts[0])) {
            return false;
        }
        if (!stringValue(sessionId).equals(payloadParts[1])) {
            return false;
        }
        if (!stringValue(sourceNodeId).equals(payloadParts[2])) {
            return false;
        }
        if (!stringValue(targetNodeId).equals(payloadParts[3])) {
            return false;
        }
        if (!stringValue(expiresAt).equals(payloadParts[5])) {
            return false;
        }
        return isCapabilitySubset(payloadParts[4], allowedCapabilities);
    }

    private boolean isCapabilitySubset(String grantedCapabilitiesPayload, List<String> requestedCapabilities) {
        Set<String> granted = normalizeCapabilities(parseCapabilities(grantedCapabilitiesPayload));
        Set<String> requested = normalizeCapabilities(requestedCapabilities);
        return granted.containsAll(requested);
    }

    private List<String> parseCapabilities(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return List.of();
        }
        String[] parts = payload.split(",");
        List<String> capabilities = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                capabilities.add(part.trim());
            }
        }
        return capabilities;
    }

    private Set<String> normalizeCapabilities(List<String> capabilities) {
        Set<String> normalized = new LinkedHashSet<>();
        if (capabilities == null) {
            return normalized;
        }
        for (String capability : capabilities) {
            if (capability != null && !capability.trim().isEmpty()) {
                normalized.add(capability.trim().toUpperCase());
            }
        }
        return normalized;
    }

    private String canonicalPayload(String grantId, String sessionId, String sourceNodeId, String targetNodeId,
                                    List<String> allowedCapabilities, String expiresAt) {
        String capabilities = allowedCapabilities == null ? "" : String.join(",", allowedCapabilities);
        return String.join("|",
                stringValue(grantId),
                stringValue(sessionId),
                stringValue(sourceNodeId),
                stringValue(targetNodeId),
                capabilities,
                stringValue(expiresAt));
    }

    private String hmac(String payload, String secretValue) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretValue.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign relay grant token", e);
        }
    }

    private String resolveSecret() {
        return RelayHmacSecretResolver.resolve(runtimeConfigService);
    }

    private String stringValue(String value) {
        return value == null ? "" : value;
    }
}
