// Parses structured Arabic/English written articles into timed sections with headings, answers, takeaways, and references.
package com.arabicquest.service;

import com.arabicquest.service.TranscriptParserService.Cue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WrittenTextSourceParser {

    public static final int MAX_TEXT_SOURCE_BYTES = 1024 * 1024; // 1 MB

    private static final Pattern YT_T_PARAM = Pattern.compile(
            "(?:[?&#]|\\b)t=(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?(?=\\b|&|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HMS_PATTERN = Pattern.compile("(?:\\[|\\()?\\b(\\d{1,2}):(\\d{2}):(\\d{2})\\b(?:]|\\))?");
    private static final Pattern MS_PATTERN = Pattern.compile("(?:\\[|\\()?\\b(\\d{1,3}):(\\d{2})\\b(?:]|\\))?");
    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern QUESTION_HEADING = Pattern.compile(
            "^(?:السؤال\\s+[^:：\\-]{1,20}|س\\s*\\d+|Q\\s*\\d+|Section\\s+\\d+)\\s*[:：\\-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[-=*_]{3,}$");

    private WrittenTextSourceParser() {
    }

    public record ParsedSection(
            int index, String heading, int startSeconds, int endSeconds,
            String shortAnswer, String detailedAnswer, String keyTakeaways,
            String references, String fullBody, int wordCount) {}

    public record ParsedWrittenSource(
            List<ParsedSection> sections, List<Cue> cues, String plainText, int wordCount) {}

    public static String decodeStrictUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Text source file is empty.");
        if (bytes.length > MAX_TEXT_SOURCE_BYTES) throw new IllegalArgumentException("Text source exceeds maximum size of 1 MB.");
        for (byte b : bytes) {
            if (b == 0) throw new IllegalArgumentException("Binary file rejected: text source must be valid UTF-8 text.");
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String text = decoded.toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid file encoding: text source must be UTF-8 encoded.");
        }
    }

    public static Integer parseStartTimeSeconds(String line) {
        if (line == null || line.isBlank()) return null;
        Matcher yt = YT_T_PARAM.matcher(line);
        if (yt.find()) {
            String h = yt.group(1), m = yt.group(2), s = yt.group(3);
            if (h != null || m != null || s != null) {
                int hrs = h != null ? Integer.parseInt(h) : 0;
                int mins = m != null ? Integer.parseInt(m) : 0;
                int secs = s != null ? Integer.parseInt(s) : 0;
                return hrs * 3600 + mins * 60 + secs;
            }
        }
        Matcher hms = HMS_PATTERN.matcher(line);
        if (hms.find()) {
            int hrs = Integer.parseInt(hms.group(1)), mins = Integer.parseInt(hms.group(2)), secs = Integer.parseInt(hms.group(3));
            if (mins < 60 && secs < 60) return hrs * 3600 + mins * 60 + secs;
        }
        Matcher ms = MS_PATTERN.matcher(line);
        if (ms.find()) {
            int mins = Integer.parseInt(ms.group(1)), secs = Integer.parseInt(ms.group(2));
            if (secs < 60) return mins * 60 + secs;
        }
        return null;
    }

    public static ParsedWrittenSource parse(String rawText, int episodeDurationSeconds) {
        if (rawText == null || rawText.isBlank()) throw new IllegalArgumentException("Written text source cannot be empty.");
        if (rawText.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_SOURCE_BYTES) {
            throw new IllegalArgumentException("Written text source exceeds maximum size of 1 MB.");
        }
        String[] lines = rawText.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<RawSectionBuilder> builders = new ArrayList<>();
        RawSectionBuilder current = null;
        boolean afterSeparator = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (SEPARATOR_LINE.matcher(trimmed).matches()) {
                afterSeparator = true;
                continue;
            }
            String subKind = detectSubBlockLabel(trimmed);
            if (subKind != null && current != null) {
                current.activeBlock = subKind;
                String remainder = stripSubBlockLabel(trimmed);
                if (!remainder.isEmpty()) current.appendToActive(remainder);
                continue;
            }
            boolean isNewHeading = isSectionHeadingLine(trimmed, lines, i, afterSeparator, current == null);
            afterSeparator = false;
            if (isNewHeading) {
                current = new RawSectionBuilder(cleanHeadingText(trimmed));
                Integer inlineTs = parseStartTimeSeconds(trimmed);
                if (inlineTs != null) current.startSeconds = inlineTs;
                builders.add(current);
                continue;
            }
            if (current == null) {
                current = new RawSectionBuilder("Section 1");
                builders.add(current);
            }
            if (current.startSeconds == null && isTimestampMetaLine(trimmed)) {
                Integer ts = parseStartTimeSeconds(trimmed);
                if (ts != null) {
                    current.startSeconds = ts;
                    continue;
                }
            }
            current.appendToActive(trimmed);
        }

        if (builders.isEmpty()) throw new IllegalArgumentException("No readable sections found in written text source.");

        int safeDuration = Math.max(60, episodeDurationSeconds > 0 ? episodeDurationSeconds : 1800);
        int prevStart = 0;
        for (int idx = 0; idx < builders.size(); idx++) {
            RawSectionBuilder b = builders.get(idx);
            if (b.startSeconds == null) b.startSeconds = (idx == 0) ? 0 : prevStart + 60;
            prevStart = b.startSeconds;
        }

        List<ParsedSection> sections = new ArrayList<>(builders.size());
        List<Cue> cues = new ArrayList<>(builders.size());
        StringBuilder plainAll = new StringBuilder();

        for (int idx = 0; idx < builders.size(); idx++) {
            RawSectionBuilder b = builders.get(idx);
            int startSec = Math.max(0, b.startSeconds);
            int nextStart = (idx + 1 < builders.size()) ? builders.get(idx + 1).startSeconds : Math.max(startSec + 60, safeDuration);
            int endSec = Math.max(startSec + 1, nextStart);
            String body = b.buildStructuredBody();
            int wc = countWords(b.heading + " " + body);
            sections.add(new ParsedSection(idx, b.heading, startSec, endSec,
                    b.shortAnswer.toString().trim(), b.detailedAnswer.toString().trim(),
                    b.keyTakeaways.toString().trim(), b.references.toString().trim(), body, wc));
            cues.add(new Cue(startSec, endSec, body));
            if (!plainAll.isEmpty()) plainAll.append("\n\n");
            plainAll.append(body);
        }
        String plain = plainAll.toString().trim();
        return new ParsedWrittenSource(sections, cues, plain, countWords(plain));
    }

    private static boolean isSectionHeadingLine(String trimmed, String[] lines, int idx, boolean afterSeparator, boolean isFirst) {
        if (MD_HEADING.matcher(trimmed).matches() || QUESTION_HEADING.matcher(trimmed).matches()) return true;
        if (afterSeparator && trimmed.length() <= 160 && parseStartTimeSeconds(trimmed) == null) return true;
        if (trimmed.length() <= 160 && !isTimestampMetaLine(trimmed)) {
            for (int j = idx + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty()) continue;
                return isTimestampMetaLine(next);
            }
        }
        return isFirst && trimmed.length() <= 140 && !isTimestampMetaLine(trimmed);
    }

    private static boolean isTimestampMetaLine(String trimmed) {
        if (parseStartTimeSeconds(trimmed) == null) return false;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains("youtu.be") || lower.contains("youtube.com") || trimmed.length() <= 60
                || lower.startsWith("start") || lower.startsWith("time") || trimmed.startsWith("الوقت") || trimmed.startsWith("التوقيت");
    }

    private static String detectSubBlockLabel(String line) {
        String clean = line.replaceAll("^[#*\\s]+|[#*:\\-：\\s]+$", "").trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("الإجابة المختصرة") || clean.startsWith("الاجابة المختصرة") || clean.startsWith("الإجابة السريعة")
                || clean.startsWith("ملخص الإجابة") || clean.startsWith("short answer") || clean.startsWith("opening point")) return "SHORT";
        if (clean.startsWith("الإجابة التفصيلية") || clean.startsWith("الاجابة التفصيلية") || clean.startsWith("الشرح التفصيلي")
                || clean.startsWith("التفصيل") || clean.startsWith("detailed answer") || clean.startsWith("details")) return "DETAILED";
        if (clean.startsWith("أبرز الخلاصات") || clean.startsWith("ابرز الخلاصات") || clean.startsWith("الخلاصات")
                || clean.startsWith("الخلاصة") || clean.startsWith("key takeaways") || clean.startsWith("takeaways")) return "TAKEAWAYS";
        if (clean.startsWith("المراجع المذكورة") || clean.startsWith("المراجع") || clean.startsWith("المصادر")
                || clean.startsWith("الكتب") || clean.startsWith("references") || clean.startsWith("sources") || clean.startsWith("books")) return "REFERENCES";
        return null;
    }

    private static String stripSubBlockLabel(String line) {
        int colon = Math.max(line.indexOf(':'), line.indexOf('：'));
        return (colon >= 0 && colon < 45) ? line.substring(colon + 1).replaceAll("^[*\\s]+", "").trim() : "";
    }

    private static String cleanHeadingText(String line) {
        return line.replaceFirst("^#{1,6}\\s+", "").replaceAll("\\*\\*", "").trim();
    }

    private static int countWords(String text) {
        return (text == null || text.isBlank()) ? 0 : text.trim().split("\\s+").length;
    }

    private static final class RawSectionBuilder {
        final String heading;
        Integer startSeconds;
        String activeBlock = "SHORT";
        final StringBuilder shortAnswer = new StringBuilder();
        final StringBuilder detailedAnswer = new StringBuilder();
        final StringBuilder keyTakeaways = new StringBuilder();
        final StringBuilder references = new StringBuilder();

        RawSectionBuilder(String heading) { this.heading = heading; }

        void appendToActive(String text) {
            StringBuilder target = switch (activeBlock) {
                case "DETAILED" -> detailedAnswer;
                case "TAKEAWAYS" -> keyTakeaways;
                case "REFERENCES" -> references;
                default -> shortAnswer;
            };
            if (!target.isEmpty()) target.append('\n');
            target.append(text);
        }

        String buildStructuredBody() {
            StringBuilder sb = new StringBuilder();
            sb.append("Heading: ").append(heading);
            if (!shortAnswer.isEmpty()) sb.append("\nShort Answer: ").append(shortAnswer.toString().trim());
            if (!detailedAnswer.isEmpty()) sb.append("\nDetailed Answer: ").append(detailedAnswer.toString().trim());
            if (!keyTakeaways.isEmpty()) sb.append("\nKey Takeaways: ").append(keyTakeaways.toString().trim());
            if (!references.isEmpty()) sb.append("\nReferences: ").append(references.toString().trim());
            return sb.toString();
        }
    }
}
