package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RelayRequestSecurityServiceImpl implements RelayRequestSecurityService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_ALLOWED_SKEW_MS = 5L * 60L * 1000L;
    private static final long DEFAULT_NONCE_TTL_MS = 10L * 60L * 1000L;

    private final RuntimeConfigService runtimeConfigService;
    private final long allowedSkewMs;
    private final long nonceTtlMs;
    private final ConcurrentMap<String, Long> usedNonces = new ConcurrentHashMap<>();

    public RelayRequestSecurityServiceImpl() {
        this(null, resolveLong("wdsavs.ai.relay.request.allowed-skew-ms", "WDSAVS_AI_RELAY_REQUEST_ALLOWED_SKEW_MS", DEFAULT_ALLOWED_SKEW_MS),
                resolveLong("wdsavs.ai.relay.request.nonce-ttl-ms", "WDSAVS_AI_RELAY_REQUEST_NONCE_TTL_MS", DEFAULT_NONCE_TTL_MS));
    }

    @Autowired
    public RelayRequestSecurityServiceImpl(RuntimeConfigService runtimeConfigService) {
        this(runtimeConfigService,
                resolveLong("wdsavs.ai.relay.request.allowed-skew-ms", "WDSAVS_AI_RELAY_REQUEST_ALLOWED_SKEW_MS", DEFAULT_ALLOWED_SKEW_MS),
                resolveLong("wdsavs.ai.relay.request.nonce-ttl-ms", "WDSAVS_AI_RELAY_REQUEST_NONCE_TTL_MS", DEFAULT_NONCE_TTL_MS));
    }

    RelayRequestSecurityServiceImpl(RuntimeConfigService runtimeConfigService, long allowedSkewMs, long nonceTtlMs) {
        this.runtimeConfigService = runtimeConfigService;
        this.allowedSkewMs = allowedSkewMs <= 0 ? DEFAULT_ALLOWED_SKEW_MS : allowedSkewMs;
        this.nonceTtlMs = nonceTtlMs <= 0 ? DEFAULT_NONCE_TTL_MS : nonceTtlMs;
    }

    @Override
    public RelayGrantValidateRequest sign(RelayGrantValidateRequest request) {
        if (request == null) {
            return null;
        }
        RelayGrantValidateRequest signed = copy(request);
        if (isBlank(signed.getRequestTimestamp())) {
            signed.setRequestTimestamp(String.valueOf(System.currentTimeMillis()));
        }
        if (isBlank(signed.getRequestNonce())) {
            signed.setRequestNonce(UUID.randomUUID().toString());
        }
        signed.setRequestSignature(hmac(canonicalPayload(signed), resolveSecret()));
        return signed;
    }

    @Override
    public RelayRequestSecurityValidationResult validate(RelayGrantValidateRequest request, boolean consumeNonce) {
        if (request == null) {
            return new RelayRequestSecurityValidationResult(false, "INVALID", "Relay request is required");
        }
        if (isBlank(request.getRequestTimestamp()) || isBlank(request.getRequestNonce()) || isBlank(request.getRequestSignature())) {
            return new RelayRequestSecurityValidationResult(false, "UNAUTHENTICATED", "Relay request signature metadata is missing");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(request.getRequestTimestamp());
        } catch (Exception e) {
            return new RelayRequestSecurityValidationResult(false, "INVALID", "Relay request timestamp is invalid");
        }
        long now = System.currentTimeMillis();
        purgeExpired(now);
        if (Math.abs(now - timestamp) > allowedSkewMs) {
            return new RelayRequestSecurityValidationResult(false, "EXPIRED", "Relay request timestamp is outside allowed window");
        }
        String expected = hmac(canonicalPayload(request), resolveSecret());
        if (!expected.equals(request.getRequestSignature())) {
            return new RelayRequestSecurityValidationResult(false, "INVALID", "Relay request signature validation failed");
        }
        if (consumeNonce) {
            String nonceKey = nonceKey(request);
            Long existing = usedNonces.putIfAbsent(nonceKey, now + nonceTtlMs);
            if (existing != null && existing >= now) {
                return new RelayRequestSecurityValidationResult(false, "REPLAYED", "Relay request nonce has already been used");
            }
        }
        return new RelayRequestSecurityValidationResult(true, "VALID", "Relay request signature is valid");
    }

    private void purgeExpired(long now) {
        usedNonces.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < now);
    }

    private String nonceKey(RelayGrantValidateRequest request) {
        return stringValue(request.getGrantId()) + "|" + stringValue(request.getRequestNonce()) + "|" + stringValue(request.getSourceNodeId());
    }

    private RelayGrantValidateRequest copy(RelayGrantValidateRequest source) {
        RelayGrantValidateRequest target = new RelayGrantValidateRequest();
        target.setGrantId(source.getGrantId());
        target.setSessionId(source.getSessionId());
        target.setSourceNodeId(source.getSourceNodeId());
        target.setTargetNodeId(source.getTargetNodeId());
        target.setAllowedCapabilities(source.getAllowedCapabilities() == null ? List.of() : new ArrayList<>(source.getAllowedCapabilities()));
        target.setExpiresAt(source.getExpiresAt());
        target.setSignedToken(source.getSignedToken());
        target.setRequestTimestamp(source.getRequestTimestamp());
        target.setRequestNonce(source.getRequestNonce());
        target.setRequestSignature(source.getRequestSignature());
        return target;
    }

    private String canonicalPayload(RelayGrantValidateRequest request) {
        String capabilities = request.getAllowedCapabilities() == null ? "" : String.join(",", request.getAllowedCapabilities());
        return String.join("|",
                stringValue(request.getGrantId()),
                stringValue(request.getSessionId()),
                stringValue(request.getSourceNodeId()),
                stringValue(request.getTargetNodeId()),
                capabilities,
                stringValue(request.getExpiresAt()),
                stringValue(request.getSignedToken()),
                stringValue(request.getRequestTimestamp()),
                stringValue(request.getRequestNonce()));
    }

    private String hmac(String payload, String secretValue) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretValue.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign relay request", e);
        }
    }

    private String resolveSecret() {
        return resolveSecret(runtimeConfigService);
    }

    private static String resolveSecret(RuntimeConfigService runtimeConfigService) {
        return RelayHmacSecretResolver.resolve(runtimeConfigService);
    }

    private static long resolveLong(String propertyKey, String envKey, long defaultValue) {
        String value = System.getProperty(propertyKey);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(envKey);
        }
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String stringValue(String value) {
        return value == null ? "" : value;
    }
}
