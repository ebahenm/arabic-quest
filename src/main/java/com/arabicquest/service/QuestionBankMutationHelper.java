// Helper component for open-question CRUD and MCQ validation in QuestionBankService.
package com.arabicquest.service;

import com.arabicquest.dto.QuestionBankAdminResponse;
import com.arabicquest.dto.UpsertOpenQuestionRequest;
import com.arabicquest.dto.UpsertQuestionRequest;
import com.arabicquest.model.*;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.OpenQuestionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
public class QuestionBankMutationHelper {

    private final EpisodeRepository episodeRepository;
    private final OpenQuestionRepository openQuestionRepository;

    public QuestionBankMutationHelper(
            EpisodeRepository episodeRepository,
            OpenQuestionRepository openQuestionRepository) {
        this.episodeRepository = episodeRepository;
        this.openQuestionRepository = openQuestionRepository;
    }

    @Transactional
    public QuestionBankAdminResponse.OpenQuestionAdminItem createOpenQuestion(Long episodeId, UpsertOpenQuestionRequest req) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        QuestionStatus status = req.status() != null
                ? parseStatusOrDefault(req.status(), QuestionStatus.APPROVED)
                : QuestionStatus.APPROVED;
        OpenQuestion oq = new OpenQuestion(
                episode,
                req.promptText().trim(),
                req.evidence() != null ? req.evidence().trim() : null,
                req.evidenceTimestamp(),
                status
        );
        if (req.active() != null) {
            oq.setActive(req.active());
        }
        return QuestionBankAdminResponse.OpenQuestionAdminItem.from(openQuestionRepository.save(oq));
    }

    @Transactional
    public QuestionBankAdminResponse.OpenQuestionAdminItem updateOpenQuestion(Long openQuestionId, UpsertOpenQuestionRequest req) {
        OpenQuestion oq = openQuestionRepository.findById(openQuestionId)
                .orElseThrow(() -> new IllegalArgumentException("Open question not found"));
        oq.setPromptText(req.promptText().trim());
        if (req.evidence() != null) {
            oq.setEvidence(req.evidence().trim());
        }
        if (req.evidenceTimestamp() != null) {
            oq.setEvidenceTimestamp(req.evidenceTimestamp());
        }
        if (req.status() != null) {
            oq.setStatus(parseStatusOrDefault(req.status(), oq.getStatus()));
        }
        if (req.active() != null) {
            oq.setActive(req.active());
        }
        return QuestionBankAdminResponse.OpenQuestionAdminItem.from(openQuestionRepository.save(oq));
    }

    @Transactional
    public QuestionBankAdminResponse.OpenQuestionAdminItem approveOpenQuestion(Long openQuestionId) {
        OpenQuestion oq = openQuestionRepository.findById(openQuestionId)
                .orElseThrow(() -> new IllegalArgumentException("Open question not found"));
        oq.setStatus(QuestionStatus.APPROVED);
        oq.setActive(true);
        if ("AUTO".equalsIgnoreCase(oq.getSource())) {
            oq.setSource("ADMIN_REVIEWED");
        }
        return QuestionBankAdminResponse.OpenQuestionAdminItem.from(openQuestionRepository.save(oq));
    }

    @Transactional
    public QuestionBankAdminResponse.OpenQuestionAdminItem retireOpenQuestion(Long openQuestionId) {
        OpenQuestion oq = openQuestionRepository.findById(openQuestionId)
                .orElseThrow(() -> new IllegalArgumentException("Open question not found"));
        oq.setStatus(QuestionStatus.RETIRED);
        oq.setActive(false);
        return QuestionBankAdminResponse.OpenQuestionAdminItem.from(openQuestionRepository.save(oq));
    }

    public static void validateMcqRequest(UpsertQuestionRequest req) {
        if (req.options() == null || req.options().size() != 4) {
            throw new IllegalArgumentException("Each multiple-choice question must have exactly 4 options.");
        }
        int correct = 0;
        for (UpsertQuestionRequest.OptionInput oi : req.options()) {
            String t = oi.optionText() == null ? "" : oi.optionText().trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                throw new IllegalArgumentException("Option text must not be blank.");
            }
            if (t.contains("all of the above") || t.contains("none of the above")) {
                throw new IllegalArgumentException("Options must not use 'all of the above' or 'none of the above'.");
            }
            if (Boolean.TRUE.equals(oi.isCorrect())) {
                correct++;
            }
        }
        if (correct != 1) {
            throw new IllegalArgumentException("Each multiple-choice question must have exactly 1 correct option.");
        }
    }

    public static QuestionStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim()) || "NEEDS_REVIEW".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return QuestionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static QuestionStatus parseStatusOrDefault(String raw, QuestionStatus fallback) {
        QuestionStatus s = parseStatusOrNull(raw);
        return s != null ? s : fallback;
    }

    public static QuestionType parseTypeOrNull(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) return null;
        try {
            return QuestionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static QuestionType parseTypeOrDefault(String raw) {
        QuestionType t = parseTypeOrNull(raw);
        return t != null ? t : QuestionType.RECALL;
    }
}
