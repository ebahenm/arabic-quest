// Provides retry/backoff, HTTP timeout defaults, error mapping, and timestamp/concept guards for Gemini video quiz generation.
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionType;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GeminiVideoBankSupport.class);
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
        return executeWithRetry(action, baseBackoffMs, phaseName, new String[0]);
    }

    public static <T> T executeWithRetry(Callable<T> action, long baseBackoffMs, String phaseName, String... secrets) {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.call();
            } catch (Exception ex) {
                lastEx = ex;
                boolean transientErr = isTransientGeminiError(ex);
                if (!transientErr || attempt == MAX_ATTEMPTS) {
                    AiErrorSanitizer.logProviderHttpError(log, "Gemini", phaseName, ex, secrets);
                    throw mapGeminiException(ex, phaseName, secrets);
                }
                if (baseBackoffMs > 0) {
                    try {
                        Thread.sleep(baseBackoffMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        AiErrorSanitizer.logProviderHttpError(log, "Gemini", phaseName, ex, secrets);
                    throw mapGeminiException(ex, phaseName, secrets);
                    }
                }
            }
        }
        AiErrorSanitizer.logProviderHttpError(log, "Gemini", phaseName, lastEx, secrets);
        throw mapGeminiException(lastEx, phaseName, secrets);
    }

    public static RuntimeException mapGeminiException(Exception ex, String phaseName) {
        return mapGeminiException(ex, phaseName, new String[0]);
    }

    public static RuntimeException mapGeminiException(Exception ex, String phaseName, String... secrets) {
        if (ex instanceof TooManyRequestsException tme) {
            return tme;
        }
        String detail = AiErrorSanitizer.extractProviderErrorDetail(ex, secrets);
        String suffix = detail.isEmpty() ? "" : ": " + detail;
        if (ex instanceof HttpClientErrorException.TooManyRequests tmr) {
            long waitSecs = parseRetryAfterSeconds(tmr, 60L);
            return new TooManyRequestsException(
                    "Gemini API rate quota reached during " + phaseName + " (HTTP 429). Wait ~" + waitSecs + "s and click Retry." + suffix,
                    waitSecs
            );
        }
        if (ex instanceof HttpClientErrorException clientErr) {
            int code = clientErr.getStatusCode().value();
            if (code == 400 || code == 403 || code == 404) {
                return new AiProviderClientException(code,
                        "The YouTube video is private, unavailable, or rejected during " + phaseName + " (HTTP " + code + ")"
                                + (detail.isEmpty() ? ". Check video visibility or provide an optional transcript." : ": " + detail)
                );
            }
            return new AiProviderClientException(code,
                    "Gemini rejected the request during " + phaseName + " (HTTP " + code + "). Nothing was saved" + suffix
            );
        }
        if (ex instanceof ResourceAccessException) {
            return new IllegalStateException("Gemini request timed out while analyzing the video (" + phaseName + "). Nothing was saved ? please click Retry.");
        }
        if (ex instanceof HttpServerErrorException serverErr) {
            return new IllegalStateException("Gemini service was temporarily unavailable during " + phaseName
                    + " (HTTP " + serverErr.getStatusCode().value() + "). Nothing was saved ? please retry." + suffix);
        }
        if (ex instanceof IllegalStateException ise) {
            return ise;
        }
        String kind = ex != null ? ex.getClass().getSimpleName() : "UnknownError";
        return new IllegalStateException("Gemini " + phaseName + " failed (" + kind + "). Nothing was saved ? check API quota or retry.");
    }

    private static long parseRetryAfterSeconds(HttpClientErrorException ex, long defaultSeconds) {
        if (ex.getResponseHeaders() == null) {
            return defaultSeconds;
        }
        String raw = ex.getResponseHeaders().getFirst("Retry-After");
        if (raw != null) {
            try {
                long parsed = Long.parseLong(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultSeconds;
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
