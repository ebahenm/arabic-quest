// Provides retry/backoff, HTTP timeout defaults, error mapping, and timestamp/concept guards for Gemini video quiz generation.
package com.arabicquest.service;

import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionType;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeminiVideoBankSupport {

    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final Pattern PAREN_TERM_PATTERN = Pattern.compile("\\(([^()]{2,60})\\)");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "what", "which", "when", "where",
            "why", "how", "does", "did", "was", "were", "are", "into", "about", "according", "video",
            "episode", "speaker", "mentioned", "described", "term", "meaning", "refers"
    );

    private GeminiVideoBankSupport() {
    }

    public static RestTemplate createDefaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    public static <T> T executeWithRetry(Callable<T> action, long baseBackoffMs, String phaseName) {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.call();
            } catch (Exception ex) {
                lastEx = ex;
                boolean transientErr = isTransientGeminiError(ex);
                if (!transientErr || attempt == MAX_ATTEMPTS) {
                    throw mapGeminiException(ex, phaseName);
                }
                if (baseBackoffMs > 0) {
                    try {
                        Thread.sleep(baseBackoffMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw mapGeminiException(ex, phaseName);
                    }
                }
            }
        }
        throw mapGeminiException(lastEx, phaseName);
    }

    public static RuntimeException mapGeminiException(Exception ex, String phaseName) {
        if (ex instanceof TooManyRequestsException tme) {
            return tme;
        }
        if (ex instanceof HttpClientErrorException.TooManyRequests) {
            return new TooManyRequestsException(
                    "Gemini API rate quota reached during " + phaseName + ". Nothing was saved — please wait a moment and click Retry.",
                    60
            );
        }
        if (ex instanceof HttpClientErrorException.BadRequest
                || ex instanceof HttpClientErrorException.NotFound
                || ex instanceof HttpClientErrorException.Forbidden) {
            return new IllegalArgumentException(
                    "The YouTube video is private, unavailable, or rejected during " + phaseName + ". Nothing was saved."
            );
        }
        if (ex instanceof ResourceAccessException) {
            return new IllegalStateException(
                    "Gemini request timed out while analyzing the video (" + phaseName + "). Nothing was saved — please click Retry."
            );
        }
        if (ex instanceof HttpServerErrorException) {
            return new IllegalStateException(
                    "Gemini service was temporarily unavailable during " + phaseName + ". Nothing was saved — please retry."
            );
        }
        if (ex instanceof IllegalStateException ise) {
            return ise;
        }
        String kind = ex != null ? ex.getClass().getSimpleName() : "UnknownError";
        return new IllegalStateException(
                "Gemini " + phaseName + " failed (" + kind + "). Nothing was saved — check API quota or retry."
        );
    }

    public static List<CandidateMcq> filterValidTimestampsForMcqs(
            List<CandidateMcq> list,
            int knownDurationSeconds,
            boolean requireTimestamp) {
        if (list == null) {
            return List.of();
        }
        List<CandidateMcq> out = new ArrayList<>();
        for (CandidateMcq m : list) {
            if (isTimestampWithinBounds(m.evidenceTimestamp(), knownDurationSeconds, requireTimestamp)) {
                out.add(m);
            }
        }
        return out;
    }

    public static List<CandidateOpen> filterValidTimestampsForOpens(
            List<CandidateOpen> list,
            int knownDurationSeconds,
            boolean requireTimestamp) {
        if (list == null) {
            return List.of();
        }
        List<CandidateOpen> out = new ArrayList<>();
        for (CandidateOpen o : list) {
            if (isTimestampWithinBounds(o.evidenceTimestamp(), knownDurationSeconds, requireTimestamp)) {
                out.add(o);
            }
        }
        return out;
    }

    public static List<CandidateMcq> deduplicateMcqsByConcept(
            List<CandidateMcq> candidates,
            Collection<Question> existingQuestions) {
        List<Set<String>> seenTokenSets = new ArrayList<>();
        Set<String> seenVocabTerms = new HashSet<>();

        if (existingQuestions != null) {
            for (Question eq : existingQuestions) {
                if (eq.getQuestionText() == null) {
                    continue;
                }
                seenTokenSets.add(tokenizeStem(eq.getQuestionText()));
                if (eq.getType() == QuestionType.VOCABULARY || isDefinitionStem(eq.getQuestionText())) {
                    extractParenTerms(eq.getQuestionText(), seenVocabTerms);
                }
            }
        }

        List<CandidateMcq> accepted = new ArrayList<>();
        for (CandidateMcq c : candidates) {
            if (c.questionText() == null || c.questionText().isBlank()) {
                continue;
            }
            Set<String> tokens = tokenizeStem(c.questionText());
            boolean nearDup = false;
            for (Set<String> prev : seenTokenSets) {
                if (jaccardSimilarity(tokens, prev) >= 0.72) {
                    nearDup = true;
                    break;
                }
            }
            if (nearDup) {
                continue;
            }

            Set<String> candidateTerms = new HashSet<>();
            extractParenTerms(c.questionText(), candidateTerms);
            boolean isVocabFocus = (c.type() == QuestionType.VOCABULARY || isDefinitionStem(c.questionText()));
            if (isVocabFocus && !candidateTerms.isEmpty()) {
                boolean repeatedTerm = false;
                for (String t : candidateTerms) {
                    if (seenVocabTerms.contains(t)) {
                        repeatedTerm = true;
                        break;
                    }
                }
                if (repeatedTerm) {
                    continue;
                }
                seenVocabTerms.addAll(candidateTerms);
            }

            seenTokenSets.add(tokens);
            accepted.add(c);
        }
        return accepted;
    }

    private static boolean isTransientGeminiError(Exception ex) {
        return ex instanceof HttpClientErrorException.TooManyRequests
                || ex instanceof HttpServerErrorException
                || ex instanceof ResourceAccessException;
    }

    private static boolean isTimestampWithinBounds(Integer ts, int knownDurationSeconds, boolean requireTimestamp) {
        if (ts == null) {
            return !requireTimestamp;
        }
        if (ts < 0) {
            return false;
        }
        return knownDurationSeconds <= 0 || ts <= knownDurationSeconds + 15;
    }

    private static boolean isDefinitionStem(String stem) {
        String lower = stem.toLowerCase(Locale.ROOT);
        return lower.contains("what does") || lower.contains("what is the term")
                || lower.contains("which arabic term") || lower.contains("meaning of")
                || lower.contains("translate") || lower.contains("refers to");
    }

    private static void extractParenTerms(String text, Set<String> target) {
        Matcher m = PAREN_TERM_PATTERN.matcher(text);
        while (m.find()) {
            String normalized = m.group(1).trim().toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            if (normalized.length() >= 2) {
                target.add(normalized);
            }
        }
    }

    private static Set<String> tokenizeStem(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}\\s]", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : cleaned.split("\\s+")) {
            if (part.length() >= 3 && !STOPWORDS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String t : a) {
            if (b.contains(t)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }
}
