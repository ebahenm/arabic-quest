package com.arabicquest.controller;

import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.QuizAttemptRepository;
import com.arabicquest.repository.QuizRepository;
import com.arabicquest.repository.UserRepository;
import com.arabicquest.service.AiQuizService;
import com.arabicquest.service.PayoutService;
import com.arabicquest.service.QuizService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final EpisodeRepository episodeRepository;
    private final QuizRepository quizRepository;
    private final QuizService quizService;
    private final PayoutService payoutService;
    private final QuizAttemptRepository quizAttemptRepository;
    private final UserRepository userRepository;
    private final AiQuizService aiQuizService;

    public StudentController(EpisodeRepository episodeRepository,
                             QuizRepository quizRepository,
                             QuizService quizService,
                             PayoutService payoutService,
                             QuizAttemptRepository quizAttemptRepository,
                             UserRepository userRepository,
                             AiQuizService aiQuizService) {
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
        this.quizService = quizService;
        this.payoutService = payoutService;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userRepository = userRepository;
        this.aiQuizService = aiQuizService;
    }

    @GetMapping("/episodes")
    public ResponseEntity<List<Map<String, Object>>> getEpisodes(@AuthenticationPrincipal UserDetails userDetails) {
        User student = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<Episode> episodes = episodeRepository.findAll();
        List<Map<String, Object>> result = episodes.stream().map(ep -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", ep.getId());
            map.put("episodeNumber", ep.getEpisodeNumber());
            map.put("title", ep.getTitle());
            map.put("youtubeVideoId", ep.getYoutubeVideoId());
            boolean passed = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), ep.getId());
            map.put("passed", passed);
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/quizzes/{episodeId}")
    public ResponseEntity<QuizViewResponse> getQuizForEpisode(@PathVariable Long episodeId) {
        Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElse(null);

        // If no quiz exists yet, auto-generate one with AI!
        if (quiz == null || quiz.getQuestions().isEmpty()) {
            quiz = aiQuizService.generateQuizForEpisode(episodeId);
        }

        List<QuestionViewResponse> questionViews = quiz.getQuestions().stream().map(q -> {
            List<OptionViewResponse> optionViews = q.getOptions().stream()
                    .map(opt -> new OptionViewResponse(opt.getId(), opt.getOptionText()))
                    .collect(Collectors.toList());
            return new QuestionViewResponse(q.getId(), q.getQuestionText(), q.getQuestionOrder(), optionViews);
        }).collect(Collectors.toList());

        QuizViewResponse response = new QuizViewResponse(quiz.getId(), episodeId, quiz.getPassingScorePercentage(), questionViews);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/quizzes/generate/{episodeId}")
    public ResponseEntity<QuizViewResponse> regenerateQuizWithAi(@PathVariable Long episodeId) {
        Quiz quiz = aiQuizService.generateQuizForEpisode(episodeId);

        List<QuestionViewResponse> questionViews = quiz.getQuestions().stream().map(q -> {
            List<OptionViewResponse> optionViews = q.getOptions().stream()
                    .map(opt -> new OptionViewResponse(opt.getId(), opt.getOptionText()))
                    .collect(Collectors.toList());
            return new QuestionViewResponse(q.getId(), q.getQuestionText(), q.getQuestionOrder(), optionViews);
        }).collect(Collectors.toList());

        QuizViewResponse response = new QuizViewResponse(quiz.getId(), episodeId, quiz.getPassingScorePercentage(), questionViews);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/episodes/{episodeId}/vocab-guide")
    public ResponseEntity<List<Map<String, String>>> getVocabularyGuide(@PathVariable Long episodeId) {
        List<Map<String, String>> guide = aiQuizService.getVocabularyGuide(episodeId);
        return ResponseEntity.ok(guide);
    }

    @PostMapping("/quizzes/submit")
    public ResponseEntity<QuizResultResponse> submitQuiz(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody QuizSubmissionRequest request) {
        QuizResultResponse response = quizService.submitQuiz(userDetails.getUsername(), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/earnings")
    public ResponseEntity<EarningsSummaryResponse> getEarnings(@AuthenticationPrincipal UserDetails userDetails) {
        EarningsSummaryResponse summary = payoutService.getStudentEarnings(userDetails.getUsername());
        return ResponseEntity.ok(summary);
    }
}
