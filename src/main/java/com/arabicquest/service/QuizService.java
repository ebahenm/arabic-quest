package com.arabicquest.service;

import com.arabicquest.config.InvalidQuizSessionException;
import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class QuizService {

    public static final int SESSION_QUESTION_COUNT = 10;
    public static final long SESSION_TTL_MINUTES = 60L;

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final QuizAttemptSessionRepository quizAttemptSessionRepository;
    private final StudentQuestionExposureRepository exposureRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EpisodeRepository episodeRepository;
    private final UserRepository userRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final WatchProgressService watchProgressService;
    private final OpenAnswerReviewService openAnswerReviewService;
    private final SecureRandom secureRandom = new SecureRandom();

    public QuizService(
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            QuizAttemptSessionRepository quizAttemptSessionRepository,
            StudentQuestionExposureRepository exposureRepository,
            QuizAttemptRepository quizAttemptRepository,
            EpisodeRepository episodeRepository,
            UserRepository userRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            WatchProgressService watchProgressService,
            OpenAnswerReviewService openAnswerReviewService) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.quizAttemptSessionRepository = quizAttemptSessionRepository;
        this.exposureRepository = exposureRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.episodeRepository = episodeRepository;
        this.userRepository = userRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.watchProgressService = watchProgressService;
        this.openAnswerReviewService = openAnswerReviewService;
    }

    @Transactional
    public QuizViewResponse getStudentQuizView(String username, Long episodeId, boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + episodeId));
        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode not found");
        }

        watchProgressService.assertEpisodeUnlocked(student, episode, betaUnlockRequested);

        Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElse(null);
        if (quiz == null) {
            return QuizViewResponse.notReady(episodeId, "Quiz not ready yet");
        }

        List<Question> allQuestions = questionRepository.findByEpisodeIdWithOptions(episodeId);
        List<Question> approvedQuestions = allQuestions.stream()
                .filter(q -> q.isActive() && q.getStatus() == QuestionStatus.APPROVED)
                .toList();

        long approvedOpenCount = openQuestionRepository.countByEpisodeIdAndStatusAndActiveTrue(
                episodeId, QuestionStatus.APPROVED);

        if (approvedQuestions.size() < SESSION_QUESTION_COUNT || approvedOpenCount < 1) {
            return QuizViewResponse.notReady(episodeId, "Quiz not ready yet");
        }

        Map<Long, Question> approvedById = new HashMap<>();
        for (Question q : approvedQuestions) {
            approvedById.put(q.getId(), q);
        }

        LocalDateTime now = LocalDateTime.now();
        QuizAttemptSession activeSession = quizAttemptSessionRepository
                .findTopByStudentIdAndEpisodeIdAndStatusOrderByCreatedAtDesc(
                        student.getId(), episodeId, QuizSessionStatus.OPEN)
                .orElse(null);

        if (activeSession != null) {
            List<Long> storedIds = activeSession.getQuestionIds();
            boolean expired = now.isAfter(activeSession.getExpiresAt());
            boolean allStillApproved = storedIds.size() == SESSION_QUESTION_COUNT
                    && storedIds.stream().allMatch(approvedById::containsKey);

            if (expired || !allStillApproved) {
                activeSession.setStatus(QuizSessionStatus.EXPIRED);
                quizAttemptSessionRepository.save(activeSession);
                activeSession = null;
            }
        }

        if (activeSession == null) {
            List<Question> selected10 = selectTenQuestionsForAttempt(student, episode, approvedQuestions, now);
            List<Long> selectedIds = selected10.stream().map(Question::getId).toList();
            activeSession = quizAttemptSessionRepository.save(new QuizAttemptSession(
                    student,
                    episode,
                    selectedIds,
                    now,
                    now.plusMinutes(SESSION_TTL_MINUTES)
            ));
        }

        List<QuestionViewResponse> questionViews = new ArrayList<>();
        int displayOrder = 1;
        for (Long qId : activeSession.getQuestionIds()) {
            Question q = approvedById.get(qId);
            List<OptionViewResponse> optionViews = new ArrayList<>(
                    q.getOptions().stream()
                            .map(opt -> new OptionViewResponse(opt.getId(), opt.getOptionText()))
                            .toList()
            );
            long seed = UUID.nameUUIDFromBytes(
                    (activeSession.getId().toString() + ":" + q.getId()).getBytes(StandardCharsets.UTF_8)
            ).getMostSignificantBits();
            Collections.shuffle(optionViews, new Random(seed));
            questionViews.add(new QuestionViewResponse(q.getId(), q.getQuestionText(), displayOrder++, optionViews));
        }

        OpenQuestionStudentResponse openQuestionState = null;
        boolean alreadyPassedMcq = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episodeId)
                || episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episodeId);
        if (alreadyPassedMcq) {
            WatchMode effectiveMode = watchProgressService.resolveEffectiveWatchMode(
                    student.getId(), episodeId, WatchMode.ARABIC_SUB);
            openQuestionState = openAnswerReviewService.assignOrGetOpenQuestion(student, episode, effectiveMode);
        }

        return new QuizViewResponse(
                quiz.getId(),
                episodeId,
                activeSession.getId(),
                activeSession.getExpiresAt(),
                100.0,
                questionViews,
                openQuestionState
        );
    }

    private List<Question> selectTenQuestionsForAttempt(
            User student,
            Episode episode,
            List<Question> approvedQuestions,
            LocalDateTime now) {

        List<StudentQuestionExposure> exposures = exposureRepository
                .findByStudentIdAndEpisodeId(student.getId(), episode.getId());
        Map<Long, StudentQuestionExposure> exposureByQuestionId = new HashMap<>();
        for (StudentQuestionExposure exp : exposures) {
            exposureByQuestionId.put(exp.getQuestion().getId(), exp);
        }

        List<Question> shuffledPool = new ArrayList<>(approvedQuestions);
        Collections.shuffle(shuffledPool, secureRandom);

        List<Question> unseen = new ArrayList<>();
        List<Question> seen = new ArrayList<>();
        for (Question q : shuffledPool) {
            if (exposureByQuestionId.containsKey(q.getId())) {
                seen.add(q);
            } else {
                unseen.add(q);
            }
        }

        seen.sort(Comparator.comparing(q -> exposureByQuestionId.get(q.getId()).getLastShownAt()));

        List<Question> chosen = new ArrayList<>(SESSION_QUESTION_COUNT);
        for (Question q : unseen) {
            if (chosen.size() >= SESSION_QUESTION_COUNT) break;
            chosen.add(q);
        }
        for (Question q : seen) {
            if (chosen.size() >= SESSION_QUESTION_COUNT) break;
            chosen.add(q);
        }

        Collections.shuffle(chosen, secureRandom);

        for (Question q : chosen) {
            StudentQuestionExposure exp = exposureByQuestionId.get(q.getId());
            if (exp == null) {
                exp = new StudentQuestionExposure(student, episode, q, now);
            } else {
                exp.setLastShownAt(now);
                exp.setExposureCount(exp.getExposureCount() + 1);
            }
            exposureRepository.save(exp);
        }

        return chosen;
    }

    @Transactional(noRollbackFor = InvalidQuizSessionException.class)
    public QuizResultResponse submitQuiz(String username, QuizSubmissionRequest request) {
        return submitQuiz(username, request, false);
    }

    @Transactional(noRollbackFor = InvalidQuizSessionException.class)
    public QuizResultResponse submitQuiz(String username, QuizSubmissionRequest request, boolean betaUnlockRequested) {
        if (request == null || request.getSessionId() == null) {
            throw new InvalidQuizSessionException("Quiz session ID is required.");
        }

        User student = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        QuizAttemptSession session = quizAttemptSessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new InvalidQuizSessionException("Invalid or unknown quiz session."));

        if (!session.getStudent().getId().equals(student.getId())) {
            throw new SecurityException("This quiz session belongs to a different user.");
        }

        Long sessionEpisodeId = session.getEpisode().getId();
        if (request.getEpisodeId() != null && !request.getEpisodeId().equals(sessionEpisodeId)) {
            throw new InvalidQuizSessionException("Episode ID does not match the active quiz session.");
        }

        Episode episode = episodeRepository.findByIdWithPlaylist(sessionEpisodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode is archived and no longer accepting submissions");
        }

        if (session.getStatus() == QuizSessionStatus.SUBMITTED) {
            throw new InvalidQuizSessionException("This quiz session has already been submitted. Start a new session to retry.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (session.getStatus() == QuizSessionStatus.EXPIRED || now.isAfter(session.getExpiresAt())) {
            session.setStatus(QuizSessionStatus.EXPIRED);
            quizAttemptSessionRepository.save(session);
            throw new InvalidQuizSessionException("This quiz session has expired (60-minute limit). Refresh to draw a new session.");
        }

        watchProgressService.assertEpisodeUnlocked(student, episode, betaUnlockRequested);

        boolean alreadyPassedOrClaimed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())
                || quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());

        if (!alreadyPassedOrClaimed) {
            long cooldownRemaining = watchProgressService.getRetryCooldownRemainingSeconds(student.getId(), episode.getId());
            if (cooldownRemaining > 0) {
                long mins = cooldownRemaining / 60;
                long secs = cooldownRemaining % 60;
                throw new TooManyRequestsException(
                        String.format("Cooldown active after previous attempt. Please review the episode and retry in %dm %02ds.", mins, secs),
                        cooldownRemaining
                );
            }

            if (!watchProgressService.isQuizUnlockedForSubmission(student.getId(), episode)) {
                throw new SecurityException("Quiz is locked. Please complete the required watch progress before submitting.");
            }
        }

        List<Long> sessionQuestionIds = session.getQuestionIds();
        if (sessionQuestionIds.size() != SESSION_QUESTION_COUNT) {
            throw new InvalidQuizSessionException("Invalid session question set.");
        }
        Set<Long> expectedQuestionSet = new LinkedHashSet<>(sessionQuestionIds);

        Map<Long, Long> submittedAnswers = request.getAnswers();
        if (submittedAnswers == null || submittedAnswers.isEmpty()) {
            throw new InvalidQuizSessionException("All 10 questions in this session must be answered.");
        }

        for (Long answeredQuestionId : submittedAnswers.keySet()) {
            if (answeredQuestionId == null || !expectedQuestionSet.contains(answeredQuestionId)) {
                throw new InvalidQuizSessionException("Submission contains a question ID outside of this quiz session.");
            }
        }

        if (submittedAnswers.size() != SESSION_QUESTION_COUNT) {
            throw new InvalidQuizSessionException("Incomplete submission: all 10 questions in the session must be answered.");
        }

        List<Question> episodeQuestions = questionRepository.findByEpisodeIdWithOptions(episode.getId());
        Map<Long, Question> questionById = new HashMap<>();
        for (Question q : episodeQuestions) {
            questionById.put(q.getId(), q);
        }

        int correctCount = 0;
        for (Long qId : sessionQuestionIds) {
            Question question = questionById.get(qId);
            if (question == null) {
                throw new InvalidQuizSessionException("Session question no longer exists.");
            }
            Long selectedOptionId = submittedAnswers.get(qId);
            if (selectedOptionId == null) {
                throw new InvalidQuizSessionException("All 10 questions in the session must have a selected option.");
            }
            QuestionOption chosenOption = question.getOptions().stream()
                    .filter(opt -> opt.getId().equals(selectedOptionId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidQuizSessionException("Selected option does not belong to the session question."));
            if (Boolean.TRUE.equals(chosenOption.getIsCorrect())) {
                correctCount++;
            }
        }

        WatchMode effectiveWatchMode = watchProgressService.resolveEffectiveWatchMode(
                student.getId(), episode.getId(), request.getWatchMode());

        session.setStatus(QuizSessionStatus.SUBMITTED);
        session.setSubmittedAt(now);
        quizAttemptSessionRepository.save(session);

        double scorePercentage = ((double) correctCount / SESSION_QUESTION_COUNT) * 100.0;
        boolean passed = (correctCount == SESSION_QUESTION_COUNT);

        QuizAttempt attempt = new QuizAttempt(
                student,
                episode,
                effectiveWatchMode,
                scorePercentage,
                passed,
                BigDecimal.ZERO
        );
        quizAttemptRepository.save(attempt);

        if (passed) {
            OpenQuestionStudentResponse openQ = openAnswerReviewService.assignOrGetOpenQuestion(
                    student, episode, effectiveWatchMode);
            BigDecimal potentialRate = openAnswerReviewService.resolveEpisodeRewardRate(episode, effectiveWatchMode);
            boolean alreadyClaimed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId());
            String msg = alreadyClaimed
                    ? "Perfect 10/10 (100%)! You have already earned your reward for this episode."
                    : String.format("Perfect 10/10 (100%%)! Submit your written answer below to pass the episode and earn your +$%s reward.", potentialRate);

            return new QuizResultResponse(
                    scorePercentage,
                    correctCount,
                    SESSION_QUESTION_COUNT,
                    true,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0L,
                    msg,
                    openQ
            );
        } else {
            long cooldownSecs = watchProgressService.getConfiguredRetryCooldownSeconds();
            String msg = String.format(
                    "Score: %d/%d (%.0f%%). Pass mark is 100%% (10/10). Review the episode and retry with a fresh set of questions after cooldown.",
                    correctCount, SESSION_QUESTION_COUNT, scorePercentage
            );
            return new QuizResultResponse(
                    scorePercentage,
                    correctCount,
                    SESSION_QUESTION_COUNT,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    cooldownSecs,
                    msg,
                    null
            );
        }
    }
}
import com.arabicquest.config.InvalidQuizSessionException;
import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class QuizService {

    public static final int SESSION_QUESTION_COUNT = 10;
    public static final long SESSION_TTL_MINUTES = 60L;

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final QuizAttemptSessionRepository quizAttemptSessionRepository;
    private final StudentQuestionExposureRepository exposureRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EpisodeRepository episodeRepository;
    private final UserRepository userRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final WatchProgressService watchProgressService;
    private final OpenAnswerReviewService openAnswerReviewService;
    private final SecureRandom secureRandom = new SecureRandom();

    public QuizService(
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            QuizAttemptSessionRepository quizAttemptSessionRepository,
            StudentQuestionExposureRepository exposureRepository,
            QuizAttemptRepository quizAttemptRepository,
            EpisodeRepository episodeRepository,
            UserRepository userRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            WatchProgressService watchProgressService,
            OpenAnswerReviewService openAnswerReviewService) {
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.quizAttemptSessionRepository = quizAttemptSessionRepository;
        this.exposureRepository = exposureRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.episodeRepository = episodeRepository;
        this.userRepository = userRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.watchProgressService = watchProgressService;
        this.openAnswerReviewService = openAnswerReviewService;
    }

    @Transactional
    public QuizViewResponse getStudentQuizView(String username, Long episodeId, boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + episodeId));
        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode not found");
        }

        watchProgressService.assertEpisodeUnlocked(student, episode, betaUnlockRequested);

        Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElse(null);
        if (quiz == null) {
            return QuizViewResponse.notReady(episodeId, "Quiz not ready yet");
        }

        List<Question> allQuestions = questionRepository.findByEpisodeIdWithOptions(episodeId);
        List<Question> approvedQuestions = allQuestions.stream()
                .filter(q -> q.isActive() && q.getStatus() == QuestionStatus.APPROVED)
                .toList();

        long approvedOpenCount = openQuestionRepository.countByEpisodeIdAndStatusAndActiveTrue(
                episodeId, QuestionStatus.APPROVED);

        if (approvedQuestions.size() < SESSION_QUESTION_COUNT || approvedOpenCount < 1) {
            return QuizViewResponse.notReady(episodeId, "Quiz not ready yet");
        }

        Map<Long, Question> approvedById = new HashMap<>();
        for (Question q : approvedQuestions) {
            approvedById.put(q.getId(), q);
        }

        LocalDateTime now = LocalDateTime.now();
        QuizAttemptSession activeSession = quizAttemptSessionRepository
                .findTopByStudentIdAndEpisodeIdAndStatusOrderByCreatedAtDesc(
                        student.getId(), episodeId, QuizSessionStatus.OPEN)
                .orElse(null);

        if (activeSession != null) {
            List<Long> storedIds = activeSession.getQuestionIds();
            boolean expired = now.isAfter(activeSession.getExpiresAt());
            boolean allStillApproved = storedIds.size() == SESSION_QUESTION_COUNT
                    && storedIds.stream().allMatch(approvedById::containsKey);

            if (expired || !allStillApproved) {
                activeSession.setStatus(QuizSessionStatus.EXPIRED);
                quizAttemptSessionRepository.save(activeSession);
                activeSession = null;
            }
        }

        if (activeSession == null) {
            List<Question> selected10 = selectTenQuestionsForAttempt(student, episode, approvedQuestions, now);
            List<Long> selectedIds = selected10.stream().map(Question::getId).toList();
            activeSession = quizAttemptSessionRepository.save(new QuizAttemptSession(
                    student,
                    episode,
                    selectedIds,
                    now,
                    now.plusMinutes(SESSION_TTL_MINUTES)
            ));
        }

        List<QuestionViewResponse> questionViews = new ArrayList<>();
        int displayOrder = 1;
        for (Long qId : activeSession.getQuestionIds()) {
            Question q = approvedById.get(qId);
            List<OptionViewResponse> optionViews = new ArrayList<>(
                    q.getOptions().stream()
                            .map(opt -> new OptionViewResponse(opt.getId(), opt.getOptionText()))
                            .toList()
            );
            // Deterministic shuffle tied to (sessionId, questionId) so page refreshes never reroll options
            long seed = UUID.nameUUIDFromBytes(
                    (activeSession.getId().toString() + ":" + q.getId()).getBytes(StandardCharsets.UTF_8)
            ).getMostSignificantBits();
            Collections.shuffle(optionViews, new Random(seed));
            questionViews.add(new QuestionViewResponse(q.getId(), q.getQuestionText(), displayOrder++, optionViews));
        }

        OpenQuestionStudentResponse openQuestionState = null;
        boolean alreadyPassedMcq = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episodeId)
                || episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episodeId);
        if (alreadyPassedMcq) {
            WatchMode effectiveMode = watchProgressService.resolveEffectiveWatchMode(
                    student.getId(), episodeId, WatchMode.ARABIC_SUB);
            openQuestionState = openAnswerReviewService.assignOrGetOpenQuestion(student, episode, effectiveMode);
        }

        return new QuizViewResponse(
                quiz.getId(),
                episodeId,
                activeSession.getId(),
                activeSession.getExpiresAt(),
                100.0,
                questionViews,
                openQuestionState
        );
    }

    private List<Question> selectTenQuestionsForAttempt(
            User student,
            Episode episode,
            List<Question> approvedQuestions,
            LocalDateTime now) {

        List<StudentQuestionExposure> exposures = exposureRepository
                .findByStudentIdAndEpisodeId(student.getId(), episode.getId());
        Map<Long, StudentQuestionExposure> exposureByQuestionId = new HashMap<>();
        for (StudentQuestionExposure exp : exposures) {
            exposureByQuestionId.put(exp.getQuestion().getId(), exp);
        }

        List<Question> shuffledPool = new ArrayList<>(approvedQuestions);
        Collections.shuffle(shuffledPool, secureRandom);

        List<Question> unseen = new ArrayList<>();
        List<Question> seen = new ArrayList<>();
        for (Question q : shuffledPool) {
            if (exposureByQuestionId.containsKey(q.getId())) {
                seen.add(q);
            } else {
                unseen.add(q);
            }
        }

        // Oldest-seen first when unseen pool has fewer than 10 remaining questions
        seen.sort(Comparator.comparing(q -> exposureByQuestionId.get(q.getId()).getLastShownAt()));

        List<Question> chosen = new ArrayList<>(SESSION_QUESTION_COUNT);
        for (Question q : unseen) {
            if (chosen.size() >= SESSION_QUESTION_COUNT) break;
            chosen.add(q);
        }
        for (Question q : seen) {
            if (chosen.size() >= SESSION_QUESTION_COUNT) break;
            chosen.add(q);
        }

        Collections.shuffle(chosen, secureRandom);

        for (Question q : chosen) {
            StudentQuestionExposure exp = exposureByQuestionId.get(q.getId());
            if (exp == null) {
                exp = new StudentQuestionExposure(student, episode, q, now);
            } else {
                exp.setLastShownAt(now);
                exp.setExposureCount(exp.getExposureCount() + 1);
            }
            exposureRepository.save(exp);
        }

        return chosen;
    }

    @Transactional(noRollbackFor = InvalidQuizSessionException.class)
    public QuizResultResponse submitQuiz(String username, QuizSubmissionRequest request) {
        return submitQuiz(username, request, false);
    }

    @Transactional(noRollbackFor = InvalidQuizSessionException.class)
    public QuizResultResponse submitQuiz(String username, QuizSubmissionRequest request, boolean betaUnlockRequested) {
        if (request == null || request.getSessionId() == null) {
            throw new InvalidQuizSessionException("Quiz session ID is required.");
        }

        User student = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        QuizAttemptSession session = quizAttemptSessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new InvalidQuizSessionException("Invalid or unknown quiz session."));

        if (!session.getStudent().getId().equals(student.getId())) {
            throw new SecurityException("This quiz session belongs to a different user.");
        }

        Long sessionEpisodeId = session.getEpisode().getId();
        if (request.getEpisodeId() != null && !request.getEpisodeId().equals(sessionEpisodeId)) {
            throw new InvalidQuizSessionException("Episode ID does not match the active quiz session.");
        }

        Episode episode = episodeRepository.findByIdWithPlaylist(sessionEpisodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode is archived and no longer accepting submissions");
        }

        if (session.getStatus() == QuizSessionStatus.SUBMITTED) {
            throw new InvalidQuizSessionException("This quiz session has already been submitted. Start a new session to retry.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (session.getStatus() == QuizSessionStatus.EXPIRED || now.isAfter(session.getExpiresAt())) {
            session.setStatus(QuizSessionStatus.EXPIRED);
            quizAttemptSessionRepository.save(session);
            throw new InvalidQuizSessionException("This quiz session has expired (60-minute limit). Refresh to draw a new session.");
        }

        watchProgressService.assertEpisodeUnlocked(student, episode, betaUnlockRequested);

        boolean alreadyPassedOrClaimed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())
                || quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());

        if (!alreadyPassedOrClaimed) {
            long cooldownRemaining = watchProgressService.getRetryCooldownRemainingSeconds(student.getId(), episode.getId());
            if (cooldownRemaining > 0) {
                long mins = cooldownRemaining / 60;
                long secs = cooldownRemaining % 60;
                throw new TooManyRequestsException(
                        String.format("Cooldown active after previous attempt. Please review the episode and retry in %dm %02ds.", mins, secs),
                        cooldownRemaining
                );
            }

            if (!watchProgressService.isQuizUnlockedForSubmission(student.getId(), episode)) {
                throw new SecurityException("Quiz is locked. Please complete the required watch progress before submitting.");
            }
        }

        List<Long> sessionQuestionIds = session.getQuestionIds();
        if (sessionQuestionIds.size() != SESSION_QUESTION_COUNT) {
            throw new InvalidQuizSessionException("Invalid session question set.");
        }
        Set<Long> expectedQuestionSet = new LinkedHashSet<>(sessionQuestionIds);

        Map<Long, Long> submittedAnswers = request.getAnswers();
        if (submittedAnswers == null || submittedAnswers.isEmpty()) {
            throw new InvalidQuizSessionException("All 10 questions in this session must be answered.");
        }

        for (Long answeredQuestionId : submittedAnswers.keySet()) {
            if (answeredQuestionId == null || !expectedQuestionSet.contains(answeredQuestionId)) {
                throw new InvalidQuizSessionException("Submission contains a question ID outside of this quiz session.");
            }
        }

        if (submittedAnswers.size() != SESSION_QUESTION_COUNT) {
            throw new InvalidQuizSessionException("Incomplete submission: all 10 questions in the session must be answered.");
        }

        List<Question> episodeQuestions = questionRepository.findByEpisodeIdWithOptions(episode.getId());
        Map<Long, Question> questionById = new HashMap<>();
        for (Question q : episodeQuestions) {
            questionById.put(q.getId(), q);
        }

        int correctCount = 0;
        for (Long qId : sessionQuestionIds) {
            Question question = questionById.get(qId);
            if (question == null) {
                throw new InvalidQuizSessionException("Session question no longer exists.");
            }
            Long selectedOptionId = submittedAnswers.get(qId);
            if (selectedOptionId == null) {
                throw new InvalidQuizSessionException("All 10 questions in the session must have a selected option.");
            }
            QuestionOption chosenOption = question.getOptions().stream()
                    .filter(opt -> opt.getId().equals(selectedOptionId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidQuizSessionException("Selected option does not belong to the session question."));
            if (Boolean.TRUE.equals(chosenOption.getIsCorrect())) {
                correctCount++;
            }
        }

        WatchMode effectiveWatchMode = watchProgressService.resolveEffectiveWatchMode(
                student.getId(), episode.getId(), request.getWatchMode());

        session.setStatus(QuizSessionStatus.SUBMITTED);
        session.setSubmittedAt(now);
        quizAttemptSessionRepository.save(session);

        double scorePercentage = ((double) correctCount / SESSION_QUESTION_COUNT) * 100.0;
        boolean passed = (correctCount == SESSION_QUESTION_COUNT);

        // Reward is created ONLY when the admin approves the open written answer (never on quiz submit)
        QuizAttempt attempt = new QuizAttempt(
                student,
                episode,
                effectiveWatchMode,
                scorePercentage,
                passed,
                BigDecimal.ZERO
        );
        quizAttemptRepository.save(attempt);

        if (passed) {
            OpenQuestionStudentResponse openQ = openAnswerReviewService.assignOrGetOpenQuestion(
                    student, episode, effectiveWatchMode);
            BigDecimal potentialRate = openAnswerReviewService.resolveEpisodeRewardRate(episode, effectiveWatchMode);
            boolean alreadyClaimed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId());
            String msg = alreadyClaimed
                    ? "Perfect 10/10 (100%)! Reward for this episode was already approved."
                    : String.format("Perfect 10/10 (100%%)! Complete the written question below so your $%s reward can be approved.", potentialRate);

            return new QuizResultResponse(
                    scorePercentage,
                    correctCount,
                    SESSION_QUESTION_COUNT,
                    true,
                    BigDecimal.ZERO,
                    alreadyClaimed ? BigDecimal.ZERO : potentialRate,
                    0L,
                    msg,
                    openQ
            );
        } else {
            long cooldownSecs = watchProgressService.getConfiguredRetryCooldownSeconds();
            String msg = String.format(
                    "Score: %d/%d (%.0f%%). Pass mark is 100%% (10/10). Review the episode and retry with a fresh set of questions after cooldown.",
                    correctCount, SESSION_QUESTION_COUNT, scorePercentage
            );
            return new QuizResultResponse(
                    scorePercentage,
                    correctCount,
                    SESSION_QUESTION_COUNT,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    cooldownSecs,
                    msg,
                    null
            );
        }
    }
}
