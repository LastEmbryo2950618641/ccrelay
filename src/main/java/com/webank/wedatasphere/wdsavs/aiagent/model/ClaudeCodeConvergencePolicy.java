package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class ClaudeCodeConvergencePolicy {

    private Integer maxRatSteps;
    private Integer maxRetries;
    private Integer maxNoProgressRounds;
    private Long maxDurationMs;
    private Integer repeatedActionThreshold;
    private Long retryDelayMs;
    private Integer maxPromptChars;
    private Integer maxResponseChars;
    private Long humanApprovalPauseTimeoutMs;
    private Boolean enableHumanApprovalPause;
    private Boolean enableRepeatActionDetection;

    public static ClaudeCodeConvergencePolicy defaults() {
        ClaudeCodeConvergencePolicy policy = new ClaudeCodeConvergencePolicy();
        policy.setMaxRatSteps(12);
        policy.setMaxRetries(3);
        policy.setMaxNoProgressRounds(3);
        policy.setMaxDurationMs(600000L);
        policy.setRepeatedActionThreshold(2);
        policy.setRetryDelayMs(1000L);
        policy.setMaxPromptChars(120000);
        policy.setMaxResponseChars(120000);
        policy.setHumanApprovalPauseTimeoutMs(300000L);
        policy.setEnableHumanApprovalPause(false);
        policy.setEnableRepeatActionDetection(true);
        return policy;
    }

    public static ClaudeCodeConvergencePolicy withDefaults(ClaudeCodeConvergencePolicy source) {
        return withDefaults(source, defaults());
    }

    public static ClaudeCodeConvergencePolicy withDefaults(ClaudeCodeConvergencePolicy source, ClaudeCodeConvergencePolicy defaults) {
        ClaudeCodeConvergencePolicy fallback = normalizeDefaults(defaults);
        if (source == null) {
            return copyOf(fallback);
        }
        ClaudeCodeConvergencePolicy policy = new ClaudeCodeConvergencePolicy();
        policy.setMaxRatSteps(source.getMaxRatSteps() == null ? fallback.getMaxRatSteps() : source.getMaxRatSteps());
        policy.setMaxRetries(source.getMaxRetries() == null ? fallback.getMaxRetries() : source.getMaxRetries());
        policy.setMaxNoProgressRounds(source.getMaxNoProgressRounds() == null ? fallback.getMaxNoProgressRounds() : source.getMaxNoProgressRounds());
        policy.setMaxDurationMs(source.getMaxDurationMs() == null ? fallback.getMaxDurationMs() : source.getMaxDurationMs());
        policy.setRepeatedActionThreshold(source.getRepeatedActionThreshold() == null ? fallback.getRepeatedActionThreshold() : source.getRepeatedActionThreshold());
        policy.setRetryDelayMs(source.getRetryDelayMs() == null ? fallback.getRetryDelayMs() : source.getRetryDelayMs());
        policy.setMaxPromptChars(source.getMaxPromptChars() == null ? fallback.getMaxPromptChars() : source.getMaxPromptChars());
        policy.setMaxResponseChars(source.getMaxResponseChars() == null ? fallback.getMaxResponseChars() : source.getMaxResponseChars());
        policy.setHumanApprovalPauseTimeoutMs(source.getHumanApprovalPauseTimeoutMs() == null ? fallback.getHumanApprovalPauseTimeoutMs() : source.getHumanApprovalPauseTimeoutMs());
        policy.setEnableHumanApprovalPause(source.getEnableHumanApprovalPause() == null ? fallback.getEnableHumanApprovalPause() : source.getEnableHumanApprovalPause());
        policy.setEnableRepeatActionDetection(source.getEnableRepeatActionDetection() == null ? fallback.getEnableRepeatActionDetection() : source.getEnableRepeatActionDetection());
        return policy;
    }

    private static ClaudeCodeConvergencePolicy normalizeDefaults(ClaudeCodeConvergencePolicy defaults) {
        if (defaults == null) {
            return defaults();
        }
        ClaudeCodeConvergencePolicy systemDefaults = defaults();
        return withDefaultsAgainst(defaults, systemDefaults);
    }

    private static ClaudeCodeConvergencePolicy withDefaultsAgainst(ClaudeCodeConvergencePolicy source, ClaudeCodeConvergencePolicy fallback) {
        ClaudeCodeConvergencePolicy policy = new ClaudeCodeConvergencePolicy();
        policy.setMaxRatSteps(source.getMaxRatSteps() == null ? fallback.getMaxRatSteps() : source.getMaxRatSteps());
        policy.setMaxRetries(source.getMaxRetries() == null ? fallback.getMaxRetries() : source.getMaxRetries());
        policy.setMaxNoProgressRounds(source.getMaxNoProgressRounds() == null ? fallback.getMaxNoProgressRounds() : source.getMaxNoProgressRounds());
        policy.setMaxDurationMs(source.getMaxDurationMs() == null ? fallback.getMaxDurationMs() : source.getMaxDurationMs());
        policy.setRepeatedActionThreshold(source.getRepeatedActionThreshold() == null ? fallback.getRepeatedActionThreshold() : source.getRepeatedActionThreshold());
        policy.setRetryDelayMs(source.getRetryDelayMs() == null ? fallback.getRetryDelayMs() : source.getRetryDelayMs());
        policy.setMaxPromptChars(source.getMaxPromptChars() == null ? fallback.getMaxPromptChars() : source.getMaxPromptChars());
        policy.setMaxResponseChars(source.getMaxResponseChars() == null ? fallback.getMaxResponseChars() : source.getMaxResponseChars());
        policy.setHumanApprovalPauseTimeoutMs(source.getHumanApprovalPauseTimeoutMs() == null ? fallback.getHumanApprovalPauseTimeoutMs() : source.getHumanApprovalPauseTimeoutMs());
        policy.setEnableHumanApprovalPause(source.getEnableHumanApprovalPause() == null ? fallback.getEnableHumanApprovalPause() : source.getEnableHumanApprovalPause());
        policy.setEnableRepeatActionDetection(source.getEnableRepeatActionDetection() == null ? fallback.getEnableRepeatActionDetection() : source.getEnableRepeatActionDetection());
        return policy;
    }

    private static ClaudeCodeConvergencePolicy copyOf(ClaudeCodeConvergencePolicy source) {
        ClaudeCodeConvergencePolicy policy = new ClaudeCodeConvergencePolicy();
        policy.setMaxRatSteps(source.getMaxRatSteps());
        policy.setMaxRetries(source.getMaxRetries());
        policy.setMaxNoProgressRounds(source.getMaxNoProgressRounds());
        policy.setMaxDurationMs(source.getMaxDurationMs());
        policy.setRepeatedActionThreshold(source.getRepeatedActionThreshold());
        policy.setRetryDelayMs(source.getRetryDelayMs());
        policy.setMaxPromptChars(source.getMaxPromptChars());
        policy.setMaxResponseChars(source.getMaxResponseChars());
        policy.setHumanApprovalPauseTimeoutMs(source.getHumanApprovalPauseTimeoutMs());
        policy.setEnableHumanApprovalPause(source.getEnableHumanApprovalPause());
        policy.setEnableRepeatActionDetection(source.getEnableRepeatActionDetection());
        return policy;
    }
}
