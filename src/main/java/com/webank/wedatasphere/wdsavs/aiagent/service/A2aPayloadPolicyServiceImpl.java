package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
@Service
public class A2aPayloadPolicyServiceImpl implements A2aPayloadPolicyService {

    private static final long DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 5L * 1024L * 1024L;
    private static final String DEFAULT_ALLOWED_REFERENCE_PATHS = "*";
    private static final String ALLOWED_REFERENCE_PATHS_PROPERTY = "wdsavs.ai.a2a.allowed-reference-paths";
    private static final String ALLOWED_REFERENCE_PATHS_ENV = "WDSAVS_AI_A2A_ALLOWED_REFERENCE_PATHS";
    private static final String ALLOWED_WORK_ROOTS_PROPERTY = "wdsavs.ai.a2a.allowed-work-roots";
    private static final String ALLOWED_WORK_ROOTS_ENV = "WDSAVS_AI_A2A_ALLOWED_WORK_ROOTS";
    private static final String ALLOWED_LOG_ROOTS_PROPERTY = "wdsavs.ai.a2a.allowed-log-roots";
    private static final String ALLOWED_LOG_ROOTS_ENV = "WDSAVS_AI_A2A_ALLOWED_LOG_ROOTS";
    private static final String ALLOWED_CODE_ROOTS_PROPERTY = "wdsavs.ai.a2a.allowed-code-roots";
    private static final String ALLOWED_CODE_ROOTS_ENV = "WDSAVS_AI_A2A_ALLOWED_CODE_ROOTS";

    private final long largeFileThresholdBytes;
    private final List<String> allowedReferencePaths;
    private final boolean legacyAllowedReferencePathsConfigured;
    private final List<String> allowedWorkRoots;
    private final List<String> allowedLogRoots;
    private final List<String> allowedCodeRoots;

    public A2aPayloadPolicyServiceImpl() {
        this(resolveThreshold(),
                resolveAllowedReferencePaths(),
                hasConfiguredValue(ALLOWED_REFERENCE_PATHS_PROPERTY, ALLOWED_REFERENCE_PATHS_ENV),
                resolveAllowedRoots(ALLOWED_WORK_ROOTS_PROPERTY, ALLOWED_WORK_ROOTS_ENV),
                resolveAllowedRoots(ALLOWED_LOG_ROOTS_PROPERTY, ALLOWED_LOG_ROOTS_ENV),
                resolveAllowedRoots(ALLOWED_CODE_ROOTS_PROPERTY, ALLOWED_CODE_ROOTS_ENV));
    }

    A2aPayloadPolicyServiceImpl(long largeFileThresholdBytes) {
        this(largeFileThresholdBytes, List.of(DEFAULT_ALLOWED_REFERENCE_PATHS));
    }

    public A2aPayloadPolicyServiceImpl(long largeFileThresholdBytes, List<String> allowedReferencePaths) {
        this(largeFileThresholdBytes,
                allowedReferencePaths,
                hasConfiguredPaths(allowedReferencePaths),
                List.of(DEFAULT_ALLOWED_REFERENCE_PATHS),
                List.of(DEFAULT_ALLOWED_REFERENCE_PATHS),
                List.of(DEFAULT_ALLOWED_REFERENCE_PATHS));
    }

    public A2aPayloadPolicyServiceImpl(long largeFileThresholdBytes,
                                List<String> allowedReferencePaths,
                                List<String> allowedWorkRoots,
                                List<String> allowedLogRoots,
                                List<String> allowedCodeRoots) {
        this(largeFileThresholdBytes,
                allowedReferencePaths,
                hasConfiguredPaths(allowedReferencePaths),
                allowedWorkRoots,
                allowedLogRoots,
                allowedCodeRoots);
    }

    private A2aPayloadPolicyServiceImpl(long largeFileThresholdBytes,
                                        List<String> allowedReferencePaths,
                                        boolean legacyAllowedReferencePathsConfigured,
                                        List<String> allowedWorkRoots,
                                        List<String> allowedLogRoots,
                                        List<String> allowedCodeRoots) {
        this.largeFileThresholdBytes = largeFileThresholdBytes <= 0 ? DEFAULT_LARGE_FILE_THRESHOLD_BYTES : largeFileThresholdBytes;
        this.allowedReferencePaths = normalizeAllowedPaths(allowedReferencePaths);
        this.legacyAllowedReferencePathsConfigured = legacyAllowedReferencePathsConfigured;
        this.allowedWorkRoots = normalizeAllowedPaths(allowedWorkRoots);
        this.allowedLogRoots = normalizeAllowedPaths(allowedLogRoots);
        this.allowedCodeRoots = normalizeAllowedPaths(allowedCodeRoots);
    }

