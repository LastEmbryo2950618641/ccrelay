package com.webank.wedatasphere.wdsavs.aiagent.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;

public final class PromptCatalogDigestSupport {

    private static final Comparator<PromptCatalogEntry> ORDER = Comparator
            .comparing(PromptCatalogEntry::getType)
            .thenComparing(PromptCatalogEntry::getOrder)
            .thenComparing(PromptCatalogEntry::getPromptId);

    private PromptCatalogDigestSupport() {
    }

    public static String calculate(List<PromptCatalogEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (entries != null) {
                entries.stream().filter(java.util.Objects::nonNull).sorted(ORDER)
                        .forEach(entry -> update(digest, entry));
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Prompt catalog SHA-256", e);
        }
    }

    private static void update(MessageDigest digest, PromptCatalogEntry entry) {
        byte[] record = (entry.getPromptId() + "\n" + entry.getType().name() + "\n"
                + entry.getOrder() + "\n" + entry.getSha256() + "\n" + entry.getStatus() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(record.length).array());
        digest.update(record);
    }
}
