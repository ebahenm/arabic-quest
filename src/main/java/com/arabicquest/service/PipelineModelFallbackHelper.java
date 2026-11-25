// Handles per-job Claude and Gemini availability flags and automatic cross-provider fallback across all pipeline passes.
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.service.ClaudeClient.ClaudeMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PipelineModelFallbackHelper {

    private static final Logger log = LoggerFactory.getLogger(PipelineModelFallbackHelper.class);

    private PipelineModelFallbackHelper() {}

    public static boolean isClaudeFallbackError(Throwable ex) {
        if (!(ex instanceof Exception) || ex instanceof AiUsageGuard.AiCostCapExceededException) {
            return false;
        }
        if (ex instanceof TooManyRequestsException) {
            return true;
        }
        if (ex instanceof AiProviderClientException apce) {
            int code = apce.getStatusCode();
            if (code == 400 || code == 402 || code == 403 || code == 429 || code >= 500) {
                return true;
            }
        }
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
        return msg.contains("http 400") || msg.contains("http 402") || msg.contains("http 403")
                || msg.contains("http 429") || msg.contains("http 5")
                || msg.contains("400") || msg.contains("402") || msg.contains("403")
                || msg.contains("429") || msg.contains("500") || msg.contains("502")
                || msg.contains("503") || msg.contains("504") || msg.contains("529")
                || msg.contains("credit") || msg.contains("balance") || msg.contains("billing")
                || msg.contains("quota") || msg.contains("turned off") || msg.contains("rate limit")
                || msg.contains("overloaded") || msg.contains("timeout");
    }

    public static ClaudeMessageResponse callModelDetailed(
            ClaudeClient claudeClient,
            GeminiTextClient geminiTextClient,
            boolean preferClaude,
            AtomicBoolean geminiUnavailable,
            AtomicBoolean claudeUnavailable,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            int geminiOnlyMaxAttempts,
            long backoffBaseMs) {
        AtomicBoolean gemUnavail = geminiUnavailable != null ? geminiUnavailable : new AtomicBoolean(false);
        AtomicBoolean cldUnavail = claudeUnavailable != null ? claudeUnavailable : new AtomicBoolean(false);

        if (cldUnavail.get() && gemUnavail.get()) {
            throw new IllegalStateException("Both Claude and Gemini are unavailable for this job.");
        }

        boolean claudeConfigured = claudeClient != null && claudeClient.isConfigured();
        boolean geminiConfigured = geminiTextClient != null && geminiTextClient.isConfigured();
        boolean claudeAvailable = claudeConfigured && !cldUnavail.get();
        boolean geminiAvailable = geminiConfigured && !gemUnavail.get();

        boolean tryClaudeFirst = claudeAvailable && (preferClaude || !geminiAvailable);

        if (tryClaudeFirst) {
            try {
                return claudeClient.callMessagesDetailed(systemPrompt, userPrompt, maxTokens);
            } catch (Exception claudeEx) {
                if (!isClaudeFallbackError(claudeEx)) {
                    throw asRuntime(claudeEx);
                }
                cldUnavail.set(true);
                String claudeMsg = claudeEx.getMessage() != null ? claudeEx.getMessage() : claudeEx.getClass().getSimpleName();
                if (gemUnavail.get()) {
                    throw buildCombinedException(claudeMsg, "Gemini was already marked unavailable earlier in this job", claudeEx);
                }
                if (!geminiConfigured) {
                    throw asRuntime(claudeEx);
                }
                log.warn("Claude call failed ({}); marking Claude unavailable for job and falling back to Gemini ({}).",
                        claudeMsg, geminiTextClient.getModel());
                try {
                    return new ClaudeMessageResponse(geminiTextClient.callTextJson(systemPrompt, userPrompt), "STOP", 0, 0);
                } catch (Exception geminiEx) {
                    gemUnavail.set(true);
                    String geminiMsg = geminiEx.getMessage() != null ? geminiEx.getMessage() : geminiEx.getClass().getSimpleName();
                    throw buildCombinedException(claudeMsg, geminiMsg, geminiEx);
                }
            }
        }

        if (!geminiAvailable) {
            if (cldUnavail.get() && gemUnavail.get()) {
                throw new IllegalStateException("Both Claude and Gemini are unavailable for this job.");
            }
            throw new IllegalStateException("No AI model is configured or available for this job.");
        }

        int maxAttempts = claudeConfigured ? 1 : Math.max(1, geminiOnlyMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return new ClaudeMessageResponse(geminiTextClient.callTextJson(systemPrompt, userPrompt), "STOP", 0, 0);
            } catch (Exception geminiEx) {
                if (geminiEx instanceof AiUsageGuard.AiCostCapExceededException) {
                    throw asRuntime(geminiEx);
                }
                String geminiMsg = geminiEx.getMessage() != null ? geminiEx.getMessage() : geminiEx.getClass().getSimpleName();
                if (claudeConfigured && !cldUnavail.get()) {
                    gemUnavail.set(true);
                    log.warn("Gemini call failed ({}); marking Gemini unavailable for job and switching to Claude.", geminiMsg);
                    try {
                        return claudeClient.callMessagesDetailed(systemPrompt, userPrompt, maxTokens);
                    } catch (Exception claudeEx) {
                        cldUnavail.set(true);
                        String claudeMsg = claudeEx.getMessage() != null ? claudeEx.getMessage() : claudeEx.getClass().getSimpleName();
                        throw buildCombinedException(claudeMsg, geminiMsg, claudeEx);
                    }
                }
                if (isGeminiRetryableError(geminiEx, geminiMsg) && attempt < maxAttempts) {
                    sleepQuietly(backoffBaseMs * attempt);
                    continue;
                }
                gemUnavail.set(true);
                if (cldUnavail.get()) {
                    throw buildCombinedException("Claude was already marked unavailable earlier in this job", geminiMsg, geminiEx);
                }
                throw asRuntime(geminiEx);
            }
        }
        throw new IllegalStateException("AI model call failed.");
    }

    private static boolean isGeminiRetryableError(Exception ex, String msg) {
        return (ex instanceof TooManyRequestsException)
                || msg.contains("429") || msg.contains("500") || msg.contains("502")
                || msg.contains("503") || msg.contains("HTTP 5");
    }

    private static IllegalStateException buildCombinedException(String claudeMsg, String geminiMsg, Throwable cause) {
        return new IllegalStateException(
                "Both Claude and Gemini are unavailable for this job. Claude: " + claudeMsg + " | Gemini: " + geminiMsg,
                cause);
    }

    private static RuntimeException asRuntime(Exception ex) {
        return (ex instanceof RuntimeException re) ? re : new IllegalStateException(ex);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