    @Override
    public void validateMessageParams(Map<String, Object> params) {
        validate(params, "A2A message/send");
    }

    @Override
    public void validateTaskParams(Map<String, Object> params) {
        validate(params, "A2A tasks/create");
    }

    @Override
    public AiChatResponse normalizeChatResponse(AiChatResponse response) {
        if (response == null) {
            return null;
        }
        AiChatResponse normalized = new AiChatResponse();
        normalized.setAnswer(redactText(response.getAnswer()));
        normalized.setStatus(response.getStatus());
        normalized.setTraceId(response.getTraceId());
        normalized.setSummary(redactText(response.getSummary()));
        normalized.setArtifacts(redactList(normalizeAttachments(response.getArtifacts(), "A2A response", "artifacts")));
        normalized.setDiagnostics(redactMap(copyMap(response.getDiagnostics())));
        normalized.setMetadata(redactMap(copyMap(response.getMetadata())));
        return normalized;
    }

    @Override
    public Map<String, Object> normalizeStructuredResult(Map<String, Object> result) {
        Map<String, Object> normalized = copyMap(result);
        normalizeAttachmentsField(normalized, "attachments", "A2A response");
        normalizeAttachmentsField(normalized, "files", "A2A response");
        normalizeAttachmentsField(normalized, "artifacts", "A2A response");
        return redactMap(normalized);
    }

