// Extracts and repairs potentially truncated JSON responses and formats stem avoidance notes for Gemini retries.
package com.arabicquest.service;

import com.arabicquest.model.OpenQuestion;
import com.arabicquest.model.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class GeminiJsonRepairSupport {

    private static final int MAX_AVOID_STEMS = 25;

    private record CloseCheckpoint(int endIndex, List<Character> openStackSnapshot) {}

    private record JsonScanState(
            boolean inString,
            int openQuoteStart,
            int lastSafeSep,
            List<Character> openStack,
            List<CloseCheckpoint> shallowCheckpoints,
            List<CloseCheckpoint> deepCheckpoints) {}

    private GeminiJsonRepairSupport() {
    }

    public static String stripCodeFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    public static JsonNode parseLenientJson(ObjectMapper mapper, String raw) throws Exception {
        String clean = stripCodeFences(raw);
        if (clean.isEmpty()) throw new IllegalStateException("Gemini returned empty JSON output.");
        int obj = clean.indexOf('{'), arr = clean.indexOf('[');
        int start = obj < 0 ? Math.max(0, arr) : (arr < 0 ? obj : Math.min(obj, arr));
        String sliced = start > 0 ? clean.substring(start).trim() : clean;
        try {
            return mapper.readTree(sliced);
        } catch (Exception firstEx) {
            for (String candidate : buildRepairCandidates(sliced)) {
                try {
                    return mapper.readTree(candidate);
                } catch (Exception ignored) {
                }
            }
            throw firstEx;
        }
    }

    public static String buildExistingConceptAvoidanceNote(
            List<Question> existingMcqs,
            List<OpenQuestion> existingOpens) {
        List<String> lines = new ArrayList<>();
        if (existingMcqs != null) {
            for (Question q : existingMcqs) {
                if (q.getQuestionText() != null && !q.getQuestionText().isBlank()) {
                    lines.add("- " + q.getQuestionText().trim());
                    if (lines.size() >= MAX_AVOID_STEMS) break;
                }
            }
        }
        if (existingOpens != null) {
            int openAdded = 0;
            for (OpenQuestion oq : existingOpens) {
                if (oq.getPromptText() != null && !oq.getPromptText().isBlank()) {
                    lines.add("- [Open] " + oq.getPromptText().trim());
                    if (++openAdded >= 6) break;
                }
            }
        }
        return lines.isEmpty() ? "" : "\nAvoid duplicating or rephrasing ANY of these existing questions/concepts already in the episode bank:\n"
                + String.join("\n", lines) + "\n";
    }

    private static List<String> buildRepairCandidates(String s) {
        List<String> out = new ArrayList<>();
        int lastBrace = s.lastIndexOf('}');
        if (s.startsWith("{") && lastBrace > 0 && lastBrace < s.length() - 1) {
            out.add(s.substring(0, lastBrace + 1));
        }
        JsonScanState scan = scanJson(s);
        for (int i = scan.shallowCheckpoints().size() - 1; i >= 0; i--) {
            CloseCheckpoint cp = scan.shallowCheckpoints().get(i);
            out.add(closeOpenDelimiters(s.substring(0, cp.endIndex()), cp.openStackSnapshot()));
        }
        String eofRepaired = repairAtEndOfStream(s);
        if (eofRepaired != null) out.add(eofRepaired);
        for (int i = scan.deepCheckpoints().size() - 1; i >= 0; i--) {
            CloseCheckpoint cp = scan.deepCheckpoints().get(i);
            out.add(closeOpenDelimiters(s.substring(0, cp.endIndex()), cp.openStackSnapshot()));
        }
        return out;
    }

    private static String repairAtEndOfStream(String s) {
        String trimmed = s.stripTrailing();
        if (trimmed.isEmpty()) return null;
        JsonScanState scan = scanJson(trimmed);
        String prefix = trimmed;
        if (scan.inString()) {
            prefix = rollbackOrCloseOpenString(trimmed, scan.openQuoteStart(), scan.lastSafeSep());
        } else {
            String stripped = stripTrailingCommaOrColon(trimmed);
            if (!stripped.equals(trimmed)) {
                prefix = stripped;
            } else if (endsWithIncompleteToken(trimmed) && scan.lastSafeSep() > 0) {
                prefix = stripTrailingCommaOrColon(trimmed.substring(0, scan.lastSafeSep()));
            }
        }
        String cleanPrefix = stripTrailingCommaOrColon(prefix);
        return closeOpenDelimiters(cleanPrefix, scanJson(cleanPrefix).openStack());
    }

    private static String rollbackOrCloseOpenString(String s, int openQuoteStart, int lastSep) {
        if (openQuoteStart > 0) {
            if (s.substring(0, openQuoteStart).stripTrailing().endsWith(":")) {
                String cleanBody = s.endsWith("\\") ? s.substring(0, s.length() - 1) : s;
                return cleanBody + "\"";
            }
            if (lastSep > 0) return stripTrailingCommaOrColon(s.substring(0, lastSep));
        }
        return openQuoteStart > 0 ? stripTrailingCommaOrColon(s.substring(0, openQuoteStart)) : s;
    }

    private static JsonScanState scanJson(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        List<CloseCheckpoint> shallow = new ArrayList<>();
        List<CloseCheckpoint> deep = new ArrayList<>();
        boolean inString = false, escaped = false;
        int openQuoteStart = -1, lastSep = -1;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                openQuoteStart = i;
            } else if (c == ',' || c == '{' || c == '[') {
                lastSep = (c == ',') ? i : i + 1;
                if (c != ',') stack.push(c);
            } else if (c == '}' || c == ']') {
                char expected = c == '}' ? '{' : '[';
                if (!stack.isEmpty() && stack.peek() == expected) {
                    stack.pop();
                    if (!stack.isEmpty() && stack.peek() == '[') {
                        CloseCheckpoint cp = new CloseCheckpoint(i + 1, new ArrayList<>(stack));
                        (stack.size() <= 2 ? shallow : deep).add(cp);
                    }
                }
            }
        }
        return new JsonScanState(inString, openQuoteStart, lastSep, new ArrayList<>(stack), shallow, deep);
    }

    private static boolean endsWithIncompleteToken(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        if (i < 0) return false;
        char c = s.charAt(i);
        return Character.isLetter(c) || c == '-' || c == '.';
    }

    private static String stripTrailingCommaOrColon(String s) {
        String out = s.stripTrailing();
        while (!out.isEmpty() && (out.endsWith(",") || out.endsWith(":"))) {
            if (out.endsWith(":")) {
                int sep = scanJson(out.substring(0, out.length() - 1)).lastSafeSep();
                out = (sep > 0 ? out.substring(0, sep) : out.substring(0, out.length() - 1)).stripTrailing();
            } else {
                out = out.substring(0, out.length() - 1).stripTrailing();
            }
        }
        return out;
    }

    private static String closeOpenDelimiters(String prefix, List<Character> remainingStack) {
        StringBuilder sb = new StringBuilder(prefix.stripTrailing());
        for (Character open : remainingStack) sb.append(open == '{' ? '}' : ']');
        return sb.toString();
    }
}
