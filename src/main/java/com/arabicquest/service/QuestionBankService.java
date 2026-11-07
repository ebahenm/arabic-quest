package com.arabicquest.service;

import com.arabicquest.dto.QuestionBankAdminResponse;
import com.arabicquest.dto.QuestionReportRequest;
import com.arabicquest.dto.UpsertOpenQuestionRequest;
import com.arabicquest.dto.UpsertQuestionRequest;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

@Service
public class QuestionBankService {

    private final EpisodeRepository episodeRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final QuestionReportRepository questionReportRepository;
    private final StudentQuestionExposureRepository exposureRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final UserRepository userRepository;
    private final WatchProgressService watchProgressService;
    private final SecureRandom secureRandom = new SecureRandom();

    public QuestionBankService(
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            QuestionReportRepository questionReportRepository,
            StudentQuestionExposureRepository exposureRepository,
            QuizAttemptRepository quizAttemptRepository,
            UserRepository userRepository,
            WatchProgressService watchProgressService) {
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.questionReportRepository = questionReportRepository;
        this.exposureRepository = exposureRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userRepository = userRepository;
        this.watchProgressService = watchProgressService;
    }

    @Transactional
    public Map<String, Object> reportQuestion(String studentUsername, Long questionId, QuestionReportRequest req) {
        User student = userRepository.findByUsername(studentUsername)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Question question = questionRepository.findByIdWithQuizAndEpisode(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));
        Episode episode = question.getQuiz().getEpisode();

        boolean hasFailedAttempt = quizAttemptRepository
                .findTopByStudentIdAndEpisodeIdAndPassedFalseOrderByAttemptedAtDesc(student.getId(), episode.getId())
                .isPresent();
        if (!hasFailedAttempt) {
            throw new IllegalArgumentException("Questions can only be reported after a failed quiz attempt on that episode.");
        }

        boolean wasExposed = exposureRepository.existsByStudentIdAndQuestionId(student.getId(), questionId);
        if (!wasExposed) {
            throw new IllegalArgumentException("You can only report questions that appeared in your quiz attempt.");
        }

        if (questionReportRepository.existsByStudentIdAndQuestionIdAndResolvedFalse(student.getId(), questionId)) {
            return Map.of(
                    "reported", true,
                    "reportCount", question.getReportCount(),
                    "message", "You have already reported this question. The admin will review it."
            );
        }

        String cleanReason = (req != null && req.reason() != null && !req.reason().isBlank())
                ? req.reason().trim()
                : null;

        question.setReportCount(question.getReportCount() + 1);
        questionRepository.save(question);
        questionReportRepository.save(new QuestionReport(student, episode, question, cleanReason));

