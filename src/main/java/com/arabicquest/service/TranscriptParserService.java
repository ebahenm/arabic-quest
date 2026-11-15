// Parses SRT and VTT subtitle files, deduplicates rolling VTT cues, and chunks transcripts with [mm:ss] markers.
package com.arabicquest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TranscriptParserService {

    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int CHUNK_DURATION_SECONDS = 1500;
    public static final int MARKER_INTERVAL_SECONDS = 45;
    public static final int MARKER_TOLERANCE_SECONDS = 60;

    private static final Pattern TIMECODE_LINE = Pattern.compile(
            "^\\s*((?:\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{1,3}).*$");
    private static final Pattern INLINE_TAGS = Pattern.compile("<[^>]*>|\\{\\\\[^}]*}");
    private static final Pattern MUSIC_APPLAUSE = Pattern.compile(
            "(?i)\\[\\s*(?:music|applause|laughter|silence|موسيقى|تصفيق)\\s*]|\\(\\s*(?:music|applause)\\s*\\)|[♪♫]+");

    private final ObjectMapper objectMapper;

    public TranscriptParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Cue(int startSeconds, int endSeconds, String text) {}

    public record ParsedTranscript(
            List<Cue> cues, String plainText, int wordCount, int rawLineCount,
            int musicApplauseCount, int duplicateLineCount, int lastCueEndSeconds) {}

    public record TranscriptChunk(
            int chunkIndex, int startSeconds, int endSeconds, String markedText, List<Integer> markerSeconds) {}

    public String decodeStrictUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Transcript file is empty.");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Transcript file exceeds maximum size of 2 MB.");
        }
        for (byte b : bytes) {
            if (b == 0) {
                throw new IllegalArgumentException("Binary file rejected: transcript must be valid UTF-8 text.");
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            String text = decoded.toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid file encoding: transcript must be UTF-8 encoded.");
        }
    }

    public ParsedTranscript parseSrtOrVtt(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("Transcript content is blank.");
        }
        String[] lines = rawContent.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<Cue> cues = new ArrayList<>();
        int rawLineCount = 0, musicApplauseCount = 0, duplicateLineCount = 0, lastCueEndSeconds = 0;
        String lastKeptLine = "", lastCueFullText = "";

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("Kind:") || line.startsWith("Language:")) {
                i++;
                continue;
            }
            if (line.startsWith("NOTE") || line.startsWith("STYLE")) {
                while (i < lines.length && !lines[i].trim().isEmpty()) i++;
                continue;
            }
            Matcher tm = TIMECODE_LINE.matcher(line);
            if (!tm.matches()) {
                i++;
                continue;
            }
            int startSec = parseTimestampToSeconds(tm.group(1));
            int endSec = Math.max(startSec, parseTimestampToSeconds(tm.group(2)));
            lastCueEndSeconds = Math.max(lastCueEndSeconds, endSec);
            i++;

            List<String> cueCleanLines = new ArrayList<>();
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                String subLine = lines[i].trim();
                if (TIMECODE_LINE.matcher(subLine).matches()) break;
                rawLineCount++;
                if (MUSIC_APPLAUSE.matcher(subLine).find()) musicApplauseCount++;
                String cleaned = stripFormatting(subLine);
                if (!cleaned.isEmpty()) {
                    if (cleaned.equalsIgnoreCase(lastKeptLine)) {
                        duplicateLineCount++;
                    } else {
                        cueCleanLines.add(cleaned);
                        lastKeptLine = cleaned;
                    }
                }
                i++;
            }

            if (!cueCleanLines.isEmpty()) {
                String joinedCue = stripRollingOverlap(lastCueFullText, String.join(" ", cueCleanLines).replaceAll("\\s+", " ").trim());
                if (!joinedCue.isEmpty()) {
                    cues.add(new Cue(startSec, endSec, joinedCue));
                    lastCueFullText = joinedCue;
                } else {
                    duplicateLineCount++;
                }
            }
        }
        if (cues.isEmpty()) {
            Pattern bracketTime = Pattern.compile("^\\[(?:(\\d+):)?(\\d{1,2}):(\\d{2})]\\s*(.*)$");
            for (String rawLine : lines) {
                String subLine = rawLine.trim();
                if (subLine.isEmpty()) continue;
                rawLineCount++;
                if (MUSIC_APPLAUSE.matcher(subLine).find()) musicApplauseCount++;
                int sec = lastCueEndSeconds;
                Matcher bm = bracketTime.matcher(subLine);
                if (bm.matches()) {
                    int h = bm.group(1) != null ? Integer.parseInt(bm.group(1)) : 0;
                    sec = h * 3600 + Integer.parseInt(bm.group(2)) * 60 + Integer.parseInt(bm.group(3));
                    subLine = bm.group(4).trim();
                } else {
                    sec = lastCueEndSeconds + 15;
                }
                String cleaned = stripRollingOverlap(lastCueFullText, stripFormatting(subLine));
                if (!cleaned.isEmpty()) {
                    cues.add(new Cue(sec, sec + 10, cleaned));
                    lastCueEndSeconds = sec + 10;
                    lastCueFullText = cleaned;
                } else {
                    duplicateLineCount++;
                }
            }
        }

        StringBuilder plainBuilder = new StringBuilder();
        for (Cue cue : cues) {
            if (!plainBuilder.isEmpty()) plainBuilder.append(' ');
            plainBuilder.append(cue.text());
        }
        String plainText = plainBuilder.toString().replaceAll("\\s+", " ").trim();
        return new ParsedTranscript(cues, plainText, countWords(plainText), rawLineCount,
                musicApplauseCount, duplicateLineCount, lastCueEndSeconds);
    }

    public List<TranscriptChunk> buildMarkedChunks(List<Cue> cues, String fallbackPlainText, int durationSeconds) {
        int safeDuration = Math.max(60, durationSeconds);
        List<Cue> effectiveCues = (cues != null && !cues.isEmpty())
                ? cues
                : synthesizeCuesFromPlainText(fallbackPlainText, safeDuration);
        if (effectiveCues.isEmpty()) return List.of();
        int totalSec = Math.max(safeDuration, effectiveCues.getLast().endSeconds());
        int numChunks = Math.max(1, (int) Math.ceil(totalSec / (double) CHUNK_DURATION_SECONDS));

        List<TranscriptChunk> chunks = new ArrayList<>();
        for (int idx = 0; idx < numChunks; idx++) {
            int cStart = idx * CHUNK_DURATION_SECONDS;
            int cEnd = (idx == numChunks - 1) ? Math.max((idx + 1) * CHUNK_DURATION_SECONDS, totalSec) : (idx + 1) * CHUNK_DURATION_SECONDS;
            StringBuilder sb = new StringBuilder();
            List<Integer> markers = new ArrayList<>();
            int lastMarker = -MARKER_INTERVAL_SECONDS;

            for (Cue cue : effectiveCues) {
                if (cue.startSeconds() >= cStart && (cue.startSeconds() < cEnd || idx == numChunks - 1)) {
                    if (markers.isEmpty() || cue.startSeconds() - lastMarker >= MARKER_INTERVAL_SECONDS) {
                        int markerSec = Math.max(cStart, cue.startSeconds());
                        markers.add(markerSec);
                        lastMarker = markerSec;
                        sb.append('[').append(formatMmSs(markerSec)).append("] ");
                    }
                    sb.append(cue.text()).append(' ');
                }
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) chunks.add(new TranscriptChunk(idx, cStart, cEnd, text, markers));
        }
        return chunks;
    }

    public boolean isTimestampValidForChunk(Integer evidenceTimestamp, TranscriptChunk chunk) {
        if (evidenceTimestamp == null || chunk == null) return false;
        if (evidenceTimestamp < chunk.startSeconds() || evidenceTimestamp > chunk.endSeconds()) return false;
        if (chunk.markerSeconds() == null || chunk.markerSeconds().isEmpty()) return true;
        for (int marker : chunk.markerSeconds()) {
            if (Math.abs(evidenceTimestamp - marker) <= MARKER_TOLERANCE_SECONDS) return true;
        }
        return false;
    }

    public String toCuesJson(List<Cue> cues) {
        try {
            return objectMapper.writeValueAsString(cues != null ? cues : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<Cue> fromCuesJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Cue>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public static String formatMmSs(int totalSeconds) {
        int safe = Math.max(0, totalSeconds);
        return String.format("%02d:%02d", safe / 60, safe % 60);
    }

    private static List<Cue> synthesizeCuesFromPlainText(String plainText, int durationSeconds) {
        if (plainText == null || plainText.isBlank()) return List.of();
        String[] words = plainText.trim().split("\\s+");
        int stepWords = 60;
        int stepCount = Math.max(1, (int) Math.ceil(words.length / (double) stepWords));
        int secPerStep = Math.max(30, durationSeconds / stepCount);
        List<Cue> cues = new ArrayList<>();
        for (int s = 0; s < stepCount; s++) {
            int from = s * stepWords, to = Math.min(words.length, (s + 1) * stepWords);
            StringBuilder part = new StringBuilder();
            for (int w = from; w < to; w++) {
                if (!part.isEmpty()) part.append(' ');
                part.append(words[w]);
            }
            int start = Math.max(0, Math.min(durationSeconds - 5, s * secPerStep));
            int end = Math.max(0, Math.min(durationSeconds, (s + 1) * secPerStep));
            cues.add(new Cue(start, end, part.toString()));
        }
        return cues;
    }

    private static String stripFormatting(String raw) {
        String noTags = INLINE_TAGS.matcher(raw).replaceAll(" ");
        String noMusic = MUSIC_APPLAUSE.matcher(noTags).replaceAll(" ");
        return noMusic.replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stripRollingOverlap(String prev, String curr) {
        if (prev == null || prev.isEmpty()) return curr;
        if (curr.equalsIgnoreCase(prev)) return "";
        if (curr.startsWith(prev + " ")) return curr.substring(prev.length()).trim();
        String[] prevWords = prev.split("\\s+"), currWords = curr.split("\\s+");
        int maxOverlap = Math.min(prevWords.length, currWords.length);
        for (int k = maxOverlap; k >= 3; k--) {
            boolean match = true;
            for (int j = 0; j < k; j++) {
                if (!prevWords[prevWords.length - k + j].equalsIgnoreCase(currWords[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                StringBuilder rest = new StringBuilder();
                for (int j = k; j < currWords.length; j++) {
                    if (!rest.isEmpty()) rest.append(' ');
                    rest.append(currWords[j]);
                }
                return rest.toString();
            }
        }
        return curr;
    }

    private static int parseTimestampToSeconds(String raw) {
        String[] parts = raw.trim().replace(',', '.').split(":");
        double secs = (parts.length == 3)
                ? Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Double.parseDouble(parts[2])
                : (parts.length == 2 ? Integer.parseInt(parts[0]) * 60 + Double.parseDouble(parts[1]) : 0);
        return (int) Math.floor(secs);
    }

    private static int countWords(String text) {
        return (text == null || text.isBlank()) ? 0 : text.trim().split("\\s+").length;
    }
}
