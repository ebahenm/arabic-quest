// Evaluates parsed transcripts against WPM, repetition, music/applause noise, Arabic script ratio, and duration coverage.
package com.arabicquest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TranscriptQualityGate {

    public static final double MIN_WORDS_PER_MINUTE = 25.0;
    public static final double MAX_Repeated_LINE_RATIO = 0.45;
    public static final double MAX_MUSIC_APPLAUSE_RATIO = 0.30;
    public static final double MIN_ARABIC_SCRIPT_RATIO = 0.50;
    public static final double MIN_COVERAGE_RATIO = 0.50;

    private final ObjectMapper objectMapper;

    public TranscriptQualityGate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record QualityAssessment(
            String status,
            boolean passed,
            List<String> reasons,
            double wordsPerMinute,
            double repeatedLineRatio,
            double musicApplauseRatio,
            double arabicScriptRatio,
            double coverageRatio
    ) {}

    public QualityAssessment evaluate(TranscriptParserService.ParsedTranscript parsed, int episodeDurationSeconds) {
        List<String> reasons = new ArrayList<>();
        if (parsed == null || parsed.cues() == null || parsed.cues().isEmpty() || parsed.wordCount() == 0) {
            reasons.add("No valid subtitle cues or spoken words found.");
            return new QualityAssessment("FAIL", false, reasons, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        int safeDuration = Math.max(60, episodeDurationSeconds > 0 ? episodeDurationSeconds : 1800);
        double durationMinutes = safeDuration / 60.0;
        double wpm = parsed.wordCount() / durationMinutes;
        int rawLines = Math.max(1, parsed.rawLineCount());
        double repeatedRatio = parsed.duplicateLineCount() / (double) rawLines;
        double musicRatio = parsed.musicApplauseCount() / (double) rawLines;
        double arabicRatio = computeArabicScriptRatio(parsed.plainText());
        double coverage = parsed.lastCueEndSeconds() / (double) safeDuration;

        if (wpm < MIN_WORDS_PER_MINUTE) {
            reasons.add(String.format(Locale.ROOT,
                    "Very low speech rate: %.1f words/min (minimum %.0f words/min for %ds episode).",
                    wpm, MIN_WORDS_PER_MINUTE, safeDuration));
        }
        if (repeatedRatio > MAX_Repeated_LINE_RATIO) {
            reasons.add(String.format(Locale.ROOT,
                    "High repeated-line ratio: %.0f%% (maximum %.0f%%).",
                    repeatedRatio * 100.0, MAX_Repeated_LINE_RATIO * 100.0));
        }
        if (musicRatio > MAX_MUSIC_APPLAUSE_RATIO) {
            reasons.add(String.format(Locale.ROOT,
                    "Excessive [Music]/[Applause] ratio: %.0f%% (maximum %.0f%%).",
                    musicRatio * 100.0, MAX_MUSIC_APPLAUSE_RATIO * 100.0));
        }
        if (arabicRatio < MIN_ARABIC_SCRIPT_RATIO) {
            reasons.add(String.format(Locale.ROOT,
                    "Low expected-language (Arabic script) ratio: %.0f%% (minimum %.0f%%).",
                    arabicRatio * 100.0, MIN_ARABIC_SCRIPT_RATIO * 100.0));
        }
        if (coverage < MIN_COVERAGE_RATIO) {
            reasons.add(String.format(Locale.ROOT,
                    "Low episode duration coverage: last cue at %ds covers %.0f%% of %ds episode (minimum %.0f%%).",
                    parsed.lastCueEndSeconds(), coverage * 100.0, safeDuration, MIN_COVERAGE_RATIO * 100.0));
        }

        boolean passed = reasons.isEmpty();
        return new QualityAssessment(
                passed ? "PASS" : "FAIL",
                passed,
                reasons,
                round2(wpm),
                round2(repeatedRatio),
                round2(musicRatio),
                round2(arabicRatio),
                round2(coverage)
        );
    }

    public String toReportJson(QualityAssessment assessment) {
        try {
            return objectMapper.writeValueAsString(assessment);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static double computeArabicScriptRatio(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int totalLetters = 0;
        int arabicLetters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                totalLetters++;
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.ARABIC
                        || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                        || block == Character.UnicodeBlock.ARABIC_EXTENDED_A
                        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) {
                    arabicLetters++;
                }
            }
        }
        if (totalLetters == 0) {
            return 0.0;
        }
        return arabicLetters / (double) totalLetters;
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
