package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.util.List;

final class PromptSnapshot {

    private static final String EMPTY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String revision;
    private final String unifiedDigest;
    private final String preDigest;
    private final String postDigest;
    private final List<String> unified;
    private final List<String> pre;
    private final List<String> post;

    PromptSnapshot(String revision,
                   String unifiedDigest,
                   String preDigest,
                   String postDigest,
                   List<String> unified,
                   List<String> pre,
                   List<String> post) {
        this.revision = revision;
        this.unifiedDigest = unifiedDigest;
        this.preDigest = preDigest;
        this.postDigest = postDigest;
        this.unified = List.copyOf(unified);
        this.pre = List.copyOf(pre);
        this.post = List.copyOf(post);
    }

    static PromptSnapshot empty() {
        return new PromptSnapshot(EMPTY_DIGEST, EMPTY_DIGEST, EMPTY_DIGEST, EMPTY_DIGEST,
                List.of(), List.of(), List.of());
    }

    String getRevision() {
        return revision;
    }

    String getUnifiedDigest() {
        return unifiedDigest;
    }

    String getPreDigest() {
        return preDigest;
    }

    String getPostDigest() {
        return postDigest;
    }

    List<String> getUnified() {
        return unified;
    }

    List<String> getPre() {
        return pre;
    }

    List<String> getPost() {
        return post;
    }
}
