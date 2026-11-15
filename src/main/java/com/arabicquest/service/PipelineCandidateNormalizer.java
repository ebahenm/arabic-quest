// Normalizes Pass 1 candidate timestamps, recovers missing timestamps from cues, and applies soft/hard option rules.
package com.arabicquest.service;

import com.arabicquest.model.QuestionType;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.arabicquest.service.TranscriptParserService.Cue;
import com.arabicquest.service.TranscriptParserService.TranscriptChunk;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PipelineCandidateNormalizer {

    public static final String UNVERIFIED_TIMESTAMP_TAG = "[timestamp: unverified]";

    private static final Pattern COLON_TIME_PATTERN = Pattern.compile(
            "(?:\\[)?\\s*(?:(\\d{1,3}):)?(\\d{1,3}):(\\d{2})(?:[.,]\\d{1,3})?\\s*(?:])?");
    private static final Pattern NUMERIC_SEC_PATTERN = Pattern.compile(
            "^\\[?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:s|sec|secs|seconds)?\\s*]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_CONTENT_PATTERN = Pattern.compile("\\(([^)]+)\\)");
    private static final Pattern ARABIC_DIACRITICS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u0640]");

    private static final List<String> FORBIDDEN_OPTION_PHRASES = List.of(
            "all of the above", "none of the above", "both a and b", "both a & b",
            "جميع ما سبق", "كل ما سبق", "لا شيء مما سبق"
    );

    private PipelineCandidateNormalizer() {
    }

    public record ParsedPass1Batch(List<CandidateMcq> mcqs, List<CandidateOpen> opens) {}

    public static final class StageCounts {
        public int pass1Parsed;
        public int droppedNullTimestamp;
        public int droppedOutOfRange;
        public int droppedOptionRule;
        public int timestampValid;
        public int pass2Verified;
        public int pass3Survived;

        public void add(StageCounts other) {
            this.pass1Parsed += other.pass1Parsed;
            this.droppedNullTimestamp += other.droppedNullTimestamp;
            this.droppedOutOfRange += other.droppedOutOfRange;
            this.droppedOptionRule += other.droppedOptionRule;
            this.timestampValid += other.timestampValid;
            this.pass2Verified += other.pass2Verified;
            this.pass3Survived += other.pass3Survived;
        }

        public String formatSummary() {
            return String.format(Locale.ROOT,
                    "pass1Parsed=%d, droppedNullTimestamp=%d, droppedOutOfRange=%d, droppedOptionRule=%d, timestampValid=%d, pass2Verified=%d, pass3Survived=%d",
                    pass1Parsed, droppedNullTimestamp, droppedOutOfRange, droppedOptionRule,
                    timestampValid, pass2Verified, pass3Survived);
        }
    }

    public record ChunkNormalizationResult(
            List<CandidateMcq> keptMcqs,
            List<CandidateOpen> keptOpens,
            StageCounts counts,
            int softFlaggedOptionCount,
            int unverifiedTimestampCount
    ) {}

    public static Integer parseFlexibleTimestampSeconds(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) {
            int val = (int) Math.floor(n.doubleValue());
            return val >= 0 ? val : null;
        }
        if (raw instanceof JsonNode node) {
            if (node.isNull() || node.isMissingNode()) return null;
            if (node.isNumber()) {
                int val = (int) Math.floor(node.asDouble());
                return val >= 0 ? val : null;
            }
            if (node.isTextual()) return parseFlexibleTimestampString(node.asText());
            return null;
        }
        return parseFlexibleTimestampString(raw.toString());
    }

    public static Integer parseFlexibleTimestampString(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "unverified".equalsIgnoreCase(trimmed)) {
            return null;
        }
        Matcher numMatcher = NUMERIC_SEC_PATTERN.matcher(trimmed);
        if (numMatcher.matches()) {
            try {
                int secs = (int) Math.floor(Double.parseDouble(numMatcher.group(1)));
                return secs >= 0 ? secs : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Matcher colonMatcher = COLON_TIME_PATTERN.matcher(trimmed);
        if (colonMatcher.find()) {
            try {
                String hGroup = colonMatcher.group(1);
                int minutes = Integer.parseInt(colonMatcher.group(2));
                int seconds = Integer.parseInt(colonMatcher.group(3));
                if (seconds < 0 || seconds >= 60 || minutes < 0) return null;
                if (hGroup != null && !hGroup.isEmpty()) {
                    int hours = Integer.parseInt(hGroup);
                    return hours * 3600 + minutes * 60 + seconds;
                }
                return minutes * 60 + seconds;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Integer extractCandidateTimestamp(JsonNode qn) {
        if (qn == null || qn.isNull()) return null;
        Integer fromMarker = parseFlexibleTimestampSeconds(qn.get("evidenceMarker"));
        if (fromMarker != null) return fromMarker;
        Integer fromTs = parseFlexibleTimestampSeconds(qn.get("evidenceTimestamp"));
        if (fromTs != null) return fromTs;
        return parseFlexibleTimestampSeconds(qn.get("timestamp"));
    }

    public static ParsedPass1Batch parsePass1Node(JsonNode root) {
        List<CandidateMcq> mcqs = new ArrayList<>();
        for (JsonNode qn : root.path("mcqs")) {
            List<CandidateOption> opts = new ArrayList<>();
            for (JsonNode on : qn.path("options")) {
                opts.add(new CandidateOption(on.path("optionText").asText(""), on.path("isCorrect").asBoolean(false)));
            }
            QuestionType type = QuestionBankMutationHelper.parseTypeOrDefault(qn.path("type").asText("REASONING"));
            Integer ts = extractCandidateTimestamp(qn);
            mcqs.add(new CandidateMcq(qn.path("questionText").asText(""), type, qn.path("evidence").asText(""), ts, opts));
        }
        List<CandidateOpen> opens = new ArrayList<>();
        for (JsonNode opn : root.path("openQuestions")) {
            Integer ts = extractCandidateTimestamp(opn);
            opens.add(new CandidateOpen(opn.path("promptText").asText(""), opn.path("evidence").asText(""), ts));
        }
        return new ParsedPass1Batch(mcqs, opens);
    }

    public static CandidateMcq enforceOptionHardRulesOnly(CandidateMcq mcq) {
        if (mcq == null || mcq.options() == null || mcq.options().size() < 2) return null;
        int correctCount = 0;
        for (CandidateOption o : mcq.options()) {
            String t = o.optionText() != null ? o.optionText().trim() : "";
            if (t.isEmpty() || hasForbiddenOptionPhrase(t)) return null;
            if (o.isCorrect()) correctCount++;
        }
        if (correctCount != 1) return null;
        return mcq;
    }

    public static boolean isOptionRuleCompliant(CandidateMcq mcq) {
        if (mcq == null || mcq.options() == null || mcq.options().size() != 4) return false;
        int correctCount = 0, correctLen = 0, maxDistractorLen = 0;
        for (CandidateOption o : mcq.options()) {
            String t = o.optionText() != null ? o.optionText().trim() : "";
            if (t.isEmpty() || hasForbiddenOptionPhrase(t)) return false;
            if (o.isCorrect()) {
                correctCount++;
                correctLen = t.length();
            } else {
                maxDistractorLen = Math.max(maxDistractorLen, t.length());
            }
        }
        return correctCount == 1 && correctLen <= maxDistractorLen;
    }

    public static boolean hasForbiddenOptionPhrase(String optionText) {
        if (optionText == null || optionText.isBlank()) return true;
        String lower = optionText.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        for (String phrase : FORBIDDEN_OPTION_PHRASES) {
            if (lower.contains(phrase)) return true;
        }
        return false;
    }

    public static Integer recoverTimestampFromCues(
            String evidenceQuote, String fallbackQuestionText, List<Cue> allCues, TranscriptChunk chunk) {
        if (allCues == null || allCues.isEmpty()) return null;
        List<Cue> chunkCues = allCues.stream()
                .filter(c -> chunk == null || (c.startSeconds() >= chunk.startSeconds() && c.startSeconds() <= chunk.endSeconds()))
                .toList();
        List<Cue> searchPool = chunkCues.isEmpty() ? allCues : chunkCues;

        String normEvidence = normalizeForMatch(evidenceQuote);
        List<String> parenTerms = extractParenthesizedNormalized(evidenceQuote, fallbackQuestionText);

        if (normEvidence.length() >= 4) {
            for (Cue cue : searchPool) {
                String normCue = normalizeForMatch(cue.text());
                if (!normCue.isEmpty() && (normCue.contains(normEvidence) || normEvidence.contains(normCue))) {
                    return cue.startSeconds();
                }
            }
        }
        for (String term : parenTerms) {
            if (term.length() >= 3) {
                for (Cue cue : searchPool) {
                    if (normalizeForMatch(cue.text()).contains(term)) {
                        return cue.startSeconds();
                    }
                }
            }
        }

        Set<String> queryTokens = significantTokens(normEvidence + " " + normalizeForMatch(fallbackQuestionText));
        if (queryTokens.isEmpty()) return null;
        Cue bestCue = null;
        double bestScore = 0.0;
        int bestHits = 0;
        for (Cue cue : searchPool) {
            Set<String> cueTokens = significantTokens(normalizeForMatch(cue.text()));
            if (cueTokens.isEmpty()) continue;
            int hits = 0;
            for (String qt : queryTokens) {
                if (cueTokens.contains(qt)) hits++;
            }
            if (hits == 0) continue;
            double score = hits / (double) Math.max(1, Math.min(queryTokens.size(), cueTokens.size()));
            if (hits > bestHits || (hits == bestHits && score > bestScore)) {
                bestHits = hits;
                bestScore = score;
                bestCue = cue;
            }
        }
        if (bestCue != null && (bestHits >= 2 || (bestHits >= 1 && bestScore >= 0.45))) {
            return bestCue.startSeconds();
        }
        return null;
    }

    public static ChunkNormalizationResult normalizeChunkCandidates(
            ParsedPass1Batch batch, TranscriptChunk chunk, List<Cue> cues, TranscriptParserService parser) {
        StageCounts counts = new StageCounts();
        counts.pass1Parsed = batch.mcqs().size();
        List<CandidateMcq> keptMcqs = new ArrayList<>();
        int softFlagged = 0, unverifiedCount = 0;

        for (CandidateMcq rawMcq : batch.mcqs()) {
            CandidateMcq checked = enforceOptionHardRulesOnly(rawMcq);
            if (checked == null) {
                counts.droppedOptionRule++;
                continue;
            }
            if (!isOptionRuleCompliant(checked)) {
                softFlagged++;
            }
            Integer ts = checked.evidenceTimestamp();
            boolean validTs = ts != null && parser.isTimestampValidForChunk(ts, chunk);
            if (!validTs) {
                if (ts == null) counts.droppedNullTimestamp++;
                else counts.droppedOutOfRange++;
                Integer recovered = recoverTimestampFromCues(checked.evidence(), checked.questionText(), cues, chunk);
                if (recovered != null) {
                    checked = new CandidateMcq(checked.questionText(), checked.type(), checked.evidence(), recovered, checked.options());
                    counts.timestampValid++;
                } else {
                    unverifiedCount++;
                    String ev = markEvidenceUnverified(checked.evidence());
                    checked = new CandidateMcq(checked.questionText(), checked.type(), ev, null, checked.options());
                }
            } else {
                counts.timestampValid++;
            }
            keptMcqs.add(checked);
        }

        keptMcqs.sort(Comparator
                .comparing((CandidateMcq m) -> isOptionRuleCompliant(m) ? 0 : 1)
                .thenComparing(m -> m.evidenceTimestamp() != null ? 0 : 1));

        List<CandidateOpen> keptOpens = new ArrayList<>();
        for (CandidateOpen open : batch.opens()) {
            Integer ts = open.evidenceTimestamp();
            boolean validTs = ts != null && parser.isTimestampValidForChunk(ts, chunk);
            if (!validTs) {
                Integer recovered = recoverTimestampFromCues(open.evidence(), open.promptText(), cues, chunk);
                if (recovered != null) {
                    keptOpens.add(new CandidateOpen(open.promptText(), open.evidence(), recovered));
                } else {
                    keptOpens.add(new CandidateOpen(open.promptText(), markEvidenceUnverified(open.evidence()), null));
                }
            } else {
                keptOpens.add(open);
            }
        }
        return new ChunkNormalizationResult(keptMcqs, keptOpens, counts, softFlagged, unverifiedCount);
    }

    public static String markEvidenceUnverified(String evidence) {
        String base = evidence != null ? evidence.trim() : "";
        if (base.contains(UNVERIFIED_TIMESTAMP_TAG)) return base;
        return base.isEmpty() ? UNVERIFIED_TIMESTAMP_TAG : base + " " + UNVERIFIED_TIMESTAMP_TAG;
    }

    public static String normalizeForMatch(String input) {
        if (input == null || input.isBlank()) return "";
        String s = ARABIC_DIACRITICS.matcher(input).replaceAll("");
        s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي');
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> extractParenthesizedNormalized(String a, String b) {
        List<String> out = new ArrayList<>();
        for (String src : new String[]{a, b}) {
            if (src == null) continue;
            Matcher m = PAREN_CONTENT_PATTERN.matcher(src);
            while (m.find()) {
                String norm = normalizeForMatch(m.group(1));
                if (!norm.isEmpty()) out.add(norm);
            }
        }
        return out;
    }

    private static Set<String> significantTokens(String normalized) {
        Set<String> set = new LinkedHashSet<>();
        if (normalized == null || normalized.isBlank()) return set;
        for (String tok : normalized.split("\\s+")) {
            if (tok.length() >= 3) set.add(tok);
        }
        return set;
    }
}
