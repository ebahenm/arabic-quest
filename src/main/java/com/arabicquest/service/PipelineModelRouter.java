// Routes Pass 1/2/3 calls across Claude, Gemini, and optional DeepSeek with cross-model pairing and blind compare plans.
package com.arabicquest.service;

import com.arabicquest.service.ClaudeClient.ClaudeMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PipelineModelRouter {

    private static final Logger log = LoggerFactory.getLogger(PipelineModelRouter.class);
    public static final String CLAUDE = "claude";
    public static final String GEMINI = "gemini";
    public static final String DEEPSEEK = "deepseek";

    private PipelineModelRouter() {}

    public record ProviderPlan(
            String writerProvider,
            String verifierProvider,
            String writerModelName,
            String verifierModelName
    ) {}

    public static ProviderPlan resolveProviders(
            String requestedWriter,
            ClaudeClient claudeClient,
            GeminiTextClient geminiTextClient,
            DeepSeekClient deepSeekClient) {
        boolean claudeOn = claudeClient != null && claudeClient.isConfigured();
        boolean geminiOn = geminiTextClient != null && geminiTextClient.isConfigured();
        boolean deepSeekOn = deepSeekClient != null && deepSeekClient.isConfigured();

        String req = requestedWriter != null ? requestedWriter.trim().toLowerCase(Locale.ROOT) : "";
        String writer;
        if (req.contains(DEEPSEEK) && deepSeekOn) {
            writer = DEEPSEEK;
        } else if (req.contains(GEMINI) && geminiOn) {
            writer = GEMINI;
        } else if (req.contains(CLAUDE) && claudeOn) {
            writer = CLAUDE;
        } else if (claudeOn) {
            writer = CLAUDE;
        } else if (geminiOn) {
            writer = GEMINI;
        } else if (deepSeekOn) {
            writer = DEEPSEEK;
        } else {
            writer = GEMINI;
        }

        // Always pick a DIFFERENT verifier provider whenever 2+ providers are configured
        String verifier;
        if (DEEPSEEK.equals(writer)) {
            verifier = claudeOn ? CLAUDE : (geminiOn ? GEMINI : DEEPSEEK);
        } else if (GEMINI.equals(writer)) {
            verifier = claudeOn ? CLAUDE : (deepSeekOn ? DEEPSEEK : GEMINI);
        } else {
            verifier = geminiOn ? GEMINI : (deepSeekOn ? DEEPSEEK : CLAUDE);
        }

        return new ProviderPlan(
                writer,
                verifier,
                modelNameFor(writer, claudeClient, geminiTextClient, deepSeekClient),
                modelNameFor(verifier, claudeClient, geminiTextClient, deepSeekClient)
        );
    }

    public static ClaudeMessageResponse callProviderDetailed(
            String preferredProvider,
            ClaudeClient claudeClient,
            GeminiTextClient geminiTextClient,
            DeepSeekClient deepSeekClient,
            AtomicBoolean geminiUnavailable,
            AtomicBoolean claudeUnavailable,
            AtomicBoolean deepSeekUnavailable,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            int geminiOnlyMaxAttempts,
            long backoffBaseMs) {
        AtomicBoolean gemUnavail = geminiUnavailable != null ? geminiUnavailable : new AtomicBoolean(false);
        AtomicBoolean cldUnavail = claudeUnavailable != null ? claudeUnavailable : new AtomicBoolean(false);
        AtomicBoolean dsUnavail = deepSeekUnavailable != null ? deepSeekUnavailable : new AtomicBoolean(false);

        boolean dsConfigured = deepSeekClient != null && deepSeekClient.isConfigured();
        boolean dsAvailable = dsConfigured && !dsUnavail.get();

        if (DEEPSEEK.equals(preferredProvider) && dsAvailable) {
            try {
                return deepSeekClient.callChatDetailed(systemPrompt, userPrompt, maxTokens);
            } catch (Exception dsEx) {
                if (!PipelineModelFallbackHelper.isClaudeFallbackError(dsEx)) {
                    throw (dsEx instanceof RuntimeException re) ? re : new IllegalStateException(dsEx);
                }
                dsUnavail.set(true);
                log.warn("DeepSeek call failed ({}); marking DeepSeek unavailable for job and falling back.", dsEx.getMessage());
                boolean claudeOn = claudeClient != null && claudeClient.isConfigured() && !cldUnavail.get();
                boolean geminiOn = geminiTextClient != null && geminiTextClient.isConfigured() && !gemUnavail.get();
                if (!claudeOn && !geminiOn) {
                    throw (dsEx instanceof RuntimeException re) ? re : new IllegalStateException(dsEx);
                }
                return PipelineModelFallbackHelper.callModelDetailed(
                        claudeClient, geminiTextClient, claudeOn, gemUnavail, cldUnavail,
                        systemPrompt, userPrompt, maxTokens, geminiOnlyMaxAttempts, backoffBaseMs);
            }
        }

        boolean preferClaude = CLAUDE.equals(preferredProvider);
        try {
            return PipelineModelFallbackHelper.callModelDetailed(
                    claudeClient, geminiTextClient, preferClaude, gemUnavail, cldUnavail,
                    systemPrompt, userPrompt, maxTokens, geminiOnlyMaxAttempts, backoffBaseMs);
        } catch (RuntimeException primaryEx) {
            if (primaryEx instanceof AiUsageGuard.AiCostCapExceededException || !dsAvailable) {
                throw primaryEx;
            }
            log.warn("Primary providers failed ({}); falling back to DeepSeek ({}).",
                    primaryEx.getMessage(), deepSeekClient.getModel());
            try {
                return deepSeekClient.callChatDetailed(systemPrompt, userPrompt, maxTokens);
            } catch (Exception dsEx) {
                dsUnavail.set(true);
                throw primaryEx;
            }
        }
    }

    public static String resolveEffectiveModel(
            String plannedProvider,
            ClaudeClient claudeClient,
            GeminiTextClient geminiTextClient,
            DeepSeekClient deepSeekClient,
            AtomicBoolean geminiUnavailable,
            AtomicBoolean claudeUnavailable,
            AtomicBoolean deepSeekUnavailable) {
        if (DEEPSEEK.equals(plannedProvider) && !isTrue(deepSeekUnavailable)) {
            return modelNameFor(DEEPSEEK, claudeClient, geminiTextClient, deepSeekClient);
        }
        if (CLAUDE.equals(plannedProvider) && !isTrue(claudeUnavailable)) {
            return modelNameFor(CLAUDE, claudeClient, geminiTextClient, deepSeekClient);
        }
        if (GEMINI.equals(plannedProvider) && !isTrue(geminiUnavailable)) {
            return modelNameFor(GEMINI, claudeClient, geminiTextClient, deepSeekClient);
        }
        if (claudeClient != null && claudeClient.isConfigured() && !isTrue(claudeUnavailable)) {
            return claudeClient.getModel();
        }
        if (geminiTextClient != null && geminiTextClient.isConfigured() && !isTrue(geminiUnavailable)) {
            return geminiTextClient.getModel();
        }
        if (deepSeekClient != null && deepSeekClient.isConfigured() && !isTrue(deepSeekUnavailable)) {
            return deepSeekClient.getModel();
        }
        return modelNameFor(plannedProvider, claudeClient, geminiTextClient, deepSeekClient);
    }

    public static List<Map.Entry<String, String>> buildBlindComparePlan(
            DeepSeekClient deepSeekClient, SecureRandom random) {
        List<String> writers = new ArrayList<>(List.of(CLAUDE, GEMINI));
        if (deepSeekClient != null && deepSeekClient.isConfigured()) {
            writers.add(DEEPSEEK);
        }
        Collections.shuffle(writers, random);
        List<String> setTags = List.of("SET_A", "SET_B", "SET_C");
        List<Map.Entry<String, String>> plan = new ArrayList<>();
        for (int i = 0; i < writers.size(); i++) {
            plan.add(Map.entry(setTags.get(i), writers.get(i)));
        }
        return plan;
    }

    public static String modelNameFor(
            String provider, ClaudeClient claude, GeminiTextClient gemini, DeepSeekClient deepSeek) {
        if (DEEPSEEK.equals(provider) && deepSeek != null) return deepSeek.getModel();
        if (CLAUDE.equals(provider) && claude != null) return claude.getModel();
        return gemini != null ? gemini.getModel() : GeminiTextClient.DEFAULT_GEMINI_MODEL;
    }

    private static boolean isTrue(AtomicBoolean flag) {
        return flag != null && flag.get();
    }
}