    private Map<String, Object> redactMap(Map<String, Object> source) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : (source == null ? Map.<String, Object>of() : source).entrySet()) {
            if (isSensitiveKey(entry.getKey())) {
                redacted.put(entry.getKey(), "[REDACTED]");
            } else {
                redacted.put(entry.getKey(), redactValue(entry.getValue()));
            }
        }
        return redacted;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> redactList(List<Map<String, Object>> source) {
        List<Map<String, Object>> redacted = new ArrayList<>();
        for (Map<String, Object> item : source == null ? List.<Map<String, Object>>of() : source) {
            Object value = redactValue(item);
            if (value instanceof Map<?, ?> map) {
                redacted.add((Map<String, Object>) map);
            }
        }
        return redacted;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return redactMap(toMap(map));
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>();
            for (Object item : list) {
                redacted.add(redactValue(item));
            }
            return redacted;
        }
        if (value instanceof String text) {
            return redactText(text);
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        return lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("authorization")
                || lower.contains("signedtoken")
                || lower.contains("signed_token")
                || lower.contains("secret")
                || lower.endsWith("token");
    }

    private String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replaceAll("(?i)(?<![A-Za-z0-9])sk-[A-Za-z0-9_\\-]{8,}", "sk-[REDACTED]")
                .replaceAll("(?i)(apiKey|api_key|authorization|signedToken|signed_token|secret|token)(\\s*[:=]\\s*)([^\\s,;|}]+)", "$1$2[REDACTED]")
                .replaceAll("(?i)\\b(apiKey|api_key|authorization|signedToken|signed_token|secret|token)\\b(\\s+)([A-Za-z0-9._\\-+/=]{6,})", "$1$2[REDACTED]");
    }

    private void validate(Map<String, Object> params, String operation) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        validateAttachmentList(safeParams.get("attachments"), operation, "attachments");
        validateAttachmentList(safeParams.get("files"), operation, "files");
        validateAttachmentList(safeParams.get("artifacts"), operation, "artifacts");
    }

    private void normalizeAttachmentsField(Map<String, Object> target, String fieldName, String operation) {
        Object value = target.get(fieldName);
        if (!(value instanceof List<?> list)) {
            return;
        }
        target.put(fieldName, normalizeAttachments(list, operation, fieldName));
    }

    private List<Map<String, Object>> normalizeAttachments(List<?> items, String operation, String fieldName) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (items == null) {
            return normalized;
        }
        for (Object item : items) {
            Map<String, Object> attachment = toMap(item);
            if (attachment.isEmpty()) {
                continue;
            }
            normalized.add(normalizeAttachment(attachment, operation, fieldName));
        }
        return normalized;
    }

    private Map<String, Object> normalizeAttachment(Map<String, Object> attachment, String operation, String fieldName) {
        Map<String, Object> normalized = new LinkedHashMap<>(attachment);
        validateReferenceBoundary(normalized, operation, fieldName);
        long sizeBytes = resolveSizeBytes(normalized);
        if (sizeBytes > 0) {
            normalized.put("sizeBytes", sizeBytes);
        }
        if (sizeBytes > largeFileThresholdBytes && hasInlineContent(normalized)) {
            String digest = inlineContentDigest(normalized);
            normalized.remove("content");
            normalized.remove("contentBase64");
            normalized.remove("inlineContent");
            normalized.remove("bytes");
            normalized.put("inlineContentOmitted", true);
            normalized.put("referenceOnly", hasReference(normalized));
            if (digest != null) {
                normalized.put("sha256", digest);
            }
        }
        return normalized;
    }

    private void validateAttachmentList(Object value, String operation, String fieldName) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            Map<String, Object> attachment = toMap(item);
            if (attachment.isEmpty()) {
                continue;
            }
            long sizeBytes = resolveSizeBytes(attachment);
            boolean hasInlineContent = hasInlineContent(attachment);
            boolean hasReference = hasReference(attachment);
            if (sizeBytes > largeFileThresholdBytes && hasInlineContent) {
                throw new IllegalArgumentException(operation + " does not allow inline large file content in " + fieldName
                        + "; use fileRef/reference when payload exceeds " + largeFileThresholdBytes + " bytes");
            }
            if (sizeBytes > largeFileThresholdBytes && !hasReference) {
                throw new IllegalArgumentException(operation + " requires fileRef/reference for large file in " + fieldName
                        + "; threshold=" + largeFileThresholdBytes + " bytes");
            }
            validateReferenceBoundary(attachment, operation, fieldName);
        }
    }

    private void validateReferenceBoundary(Map<String, Object> attachment, String operation, String fieldName) {
        List<String> allowedPaths = allowedPathsForReference(attachment);
        if (isWildcardAllowed(allowedPaths)) {
            return;
        }
        String referencePath = extractReferencePath(attachment);
        if (referencePath == null || referencePath.trim().isEmpty()) {
            return;
        }
        String normalized = normalizePath(referencePath);
        for (String allowedPath : allowedPaths) {
            if ("*".equals(allowedPath) || normalized.startsWith(normalizePath(allowedPath))) {
                return;
            }
        }
        throw new IllegalArgumentException(operation + " references path outside allowed boundaries in " + fieldName + ": " + referencePath);
    }

    private List<String> allowedPathsForReference(Map<String, Object> attachment) {
        String category = referenceCategory(attachment);
        if ("work".equals(category)) {
            return allowedWorkRoots;
        }
        if ("log".equals(category)) {
            return allowedLogRoots;
        }
        if ("code".equals(category)) {
            return allowedCodeRoots;
        }
        if (legacyAllowedReferencePathsConfigured) {
            return allowedReferencePaths;
        }
        return mergeAllowedPaths(allowedWorkRoots, allowedLogRoots, allowedCodeRoots);
    }

    private String referenceCategory(Map<String, Object> attachment) {
        Object fileRef = attachment.get("fileRef");
        if (fileRef instanceof Map<?, ?> map) {
            String category = normalizeCategory(map.get("category"));
            if (category != null) {
                return category;
            }
            category = normalizeCategory(map.get("type"));
            if (category != null) {
                return category;
            }
            category = normalizeCategory(map.get("kind"));
            if (category != null) {
                return category;
            }
        }
        String category = normalizeCategory(attachment.get("category"));
        if (category != null) {
            return category;
        }
        category = normalizeCategory(attachment.get("referenceType"));
        if (category != null) {
            return category;
        }
        category = normalizeCategory(attachment.get("artifactType"));
        if (category != null) {
            return category;
        }
        return normalizeCategory(attachment.get("type"));
    }

    private String normalizeCategory(Object value) {
        if (!hasText(value)) {
            return null;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if (text.contains("log")) {
            return "log";
        }
        if (text.contains("code") || text.contains("source")) {
            return "code";
        }
        if (text.contains("work") || text.contains("workspace")) {
            return "work";
        }
        return null;
    }

    private List<String> mergeAllowedPaths(List<String>... pathGroups) {
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> pathGroup : pathGroups) {
            if (pathGroup == null || pathGroup.isEmpty()) {
                continue;
            }
            if (isWildcardAllowed(pathGroup)) {
                return List.of(DEFAULT_ALLOWED_REFERENCE_PATHS);
            }
            merged.addAll(pathGroup);
        }
        return merged.isEmpty() ? List.of(DEFAULT_ALLOWED_REFERENCE_PATHS) : new ArrayList<>(merged);
    }

    private boolean hasInlineContent(Map<String, Object> attachment) {
        return hasText(attachment.get("content"))
                || hasText(attachment.get("contentBase64"))
                || hasText(attachment.get("inlineContent"))
                || attachment.get("bytes") != null;
    }

    private boolean hasReference(Map<String, Object> attachment) {
        Object fileRef = attachment.get("fileRef");
        if (fileRef instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return hasText(fileRef)
                || hasText(attachment.get("reference"))
                || hasText(attachment.get("uri"))
                || hasText(attachment.get("path"));
    }

    private String extractReferencePath(Map<String, Object> attachment) {
        Object fileRef = attachment.get("fileRef");
        if (fileRef instanceof Map<?, ?> map) {
            Object path = map.get("path");
            if (hasText(path)) {
                return String.valueOf(path);
            }
            Object uri = map.get("uri");
            if (hasText(uri)) {
                return String.valueOf(uri);
            }
        }
        if (hasText(attachment.get("path"))) {
            return String.valueOf(attachment.get("path"));
        }
        if (hasText(attachment.get("uri"))) {
            return String.valueOf(attachment.get("uri"));
        }
        if (hasText(attachment.get("reference"))) {
            return String.valueOf(attachment.get("reference"));
        }
        if (hasText(fileRef)) {
            return String.valueOf(fileRef);
        }
        return null;
    }

    private long resolveSizeBytes(Map<String, Object> attachment) {
        Long declared = longValue(attachment.get("sizeBytes"));
        if (declared != null && declared > 0) {
            return declared;
        }
        Long alternative = longValue(attachment.get("size"));
        if (alternative != null && alternative > 0) {
            return alternative;
        }
        Object content = attachment.get("content");
        if (content != null) {
            return String.valueOf(content).getBytes(StandardCharsets.UTF_8).length;
        }
        Object contentBase64 = attachment.get("contentBase64");
        if (contentBase64 != null) {
            return String.valueOf(contentBase64).getBytes(StandardCharsets.UTF_8).length;
        }
        Object inlineContent = attachment.get("inlineContent");
        if (inlineContent != null) {
            return String.valueOf(inlineContent).getBytes(StandardCharsets.UTF_8).length;
        }
        Object bytes = attachment.get("bytes");
        if (bytes instanceof byte[] array) {
            return array.length;
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> target = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    target.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return target;
        }
        return Map.of();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private String inlineContentDigest(Map<String, Object> attachment) {
        byte[] bytes = inlineContentBytes(attachment);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] inlineContentBytes(Map<String, Object> attachment) {
        Object content = attachment.get("content");
        if (content != null) {
            return String.valueOf(content).getBytes(StandardCharsets.UTF_8);
        }
        Object contentBase64 = attachment.get("contentBase64");
        if (contentBase64 != null) {
            return String.valueOf(contentBase64).getBytes(StandardCharsets.UTF_8);
        }
        Object inlineContent = attachment.get("inlineContent");
        if (inlineContent != null) {
            return String.valueOf(inlineContent).getBytes(StandardCharsets.UTF_8);
        }
        Object bytes = attachment.get("bytes");
        if (bytes instanceof byte[] array) {
            return array;
        }
        return null;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWildcardAllowed(List<String> allowedPaths) {
        return allowedPaths.size() == 1 && "*".equals(allowedPaths.get(0));
    }

    private List<String> normalizeAllowedPaths(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of(DEFAULT_ALLOWED_REFERENCE_PATHS);
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return result.isEmpty() ? List.of(DEFAULT_ALLOWED_REFERENCE_PATHS) : result;
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').trim().toLowerCase();
    }

    private static long resolveThreshold() {
        String property = System.getProperty("wdsavs.ai.a2a.large-file-threshold-bytes");
        if (property != null && !property.trim().isEmpty()) {
            return Long.parseLong(property.trim());
        }
        String environment = System.getenv("WDSAVS_AI_A2A_LARGE_FILE_THRESHOLD_BYTES");
        if (environment != null && !environment.trim().isEmpty()) {
            return Long.parseLong(environment.trim());
        }
        return DEFAULT_LARGE_FILE_THRESHOLD_BYTES;
    }

    private static List<String> resolveAllowedReferencePaths() {
        return resolveAllowedRoots(ALLOWED_REFERENCE_PATHS_PROPERTY, ALLOWED_REFERENCE_PATHS_ENV);
    }

    private static List<String> resolveAllowedRoots(String propertyKey, String environmentKey) {
        String property = System.getProperty(propertyKey);
        if (property != null && !property.trim().isEmpty()) {
            return splitPaths(property);
        }
        String environment = System.getenv(environmentKey);
        if (environment != null && !environment.trim().isEmpty()) {
            return splitPaths(environment);
        }
        return List.of(DEFAULT_ALLOWED_REFERENCE_PATHS);
    }

    private static boolean hasConfiguredValue(String propertyKey, String environmentKey) {
        String property = System.getProperty(propertyKey);
        if (property != null && !property.trim().isEmpty()) {
            return true;
        }
        String environment = System.getenv(environmentKey);
        return environment != null && !environment.trim().isEmpty();
    }

    private static boolean hasConfiguredPaths(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitPaths(String value) {
        String[] parts = value.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result.isEmpty() ? List.of(DEFAULT_ALLOWED_REFERENCE_PATHS) : result;
    }
}