        return Map.of(
                "reported", true,
                "reportCount", question.getReportCount(),
                "message", "Question reported to admin. If edited or retired, your retry cooldown will reset immediately."
        );
    }

    @Transactional(readOnly = true)
    public QuestionBankAdminResponse getEpisodeQuestionBank(
            Long episodeId,
            String statusFilter,
            String typeFilter,
            boolean reportedOnly) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        List<Question> allQuestions = questionRepository.findByEpisodeIdWithOptions(episodeId);
        List<OpenQuestion> allOpens = openQuestionRepository.findByEpisodeIdOrderByIdAsc(episodeId);

        int approvedMcq = 0;
        int draftMcq = 0;
        int retiredMcq = 0;
        for (Question q : allQuestions) {
            if (q.getStatus() == QuestionStatus.APPROVED && q.isActive()) approvedMcq++;
            else if (q.getStatus() == QuestionStatus.DRAFT) draftMcq++;
            else if (q.getStatus() == QuestionStatus.RETIRED || !q.isActive()) retiredMcq++;
        }

        int approvedOpen = 0;
        int draftOpen = 0;
        for (OpenQuestion oq : allOpens) {
            if (oq.getStatus() == QuestionStatus.APPROVED && oq.isActive()) approvedOpen++;
            else if (oq.getStatus() == QuestionStatus.DRAFT) draftOpen++;
        }

        QuestionStatus targetStatus = parseStatusOrNull(statusFilter);
        QuestionType targetType = parseTypeOrNull(typeFilter);

        List<QuestionBankAdminResponse.McqAdminItem> filteredQuestions = allQuestions.stream()
                .filter(q -> targetStatus == null || q.getStatus() == targetStatus)
                .filter(q -> targetType == null || q.getType() == targetType)
                .filter(q -> !reportedOnly || q.getReportCount() > 0)
                .map(QuestionBankAdminResponse.McqAdminItem::from)
                .toList();

        List<QuestionBankAdminResponse.OpenQuestionAdminItem> filteredOpens = allOpens.stream()
                .filter(oq -> targetStatus == null || oq.getStatus() == targetStatus)
                .map(QuestionBankAdminResponse.OpenQuestionAdminItem::from)
                .toList();

        boolean ready = approvedMcq >= QuizService.SESSION_QUESTION_COUNT && approvedOpen >= 1;

        return new QuestionBankAdminResponse(
                episode.getId(),
                episode.getTitle(),
                episode.getTranscriptText() != null && !episode.getTranscriptText().isBlank(),
                approvedMcq,
                draftMcq,
                retiredMcq,
                approvedOpen,
                draftOpen,
                ready,
                filteredQuestions,
                filteredOpens
        );
    }

    @Transactional
    public QuestionBankAdminResponse.McqAdminItem createMcq(Long episodeId, UpsertQuestionRequest req) {
        validateMcqRequest(req);
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        Quiz quiz = quizRepository.findByEpisodeId(episodeId)
                .orElseGet(() -> quizRepository.save(new Quiz(episode)));
        quiz.setPassingScorePercentage(100.0);

        int nextOrder = quiz.getQuestions().size() + 1;
        Question q = new Question(quiz, req.questionText().trim(), nextOrder);
        q.setType(parseTypeOrDefault(req.type()));
        q.setStatus(req.status() != null ? parseStatusOrDefault(req.status(), QuestionStatus.APPROVED) : QuestionStatus.APPROVED);
        q.setActive(req.active() == null || req.active());
        q.setEvidence(req.evidence() != null ? req.evidence().trim() : null);
        q.setReportCount(0);

        List<UpsertQuestionRequest.OptionInput> shuffled = new ArrayList<>(req.options());
        Collections.shuffle(shuffled, secureRandom);
        for (UpsertQuestionRequest.OptionInput oi : shuffled) {
            q.getOptions().add(new QuestionOption(q, oi.optionText().trim(), Boolean.TRUE.equals(oi.isCorrect())));
        }

        Question saved = questionRepository.save(q);
        return QuestionBankAdminResponse.McqAdminItem.from(saved);
    }

    @Transactional
    public QuestionBankAdminResponse.McqAdminItem updateMcq(Long questionId, UpsertQuestionRequest req) {
        validateMcqRequest(req);
        Question q = questionRepository.findByIdWithQuizAndEpisode(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        q.setQuestionText(req.questionText().trim());
        if (req.type() != null) {
            q.setType(parseTypeOrDefault(req.type()));
        }
        if (req.status() != null) {
            q.setStatus(parseStatusOrDefault(req.status(), q.getStatus()));
        }
        if (req.active() != null) {
            q.setActive(req.active());
        }
        if (req.evidence() != null) {
            q.setEvidence(req.evidence().trim());
        }

        q.getOptions().clear();
        List<UpsertQuestionRequest.OptionInput> shuffled = new ArrayList<>(req.options());
        Collections.shuffle(shuffled, secureRandom);
        for (UpsertQuestionRequest.OptionInput oi : shuffled) {
            q.getOptions().add(new QuestionOption(q, oi.optionText().trim(), Boolean.TRUE.equals(oi.isCorrect())));
        }

        Question saved = questionRepository.save(q);
        resolveReportsAndResetStudentCooldowns(saved);
        return QuestionBankAdminResponse.McqAdminItem.from(saved);
    }

    @Transactional
    public QuestionBankAdminResponse.McqAdminItem approveMcq(Long questionId) {
        Question q = questionRepository.findByIdWithQuizAndEpisode(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));
        q.setStatus(QuestionStatus.APPROVED);
        q.setActive(true);
        return QuestionBankAdminResponse.McqAdminItem.from(questionRepository.save(q));
    }

    @Transactional
    public QuestionBankAdminResponse.McqAdminItem retireMcq(Long questionId) {
        Question q = questionRepository.findByIdWithQuizAndEpisode(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));
        q.setStatus(QuestionStatus.RETIRED);
        q.setActive(false);
        Question saved = questionRepository.save(q);
        resolveReportsAndResetStudentCooldowns(saved);
        return QuestionBankAdminResponse.McqAdminItem.from(saved);
    }

    @Transactional
    public Map<String, Integer> bulkApproveDrafts(Long episodeId) {
        List<Question> questions = questionRepository.findByEpisodeIdWithOptions(episodeId);
        int approvedMcqs = 0;
        for (Question q : questions) {
            if (q.getStatus() == QuestionStatus.DRAFT) {
                q.setStatus(QuestionStatus.APPROVED);
                q.setActive(true);
                questionRepository.save(q);
                approvedMcqs++;
            }
        }

        List<OpenQuestion> opens = openQuestionRepository.findByEpisodeIdOrderByIdAsc(episodeId);
        int approvedOpens = 0;
        for (OpenQuestion oq : opens) {
            if (oq.getStatus() == QuestionStatus.DRAFT) {
                oq.setStatus(QuestionStatus.APPROVED);
                oq.setActive(true);
                openQuestionRepository.save(oq);
                approvedOpens++;
            }
        }

        return Map.of(
                "approvedMcqs", approvedMcqs,
                "approvedOpenQuestions", approvedOpens
        );
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

    private void resolveReportsAndResetStudentCooldowns(Question question) {
        List<QuestionReport> unresolved = questionReportRepository.findUnresolvedByQuestionId(question.getId());
        for (QuestionReport report : unresolved) {
            report.setResolved(true);
            questionReportRepository.save(report);
            watchProgressService.resetRetryCooldownForStudent(report.getStudent(), report.getEpisode());
        }
        if (!unresolved.isEmpty()) {
            question.setReportCount(0);
            questionRepository.save(question);
        }
    }

    private void validateMcqRequest(UpsertQuestionRequest req) {
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

    private static QuestionStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) return null;
        try {
            return QuestionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static QuestionStatus parseStatusOrDefault(String raw, QuestionStatus fallback) {
        QuestionStatus s = parseStatusOrNull(raw);
        return s != null ? s : fallback;
    }

    private static QuestionType parseTypeOrNull(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) return null;
        try {
            return QuestionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static QuestionType parseTypeOrDefault(String raw) {
        QuestionType t = parseTypeOrNull(raw);
        return t != null ? t : QuestionType.RECALL;
    }
}
