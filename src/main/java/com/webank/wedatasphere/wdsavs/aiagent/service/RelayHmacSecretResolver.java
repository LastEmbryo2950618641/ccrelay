package com.webank.wedatasphere.wdsavs.aiagent.service;

import java.security.SecureRandom;
import java.util.Base64;

final class RelayHmacSecretResolver {

    private static final String ENV_SECRET = "WDSAVS_AI_HMAC_SECRET";
    private static final String SYSTEM_SECRET = "wdsavs.ai.hmac.secret";
    private static final String CONFIG_KEY = "wdsavs.ai.relay.hmac-secret";
    private static final String PROCESS_FALLBACK_SECRET = generateProcessFallbackSecret();

    private RelayHmacSecretResolver() {
    }

    static String resolve(RuntimeConfigService runtimeConfigService) {
        String value = trimToNull(System.getenv(ENV_SECRET));
        if (value != null) {
            return value;
        }
        value = trimToNull(System.getProperty(SYSTEM_SECRET));
        if (value != null) {
            return value;
        }
        if (runtimeConfigService != null) {
            value = trimToNull(runtimeConfigService.getString(CONFIG_KEY, null));
            if (value != null) {
                return value;
            }
        }
        return PROCESS_FALLBACK_SECRET;
    }

    private static String generateProcessFallbackSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
