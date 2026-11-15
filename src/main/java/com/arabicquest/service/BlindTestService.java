// Executes Pass 3 blind testing (without transcript) in chunk batches across up to 3 runs with backoff and low-survival fallback.
package com.arabicquest.service;

import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BlindTestService {

    private static final Logger log = LoggerFactory.getLogger(BlindTestService.class);
    public static final int MIN_SURVIVAL_THRESHOLD = 20;

    private final ClaudeClient claudeClient;
    private final GeminiTextClient geminiTextClient;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.quiz.blind-test-runs:3}")
    private int blindTestRuns = 3;

    @Value("${app.quiz.blind-test-backoff-ms:50}")
    private long backoffBaseMs = 50L;

    public BlindTestService(
            ClaudeClient claudeClient,
            GeminiTextClient geminiTextClient,
            ObjectMapper objectMapper) {
        this.claudeClient = claudeClient;
        this.geminiTextClient = geminiTextClient;
        this.objectMapper = objectMapper;
    }

    public void setBlindTestRuns(int runs) {
        this.blindTestRuns = Math.max(1, Math.min(3, runs));
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = Math.max(0L, backoffBaseMs);
    }

    public record BlindTestedCandidate(
            CandidateMcq candidate,
            int chunkIndex,
            int correctGuesses,
            int wrongGuesses,
            boolean survivedBlindTest
    ) {}

    public record EpisodeBlindTestOutcome(
            List<BlindTestedCandidate> selectedQuestions,
            boolean needsReview,
            int naturalSurvivorsCount
    ) {}

    public List<BlindTestedCandidate> runBlindTestForChunk(
            List<CandidateMcq> chunkCandidates,
            int chunkIndex,
            boolean useClaudeAsBlindTester) {
        return runBlindTestForChunk(chunkCandidates, chunkIndex, useClaudeAsBlindTester, null, null);
    }

    public List<BlindTestedCandidate> runBlindTestForChunk(
            List<CandidateMcq> chunkCandidates,
            int chunkIndex,
            boolean useClaudeAsBlindTester,
            AtomicBoolean geminiUnavailableForJob) {
        return runBlindTestForChunk(chunkCandidates, chunkIndex, useClaudeAsBlindTester, geminiUnavailableForJob, null);
    }

    public List<BlindTestedCandidate> runBlindTestForChunk(
            List<CandidateMcq> chunkCandidates,
            int chunkIndex,
            boolean useClaudeAsBlindTester,
            AtomicBoolean geminiUnavailableForJob,
            AtomicBoolean claudeUnavailableForJob) {
        if (chunkCandidates == null || chunkCandidates.isEmpty()) {
            return List.of();
        }
        int n = chunkCandidates.size();
        int[] rightCounts = new int[n];
        int runs = Math.max(1, Math.min(3, blindTestRuns));
        AtomicBoolean effectiveGeminiUnavailable = geminiUnavailableForJob != null
                ? geminiUnavailableForJob
                : new AtomicBoolean(false);
        AtomicBoolean effectiveClaudeUnavailable = claudeUnavailableForJob != null
                ? claudeUnavailableForJob
                : new AtomicBoolean(false);

        for (int run = 0; run < runs; run++) {
            List<List<CandidateOption>> shuffledPerQuestion = new ArrayList<>(n);
            StringBuilder prompt = new StringBuilder();
            prompt.append("Answer the following multiple-choice questions using ONLY general knowledge (no transcript is provided). ");
            prompt.append("Return ONLY valid JSON: {\"answers\":[{\"questionIndex\":0,\"selectedOptionIndex\":0}]}\n\n");

            for (int qIdx = 0; qIdx < n; qIdx++) {
                CandidateMcq q = chunkCandidates.get(qIdx);
                List<CandidateOption> opts = new ArrayList<>(q.options());
                Collections.shuffle(opts, random);
                shuffledPerQuestion.add(opts);

                prompt.append("Q").append(qIdx).append(": ").append(q.questionText()).append("\n");
                for (int oIdx = 0; oIdx < opts.size(); oIdx++) {
                    prompt.append("  [").append(oIdx).append("] ").append(opts.get(oIdx).optionText()).append("\n");
                }
            }

            boolean useClaudeThisRun = (useClaudeAsBlindTester || effectiveGeminiUnavailable.get())
                    && !effectiveClaudeUnavailable.get();
            Map<Integer, Integer> modelPicks = callBlindTesterFastFallback(
                    prompt.toString(), useClaudeThisRun, effectiveGeminiUnavailable, effectiveClaudeUnavailable);
            for (int qIdx = 0; qIdx < n; qIdx++) {
                Integer pickedIdx = modelPicks.get(qIdx);
                List<CandidateOption> opts = shuffledPerQuestion.get(qIdx);
                if (pickedIdx != null && pickedIdx >= 0 && pickedIdx < opts.size() && opts.get(pickedIdx).isCorrect()) {
                    rightCounts[qIdx]++;
                }
            }
        }

        List<BlindTestedCandidate> results = new ArrayList<>(n);
        for (int qIdx = 0; qIdx < n; qIdx++) {
            int right = rightCounts[qIdx];
            int wrong = runs - right;
            boolean survived = right < 2;
            results.add(new BlindTestedCandidate(chunkCandidates.get(qIdx), chunkIndex, right, wrong, survived));
        }
        return results;
    }

    public EpisodeBlindTestOutcome applySurvivalAndFallback(List<BlindTestedCandidate> allTested, int targetCount) {
        Comparator<BlindTestedCandidate> softRankOrder = Comparator
                .comparing((BlindTestedCandidate c) -> PipelineCandidateNormalizer.isOptionRuleCompliant(c.candidate()) ? 0 : 1)
                .thenComparing(c -> c.candidate().evidenceTimestamp() != null ? 0 : 1);

        List<BlindTestedCandidate> survivors = new ArrayList<>(allTested.stream()
                .filter(BlindTestedCandidate::survivedBlindTest)
                .toList());
        survivors.sort(softRankOrder);

        boolean anyNonCompliant = allTested.stream()
                .anyMatch(c -> !PipelineCandidateNormalizer.isOptionRuleCompliant(c.candidate()));
        if (survivors.size() >= MIN_SURVIVAL_THRESHOLD) {
            return new EpisodeBlindTestOutcome(survivors, anyNonCompliant, survivors.size());
        }

        int limit = Math.max(MIN_SURVIVAL_THRESHOLD, targetCount);
        List<BlindTestedCandidate> ranked = new ArrayList<>(allTested);
        ranked.sort(Comparator.comparingInt(BlindTestedCandidate::wrongGuesses).reversed()
                .thenComparingInt(BlindTestedCandidate::correctGuesses)
                .thenComparing(softRankOrder));
        List<BlindTestedCandidate> kept = ranked.subList(0, Math.min(limit, ranked.size()));
        return new EpisodeBlindTestOutcome(kept, true, survivors.size());
    }

    private Map<Integer, Integer> callBlindTesterFastFallback(
            String prompt, boolean useClaude,
            AtomicBoolean geminiUnavailableForJob, AtomicBoolean claudeUnavailableForJob) {
        String sysPrompt = "You are taking a blind multiple-choice test without any source transcript. Output strict JSON only.";
        String raw = PipelineModelFallbackHelper.callModelDetailed(
                claudeClient, geminiTextClient, useClaude,
                geminiUnavailableForJob, claudeUnavailableForJob,
                sysPrompt, prompt, 1024, 3, backoffBaseMs).text();
        return parseAnswersMap(raw);
    }

    private Map<Integer, Integer> parseAnswersMap(String rawJson) {
        Map<Integer, Integer> map = new HashMap<>();
        try {
            JsonNode root = GeminiJsonRepairSupport.parseLenientJson(objectMapper, rawJson);
            JsonNode arr = root.path("answers");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    if (item.has("questionIndex") && item.has("selectedOptionIndex")) {
                        map.put(item.get("questionIndex").asInt(), item.get("selectedOptionIndex").asInt());
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to parse blind test JSON response: {}", ex.getMessage());
        }
        return map;
    }
}
