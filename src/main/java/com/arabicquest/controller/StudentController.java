package com.arabicquest.controller;

import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final EpisodeRepository episodeRepository;
    private final CourseService courseService;
    private final QuizService quizService;
    private final PayoutService payoutService;
    private final UserRepository userRepository;
    private final AiQuizService aiQuizService;
    private final PayoutRepository payoutRepository;
    private final WatchProgressService watchProgressService;
    private final OpenAnswerReviewService openAnswerReviewService;
    private final QuestionBankService questionBankService;

    public StudentController(
            EpisodeRepository episodeRepository,
            CourseService courseService,
            QuizService quizService,
            PayoutService payoutService,
            UserRepository userRepository,
            AiQuizService aiQuizService,
            PayoutRepository payoutRepository,
            WatchProgressService watchProgressService,
            OpenAnswerReviewService openAnswerReviewService,
            QuestionBankService questionBankService) {
        this.episodeRepository = episodeRepository;
        this.courseService = courseService;
        this.quizService = quizService;
        this.payoutService = payoutService;
        this.userRepository = userRepository;
        this.aiQuizService = aiQuizService;
        this.payoutRepository = payoutRepository;
        this.watchProgressService = watchProgressService;
        this.openAnswerReviewService = openAnswerReviewService;
        this.questionBankService = questionBankService;
    }

    @GetMapping("/courses")
    public ResponseEntity<List<CourseCatalogItemResponse>> getCourseCatalog(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(courseService.getPublishedCourseCatalog(userDetails.getUsername()));
    }

    @GetMapping("/courses/{courseId}")
    public ResponseEntity<CourseDetailStudentResponse> getCourseDetail(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long courseId,
            @RequestParam(defaultValue = "false") boolean betaUnlock) {
        return ResponseEntity.ok(courseService.getStudentCourseDetail(userDetails.getUsername(), courseId, betaUnlock));
    }

    @GetMapping("/episodes")
    public ResponseEntity<List<EpisodeStudentResponse>> getEpisodes(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Long courseId,
            @RequestParam(defaultValue = "false") boolean betaUnlock) {
        return ResponseEntity.ok(courseService.getStudentEpisodes(userDetails.getUsername(), courseId, betaUnlock));
    }

    @PostMapping("/episodes/{episodeId}/favorite")
    public ResponseEntity<Map<String, Object>> favoriteEpisode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestParam(defaultValue = "false") boolean betaUnlock) {
        return ResponseEntity.ok(courseService.favoriteEpisode(userDetails.getUsername(), episodeId, betaUnlock));
    }

    @DeleteMapping("/episodes/{episodeId}/favorite")
    public ResponseEntity<Map<String, Object>> unfavoriteEpisode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId) {
        return ResponseEntity.ok(courseService.unfavoriteEpisode(userDetails.getUsername(), episodeId));
    }

    @GetMapping("/quizzes/{episodeId}")
    public ResponseEntity<QuizViewResponse> getQuizForEpisode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestParam(defaultValue = "false") boolean betaUnlock) {
        return ResponseEntity.ok(quizService.getStudentQuizView(userDetails.getUsername(), episodeId, betaUnlock));
    }

    @PostMapping("/episodes/{episodeId}/heartbeat")
    public ResponseEntity<WatchStatusResponse> recordWatchHeartbeat(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestParam(defaultValue = "false") boolean betaUnlock,
            @RequestBody(required = false) WatchHeartbeatRequest request) {
        WatchStatusResponse status = watchProgressService.recordHeartbeat(
                userDetails.getUsername(), episodeId, request, betaUnlock);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/episodes/{episodeId}/reset-session")
    public ResponseEntity<WatchStatusResponse> resetWatchSession(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestParam(defaultValue = "ARABIC_SUB") WatchMode watchMode) {
        WatchStatusResponse status = watchProgressService.resetSessionAndSwitchMode(
                userDetails.getUsername(), episodeId, watchMode);
        return ResponseEntity.ok(status);
    }

    @Transactional(readOnly = true)
    @GetMapping("/episodes/{episodeId}/vocab-guide")
    public ResponseEntity<List<Map<String, String>>> getVocabularyGuide(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestParam(defaultValue = "false") boolean betaUnlock) {
        User student = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        boolean allowBetaBypass = betaUnlock && student.hasBetaAccess();
        if (episode.isArchived()
                || !episode.isEnabled()
                || episode.getPlaylist() == null
                || episode.getPlaylist().isArchived()
                || (!episode.getPlaylist().isPublished() && !allowBetaBypass)) {
            throw new SecurityException("Course is not available.");
        }
        List<Map<String, String>> guide = aiQuizService.getVocabularyGuide(episodeId);
        return ResponseEntity.ok(guide);
    }

    @PostMapping("/quizzes/submit")
    public ResponseEntity<QuizResultResponse> submitQuiz(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "false") boolean betaUnlock,
            @Valid @RequestBody QuizSubmissionRequest request) {
        QuizResultResponse response = quizService.submitQuiz(userDetails.getUsername(), request, betaUnlock);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/questions/{questionId}/report")
    public ResponseEntity<Map<String, Object>> reportQuestion(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long questionId,
            @Valid @RequestBody(required = false) QuestionReportRequest request) {
        Map<String, Object> result = questionBankService.reportQuestion(userDetails.getUsername(), questionId, request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/episodes/{episodeId}/open-question")
    public ResponseEntity<OpenQuestionStudentResponse> getOpenQuestionForEpisode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId) {
        return ResponseEntity.ok(openAnswerReviewService.getStudentOpenQuestionState(userDetails.getUsername(), episodeId));
    }

    @PostMapping("/episodes/{episodeId}/open-answer")
    public ResponseEntity<OpenQuestionStudentResponse> submitOpenAnswer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @Valid @RequestBody OpenAnswerSubmitRequest request) {
        return ResponseEntity.ok(openAnswerReviewService.submitStudentOpenAnswer(
                userDetails.getUsername(), episodeId, request.answerText()));
    }

    @GetMapping("/earnings")
    public ResponseEntity<EarningsSummaryResponse> getEarnings(
            @AuthenticationPrincipal UserDetails userDetails) {
        EarningsSummaryResponse summary = payoutService.getStudentEarnings(userDetails.getUsername());
        return ResponseEntity.ok(summary);
    }

    @Transactional(readOnly = true)
    @GetMapping("/payouts")
    public ResponseEntity<List<PayoutResponse>> getMyPayouts(
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        List<PayoutResponse> list = payoutRepository.findByStudentIdOrderByCreatedAtDesc(student.getId())
                .stream()
                .map(PayoutResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }
}
