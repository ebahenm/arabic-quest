package com.arabicquest.controller;

import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutService payoutService;
    private final PlaylistRepository playlistRepository;
    private final EpisodeRepository episodeRepository;
    private final QuizRepository quizRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final OpenAnswerSubmissionRepository openAnswerSubmissionRepository;
    private final StudentOpenQuestionAssignmentRepository assignmentRepository;
    private final QuizAttemptSessionRepository quizAttemptSessionRepository;
    private final StudentQuestionExposureRepository exposureRepository;
    private final QuestionReportRepository questionReportRepository;
    private final StudentEpisodeOverrideRepository studentEpisodeOverrideRepository;
    private final StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository;
    private final WatchSessionRepository watchSessionRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final WatchProgressService watchProgressService;
    private final AiQuizService aiQuizService;
    private final QuestionBankService questionBankService;
    private final OpenAnswerReviewService openAnswerReviewService;
    private final QuestionBankImportExportService questionBankImportExportService;
    private final CourseService courseService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminController(
            UserRepository userRepository,
            QuizAttemptRepository quizAttemptRepository,
            PayoutRepository payoutRepository,
            PayoutService payoutService,
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            OpenQuestionRepository openQuestionRepository,
            OpenAnswerSubmissionRepository openAnswerSubmissionRepository,
            StudentOpenQuestionAssignmentRepository assignmentRepository,
            QuizAttemptSessionRepository quizAttemptSessionRepository,
            StudentQuestionExposureRepository exposureRepository,
            QuestionReportRepository questionReportRepository,
            StudentEpisodeOverrideRepository studentEpisodeOverrideRepository,
            StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository,
            WatchSessionRepository watchSessionRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            WatchProgressService watchProgressService,
            AiQuizService aiQuizService,
            QuestionBankService questionBankService,
            OpenAnswerReviewService openAnswerReviewService,
            QuestionBankImportExportService questionBankImportExportService,
            CourseService courseService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.payoutRepository = payoutRepository;
        this.payoutService = payoutService;
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.openAnswerSubmissionRepository = openAnswerSubmissionRepository;
        this.assignmentRepository = assignmentRepository;
        this.quizAttemptSessionRepository = quizAttemptSessionRepository;
        this.exposureRepository = exposureRepository;
        this.questionReportRepository = questionReportRepository;
        this.studentEpisodeOverrideRepository = studentEpisodeOverrideRepository;
        this.studentFavoriteEpisodeRepository = studentFavoriteEpisodeRepository;
        this.watchSessionRepository = watchSessionRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.watchProgressService = watchProgressService;
        this.aiQuizService = aiQuizService;
        this.questionBankService = questionBankService;
        this.openAnswerReviewService = openAnswerReviewService;
        this.questionBankImportExportService = questionBankImportExportService;
        this.courseService = courseService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/students")
    public ResponseEntity<List<UserSummaryResponse>> getAllStudents() {
        List<UserSummaryResponse> users = userRepository.findAllByOrderByIdAsc()
                .stream()
                .map(UserSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    @PostMapping("/students")
    public ResponseEntity<?> createStudent(@Valid @RequestBody RegisterRequest request) {
        String cleanUsername = request.getUsername().trim().toLowerCase();
        if (userRepository.existsByUsernameIgnoreCase(cleanUsername)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Username '" + cleanUsername + "' is already taken"));
        }

        User newUser = new User(
                cleanUsername,
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim(),
                Role.ROLE_STUDENT
        );
        User saved = userRepository.save(newUser);
        return ResponseEntity.ok(UserSummaryResponse.from(saved));
    }

    @Transactional
    @PostMapping("/students/{studentId}/reset-password")
    public ResponseEntity<Map<String, String>> resetStudentPassword(
            @PathVariable Long studentId,
            @Valid @RequestBody ResetStudentPasswordRequest request) {
        User user = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        if (user.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("Only student accounts can be reset via this endpoint");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.incrementTokenVersion();
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully for " + user.getUsername()));
    }

    @Transactional
    @PostMapping("/students/{studentId}/update-account")
    public ResponseEntity<?> updateStudentAccount(
            @PathVariable Long studentId,
            @Valid @RequestBody UpdateStudentAccountRequest request) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        if (student.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("Only student accounts can be updated via this endpoint");
        }

        boolean changed = false;
        boolean revokeTokens = false;

        if (request.newUsername() != null && !request.newUsername().isBlank()) {
            String cleanUsername = request.newUsername().trim().toLowerCase();
            if (cleanUsername.length() < 3) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Username must be at least 3 characters"));
            }
            if (!cleanUsername.equals(student.getUsername())) {
                if (userRepository.existsByUsernameIgnoreCaseAndIdNot(cleanUsername, student.getId())) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Username '" + cleanUsername + "' is already taken"));
                }
                student.setUsername(cleanUsername);
                changed = true;
                revokeTokens = true;
            }
        }

        if (request.fullName() != null && !request.fullName().isBlank()) {
            String cleanName = request.fullName().trim();
            if (!cleanName.equals(student.getFullName())) {
                student.setFullName(cleanName);
                changed = true;
            }
        }

        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            if (request.newPassword().length() < 8) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "New password must be at least 8 characters"));
            }
            student.setPassword(passwordEncoder.encode(request.newPassword()));
            changed = true;
            revokeTokens = true;
        }

        if (request.betaTester() != null && request.betaTester() != student.isBetaTester()) {
            student.setBetaTester(request.betaTester());
            changed = true;
        }

        if (!changed) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "No changes detected"));
        }

        if (revokeTokens) {
            student.incrementTokenVersion();
        }
        User saved = userRepository.save(student);
        return ResponseEntity.ok(UserSummaryResponse.from(saved));
    }

    @GetMapping("/students/{username}/earnings")
    public ResponseEntity<EarningsSummaryResponse> getStudentEarnings(@PathVariable String username) {
        return ResponseEntity.ok(payoutService.getStudentEarnings(username));
    }

    @GetMapping("/students/{studentId}/episode-overrides")
    public ResponseEntity<List<WatchProgressService.StudentEpisodeOverrideView>> getStudentEpisodeOverrides(
            @PathVariable Long studentId) {
        return ResponseEntity.ok(watchProgressService.listStudentEpisodeOverrides(studentId));
    }

    @PostMapping("/students/{studentId}/episodes/{episodeId}/unlock")
    public ResponseEntity<Map<String, Object>> unlockEpisodeForStudent(
            @PathVariable Long studentId,
            @PathVariable Long episodeId) {
        StudentEpisodeOverride.OverrideState state = watchProgressService.setStudentEpisodeOverride(
                studentId, episodeId, StudentEpisodeOverride.OverrideState.UNLOCKED);
        return ResponseEntity.ok(Map.of(
                "studentId", studentId,
                "episodeId", episodeId,
                "overrideState", state.name()
        ));
    }

    @PostMapping("/students/{studentId}/episodes/{episodeId}/lock")
    public ResponseEntity<Map<String, Object>> lockEpisodeForStudent(
            @PathVariable Long studentId,
            @PathVariable Long episodeId) {
        StudentEpisodeOverride.OverrideState state = watchProgressService.setStudentEpisodeOverride(
                studentId, episodeId, StudentEpisodeOverride.OverrideState.LOCKED);
        return ResponseEntity.ok(Map.of(
                "studentId", studentId,
                "episodeId", episodeId,
                "overrideState", state.name()
        ));
    }

    @DeleteMapping("/students/{studentId}/episodes/{episodeId}/override")
    public ResponseEntity<Map<String, Object>> clearEpisodeOverrideForStudent(
            @PathVariable Long studentId,
            @PathVariable Long episodeId) {
        watchProgressService.setStudentEpisodeOverride(studentId, episodeId, null);
        return ResponseEntity.ok(Map.of(
                "studentId", studentId,
                "episodeId", episodeId,
                "overrideState", "AUTO"
        ));
    }

    @Transactional(readOnly = true)
    @GetMapping("/attempts")
    public ResponseEntity<List<AttemptAdminViewResponse>> getAllAttempts() {
        List<AttemptAdminViewResponse> list = quizAttemptRepository
                .findAllWithStudentAndEpisodeOrderByAttemptedAtDesc()
                .stream()
                .map(AttemptAdminViewResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @Transactional(readOnly = true)
    @GetMapping("/payouts")
    public ResponseEntity<List<PayoutResponse>> getAllPayouts() {
        List<PayoutResponse> list = payoutRepository
                .findAllWithStudentOrderByCreatedAtDesc()
                .stream()
                .map(PayoutResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping("/payouts")
    public ResponseEntity<PayoutResponse> markPayoutJson(@Valid @RequestBody CreatePayoutRequest request) {
        Payout payout = payoutService.markAsPaid(request.studentId(), request.amount(), request.note());
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    @Transactional
    @PostMapping(value = "/payouts", params = {"studentId", "amount"})
    public ResponseEntity<PayoutResponse> markPayoutParams(
            @RequestParam Long studentId,
            @RequestParam @DecimalMin(value = "0.01", message = "Payout amount must be greater than $0.00") BigDecimal amount,
            @RequestParam(required = false, defaultValue = "Cash/Transfer Payout") String note) {
        Payout payout = payoutService.markAsPaid(studentId, amount, note);
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    // --- Courses & YouTube Playlist Import / Re-Sync ---

    @GetMapping("/courses")
    public ResponseEntity<List<CourseAdminResponse>> listAdminCourses() {
        return ResponseEntity.ok(courseService.listAllCoursesForAdmin());
    }

    @GetMapping("/courses/{courseId}")
    public ResponseEntity<CourseAdminResponse> getAdminCourse(@PathVariable Long courseId) {
        return ResponseEntity.ok(courseService.getCourseForAdmin(courseId));
    }

    @PostMapping("/courses")
    public ResponseEntity<CourseAdminResponse> createAdminCourse(@Valid @RequestBody UpsertCourseRequest request) {
        return ResponseEntity.ok(courseService.createDraftCourse(request));
    }

    @PutMapping("/courses/{courseId}")
    public ResponseEntity<CourseAdminResponse> updateAdminCourse(
            @PathVariable Long courseId,
            @Valid @RequestBody UpsertCourseRequest request) {
        return ResponseEntity.ok(courseService.updateCourse(courseId, request));
    }

    @PostMapping("/courses/{courseId}/publish")
    public ResponseEntity<CourseAdminResponse> setCoursePublished(
            @PathVariable Long courseId,
            @RequestParam(required = false) Boolean published,
            @RequestBody(required = false) Map<String, Boolean> body) {
        boolean targetPublished = published != null
                ? published
                : (body != null && body.containsKey("published") ? Boolean.TRUE.equals(body.get("published")) : true);
        return ResponseEntity.ok(courseService.setCoursePublished(courseId, targetPublished));
    }

    @PostMapping("/courses/{courseId}/archive")
    public ResponseEntity<CourseAdminResponse> setCourseArchived(
            @PathVariable Long courseId,
            @RequestParam(required = false) Boolean archived,
            @RequestBody(required = false) Map<String, Boolean> body) {
        boolean targetArchived = archived != null
                ? archived
                : (body != null && body.containsKey("archived") ? Boolean.TRUE.equals(body.get("archived")) : true);
        return ResponseEntity.ok(courseService.setCourseArchived(courseId, targetArchived));
    }

    @PutMapping("/courses/{courseId}/episodes/reorder")
    public ResponseEntity<CourseAdminResponse> reorderCourseEpisodes(
            @PathVariable Long courseId,
            @Valid @RequestBody ReorderEpisodesRequest request) {
        return ResponseEntity.ok(courseService.reorderCourseEpisodes(courseId, request.episodeIds()));
    }

    @PostMapping("/courses/youtube-preview")
    public ResponseEntity<YouTubePreviewResponse> previewYouTubePlaylist(
            @Valid @RequestBody YouTubePreviewRequest request) {
        return ResponseEntity.ok(courseService.previewYouTubePlaylist(request.playlistUrlOrId()));
    }

    @PostMapping("/courses/youtube-import")
    public ResponseEntity<CourseAdminResponse> importCourseFromYouTube(
            @Valid @RequestBody YouTubeImportCourseRequest request) {
        return ResponseEntity.ok(courseService.importCourseFromYouTube(request));
    }

    @PostMapping("/courses/{courseId}/resync")
    public ResponseEntity<CourseResyncResponse> resyncCourseFromYouTube(@PathVariable Long courseId) {
        return ResponseEntity.ok(courseService.resyncCourseFromYouTube(courseId));
    }

    @PutMapping("/episodes/{episodeId}")
    public ResponseEntity<CourseAdminResponse.EpisodeAdminItem> updateEpisodeAdmin(
            @PathVariable Long episodeId,
            @Valid @RequestBody UpdateEpisodeAdminRequest request) {
        return ResponseEntity.ok(courseService.updateEpisodeAdmin(episodeId, request));
    }

    @Transactional(readOnly = true)
    @GetMapping("/episodes")
    public ResponseEntity<List<Map<String, Object>>> getActiveEpisodes() {
        List<Map<String, Object>> list = episodeRepository.findAllNonArchivedOrderByCourseAndSortOrderAsc()
                .stream()
                .map(ep -> Map.<String, Object>of(
                        "id", ep.getId(),
                        "playlistId", ep.getPlaylist().getId(),
                        "courseTitle", ep.getPlaylist().getTitle(),
                        "episodeNumber", ep.getEpisodeNumber(),
                        "title", ep.getTitle(),
                        "youtubeVideoId", ep.getYoutubeVideoId(),
                        "durationSeconds", ep.getDurationSeconds(),
                        "enabled", ep.isEnabled()
                ))
                .toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping("/episodes")
    public ResponseEntity<Map<String, Object>> createEpisode(@Valid @RequestBody CreateEpisodeRequest request) {
        Playlist playlist;
        if (request.getPlaylistId() != null) {
            playlist = playlistRepository.findById(request.getPlaylistId())
                    .or(() -> playlistRepository.findFirstByOrderByIdAsc())
                    .orElseGet(() -> playlistRepository.save(new Playlist("Default Course", "default-course", "")));
        } else {
            playlist = playlistRepository.findFirstByOrderByIdAsc()
                    .orElseGet(() -> playlistRepository.save(new Playlist("Default Course", "default-course", "")));
        }

        String cleanVideoId = extractYoutubeVideoId(request.getYoutubeVideoId());
        Episode existing = episodeRepository.findByPlaylistIdAndEpisodeNumber(playlist.getId(), request.getEpisodeNumber())
                .orElse(null);

        Episode saved;
        if (existing != null) {
            if (!existing.isArchived()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Episode " + request.getEpisodeNumber() + " already exists in this course"));
            }
            existing.setTitle(request.getTitle().trim());
            existing.setYoutubeVideoId(cleanVideoId);
            existing.setDurationSeconds(request.getDurationSeconds() != null ? request.getDurationSeconds() : 1800);
            existing.setArchived(false);
            existing.setVocabularyJson(null);
            saved = episodeRepository.save(existing);
        } else {
            Episode episode = new Episode(
                    playlist,
                    request.getEpisodeNumber(),
                    request.getTitle().trim(),
                    cleanVideoId,
                    request.getDurationSeconds() != null ? request.getDurationSeconds() : 1800
            );
            saved = episodeRepository.save(episode);
        }

        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "playlistId", playlist.getId(),
                "episodeNumber", saved.getEpisodeNumber(),
                "title", saved.getTitle(),
                "youtubeVideoId", saved.getYoutubeVideoId(),
                "durationSeconds", saved.getDurationSeconds()
        ));
    }

    // --- Written Answer Review Queue ---

    @GetMapping("/open-answers")
    public ResponseEntity<List<OpenAnswerAdminViewResponse>> listOpenAnswers(
            @RequestParam(required = false, defaultValue = "ALL") String status) {
        return ResponseEntity.ok(openAnswerReviewService.listSubmissionsForAdmin(status));
    }

    @PostMapping("/open-answers/{submissionId}/approve")
    public ResponseEntity<Map<String, Object>> approveOpenAnswer(
            @PathVariable Long submissionId,
            @Valid @RequestBody(required = false) AdminReviewDecisionRequest request) {
        String feedback = request != null ? request.feedback() : null;
        return ResponseEntity.ok(openAnswerReviewService.approveSubmission(submissionId, feedback));
    }

    @PostMapping("/open-answers/{submissionId}/request-revision")
    public ResponseEntity<OpenAnswerAdminViewResponse> requestOpenAnswerRevision(
            @PathVariable Long submissionId,
            @Valid @RequestBody(required = false) AdminReviewDecisionRequest request) {
        String feedback = request != null ? request.feedback() : null;
        return ResponseEntity.ok(openAnswerReviewService.requestRevision(submissionId, feedback));
    }

    @PostMapping("/open-answers/{submissionId}/suggest-grade")
    public ResponseEntity<GradeSuggestionResponse> suggestOpenAnswerGrade(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long submissionId) {
        return ResponseEntity.ok(openAnswerReviewService.suggestGrade(userDetails.getUsername(), submissionId));
    }

    // --- Question Bank Manager & Transcript 2-Pass AI Generator ---

    @GetMapping("/episodes/{episodeId}/question-bank")
    public ResponseEntity<QuestionBankAdminResponse> getEpisodeQuestionBank(
            @PathVariable Long episodeId,
            @RequestParam(required = false, defaultValue = "ALL") String status,
            @RequestParam(required = false, defaultValue = "ALL") String type,
            @RequestParam(required = false, defaultValue = "false") boolean reportedOnly) {
        return ResponseEntity.ok(questionBankService.getEpisodeQuestionBank(episodeId, status, type, reportedOnly));
    }

    @PostMapping("/episodes/{episodeId}/generate-bank")
    public ResponseEntity<AiQuizService.GeneratedBankSummary> generateBankFromTranscript(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestBody(required = false) GenerateBankFromTranscriptRequest request) {
        String transcript = request != null ? request.transcript() : null;
        return ResponseEntity.ok(aiQuizService.generateQuestionBankFromTranscript(
                userDetails.getUsername(), episodeId, transcript));
    }

    @PostMapping("/episodes/{episodeId}/questions")
    public ResponseEntity<QuestionBankAdminResponse.McqAdminItem> createQuestion(
            @PathVariable Long episodeId,
            @Valid @RequestBody UpsertQuestionRequest request) {
        return ResponseEntity.ok(questionBankService.createMcq(episodeId, request));
    }

    @PutMapping("/questions/{questionId}")
    public ResponseEntity<QuestionBankAdminResponse.McqAdminItem> updateQuestion(
            @PathVariable Long questionId,
            @Valid @RequestBody UpsertQuestionRequest request) {
        return ResponseEntity.ok(questionBankService.updateMcq(questionId, request));
    }

    @PostMapping("/questions/{questionId}/approve")
    public ResponseEntity<QuestionBankAdminResponse.McqAdminItem> approveQuestion(@PathVariable Long questionId) {
        return ResponseEntity.ok(questionBankService.approveMcq(questionId));
    }

    @PostMapping("/questions/{questionId}/retire")
    public ResponseEntity<QuestionBankAdminResponse.McqAdminItem> retireQuestion(@PathVariable Long questionId) {
        return ResponseEntity.ok(questionBankService.retireMcq(questionId));
    }

    @PostMapping("/episodes/{episodeId}/questions/bulk-approve")
    public ResponseEntity<Map<String, Integer>> bulkApproveEpisodeDrafts(@PathVariable Long episodeId) {
        return ResponseEntity.ok(questionBankService.bulkApproveDrafts(episodeId));
    }

    @PostMapping("/episodes/{episodeId}/open-questions")
    public ResponseEntity<QuestionBankAdminResponse.OpenQuestionAdminItem> createOpenQuestion(
            @PathVariable Long episodeId,
            @Valid @RequestBody UpsertOpenQuestionRequest request) {
        return ResponseEntity.ok(questionBankService.createOpenQuestion(episodeId, request));
    }

    @PutMapping("/open-questions/{openQuestionId}")
    public ResponseEntity<QuestionBankAdminResponse.OpenQuestionAdminItem> updateOpenQuestion(
            @PathVariable Long openQuestionId,
            @Valid @RequestBody UpsertOpenQuestionRequest request) {
        return ResponseEntity.ok(questionBankService.updateOpenQuestion(openQuestionId, request));
    }

    @PostMapping("/open-questions/{openQuestionId}/approve")
    public ResponseEntity<QuestionBankAdminResponse.OpenQuestionAdminItem> approveOpenQuestion(
            @PathVariable Long openQuestionId) {
        return ResponseEntity.ok(questionBankService.approveOpenQuestion(openQuestionId));
    }

    @PostMapping("/open-questions/{openQuestionId}/retire")
    public ResponseEntity<QuestionBankAdminResponse.OpenQuestionAdminItem> retireOpenQuestion(
            @PathVariable Long openQuestionId) {
        return ResponseEntity.ok(questionBankService.retireOpenQuestion(openQuestionId));
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/quiz")
    public ResponseEntity<QuizAdminViewResponse> createOrUpdateQuiz(
            @PathVariable Long episodeId,
            @Valid @RequestBody CreateQuizRequest request) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        Quiz quiz = quizRepository.findByEpisodeId(episodeId)
                .orElse(new Quiz(episode));

        quiz.setPassingScorePercentage(100.0);
        quiz.getQuestions().clear();

        if (request.getQuestions() != null) {
            int order = 1;
            for (CreateQuizRequest.CreateQuestionRequest qReq : request.getQuestions()) {
                Question question = new Question(quiz, qReq.getQuestionText(), order++);
                question.setStatus(QuestionStatus.APPROVED);
                question.setActive(true);
                if (qReq.getOptions() != null) {
                    List<CreateQuizRequest.CreateOptionRequest> shuffled = new ArrayList<>(qReq.getOptions());
                    Collections.shuffle(shuffled, secureRandom);
                    for (CreateQuizRequest.CreateOptionRequest optReq : shuffled) {
                        QuestionOption opt = new QuestionOption(question, optReq.getOptionText(), Boolean.TRUE.equals(optReq.getIsCorrect()));
                        question.getOptions().add(opt);
                    }
                }
                quiz.getQuestions().add(question);
            }
        }

        Quiz saved = quizRepository.save(quiz);
        return ResponseEntity.ok(QuizAdminViewResponse.from(saved, episodeId));
    }

    @GetMapping("/question-bank/export")
    public ResponseEntity<QuestionBankBackupDto> exportQuestionBank() {
        QuestionBankBackupDto exported = questionBankImportExportService.exportQuestionBank();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"turathtona-question-bank.private.json\"")
                .body(exported);
    }

    @PostMapping("/question-bank/import")
    public ResponseEntity<Map<String, Object>> importQuestionBank(@RequestBody QuestionBankBackupDto backup) {
        Map<String, Object> result = questionBankImportExportService.importQuestionBank(backup);
        return ResponseEntity.ok(result);
    }

    @Transactional
    @DeleteMapping("/episodes/{episodeId}")
    public ResponseEntity<Void> deleteEpisode(@PathVariable Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId).orElse(null);
        if (episode == null) {
            return ResponseEntity.notFound().build();
        }

        watchSessionRepository.deleteByEpisodeId(episodeId);

        boolean hasHistory = quizAttemptRepository.existsByEpisodeId(episodeId)
                || episodeRewardClaimRepository.existsByEpisodeId(episodeId)
                || openAnswerSubmissionRepository.existsByEpisodeId(episodeId)
                || assignmentRepository.existsByEpisodeId(episodeId);

        if (hasHistory) {
            episode.setArchived(true);
            episodeRepository.save(episode);
        } else {
            questionReportRepository.deleteByEpisodeId(episodeId);
            exposureRepository.deleteByEpisodeId(episodeId);
            quizAttemptSessionRepository.deleteByEpisodeId(episodeId);
            studentEpisodeOverrideRepository.deleteByEpisodeId(episodeId);
            studentFavoriteEpisodeRepository.deleteByEpisodeId(episodeId);
            openQuestionRepository.deleteByEpisodeId(episodeId);
            episodeRepository.delete(episode);
        }
        return ResponseEntity.noContent().build();
    }

    public static String extractYoutubeVideoId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("YouTube video ID is required");
        }
        String trimmed = raw.trim();
        String candidate = trimmed;

        if (trimmed.contains("youtu.be/")) {
            int idx = trimmed.indexOf("youtu.be/") + "youtu.be/".length();
            candidate = trimmed.substring(idx).split("[?&/#]")[0];
        } else if (trimmed.contains("v=")) {
            int idx = trimmed.indexOf("v=") + 2;
            candidate = trimmed.substring(idx).split("[?&#]")[0];
        } else if (trimmed.contains("/embed/")) {
            int idx = trimmed.indexOf("/embed/") + "/embed/".length();
            candidate = trimmed.substring(idx).split("[?&/#]")[0];
        } else if (trimmed.contains("/shorts/")) {
            int idx = trimmed.indexOf("/shorts/") + "/shorts/".length();
            candidate = trimmed.substring(idx).split("[?&/#]")[0];
        }

        if (!candidate.matches("^[A-Za-z0-9_-]{6,20}$")) {
            throw new IllegalArgumentException("Invalid YouTube video ID or URL");
        }
        return candidate;
    }
}
