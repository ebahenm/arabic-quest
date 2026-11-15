// Integration tests for Pipeline v2: SRT/VTT quality gate, Claude primary writer, auto-caption fetch, Gemini 429/503 fallback, N+1 lookahead, and admin unlock.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase8_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase8admin", "ADMIN_PASSWORD=Phase8AdminPass#2026", "ADMIN_SECRET_PATH=phase8-hq",
        "JWT_SECRET=phase8-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "GEMINI_API_KEY=test-gemini-key-secret-888", "GEMINI_MODEL=gemini-3.8-flash",
        "ANTHROPIC_API_KEY=test-claude-key-secret-999", "CLAUDE_MODEL=claude-sonnet-5-5",
        "AUTO_QUIZ_GENERATION_ENABLED=true"
})
class Phase8PipelineV2IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private EpisodeTranscriptRepository transcriptRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private GenerationJobRepository generationJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private StudentQuestionExposureRepository exposureRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TranscriptParserService transcriptParserService;
    @Autowired private TranscriptQualityGate transcriptQualityGate;
    @Autowired private GeminiTextClient geminiTextClient;
    @Autowired private ClaudeClient claudeClient;
    @Autowired private YouTubeCaptionService youTubeCaptionService;
    @Autowired private BlindTestService blindTestService;
    @Autowired private GenerationJobService generationJobService;
    @Autowired private AutoQuizGenerationService autoQuizGenerationService;
    @Autowired private QuizService quizService;
    private static String adminBearer, studentBearer;
    private User student;
    private Playlist course;

    @BeforeEach
    void setUp() throws Exception {
        if (adminBearer == null) {
            MvcResult res = mockMvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("10.80.0.1"); return req; })
                            .contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "XMLHttpRequest")
                            .content("{\"username\":\"phase8admin\",\"password\":\"Phase8AdminPass#2026\"}")).andExpect(status().isOk()).andReturn();
            adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
        }
        if (studentBearer == null) studentBearer = Phase5GeminiTestSupport.createStudentAndLogin(mockMvc, objectMapper, adminBearer, "p8student", "10.80.0.2");
        student = userRepository.findByUsername("p8student").orElseThrow();
        course = playlistRepository.findAll().stream().filter(p -> "Phase8 Course".equals(p.getTitle())).findFirst()
                .orElseGet(() -> { Playlist p = new Playlist("Phase8 Course", "PL_PHASE8", "Pipeline v2"); p.setPublished(true); return playlistRepository.save(p); });
    }

    @Test
    void vttCleanupQualityGateMarkersAndUploadWorkflowWorkEndToEnd() throws Exception {
        assertThatThrownBy(() -> transcriptParserService.decodeStrictUtf8(new byte[]{0x41, 0x00, 0x42}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Binary file rejected");

        String rollingVtt = """
                WEBVTT

                00:00:05.000 --> 00:00:08.000
                <c>تأسست</c> الدولة الأموية في دمشق [Music]

                00:00:07.500 --> 00:00:12.000
                تأسست الدولة الأموية في دمشق على يد معاوية بن أبي سفيان عام واحد وأربعين للهجرة

                00:00:55.000 --> 00:01:05.000
                وشهدت هذه المرحلة تنظيم الدواوين وتطوير الإدارة المالية والبريد في الأمصار الإسلامية الكبرى
                """;
        TranscriptParserService.ParsedTranscript parsed = transcriptParserService.parseSrtOrVtt(rollingVtt);
        assertThat(parsed.plainText()).doesNotContain("[Music]").doesNotContain("<c>");
        assertThat(parsed.plainText().split("تأسست الدولة الأموية", -1).length - 1).isEqualTo(1);

        List<TranscriptParserService.TranscriptChunk> chunks =
                transcriptParserService.buildMarkedChunks(parsed.cues(), parsed.plainText(), 120);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).markedText()).contains("[00:05]").contains("[00:55]");
        assertThat(transcriptParserService.isTimestampValidForChunk(56, chunks.get(0))).isTrue();
        assertThat(transcriptParserService.isTimestampValidForChunk(250, chunks.get(0))).isFalse();

        TranscriptQualityGate.QualityAssessment badGate = transcriptQualityGate.evaluate(parsed, 1800);
        assertThat(badGate.passed()).isFalse();

        Episode ep = episodeRepository.save(new Episode(course, 81, "Episode 81", "vid81_abc12", 120));
        Map<String, Object> uploadBody = Map.of("playlistId", course.getId(), "confirmSave", true,
                "files", List.of(Map.of("filename", "../vid81_abc12.vtt", "content", rollingVtt)));
        mockMvc.perform(post("/api/admin/transcripts/upload").header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(uploadBody)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matchedCount").value(1)).andExpect(jsonPath("$.savedCount").value(1));
        assertThat(transcriptRepository.findByEpisodeId(ep.getId()).orElseThrow().isUsableForGeneration()).isFalse();
        mockMvc.perform(post("/api/admin/episodes/" + ep.getId() + "/transcript/use-anyway").header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.useAnyway").value(true)).andExpect(jsonPath("$.usableForGeneration").value(true));
    }

    @Test
    void claudeNoTemperatureAutoCaptionFetchGemini503FallbackAndLookaheadWork() throws Exception {
        assertThat(GeminiTextClient.normalizeThinkingLevel("minimal")).isEqualTo("low");
        assertThat(GeminiTextClient.normalizeThinkingLevel("HIGH")).isEqualTo("high");
        CandidateMcq candidate = new CandidateMcq("ما السبب الرئيس لإنشاء ديوان الخاتم في دمشق؟", QuestionType.REASONING, "إنشاء ديوان الخاتم", 55,
                List.of(new CandidateOption("لمنع تزوير الرسائل الرسمية وضمان سرية الأوامر الصادرة عن الخليفة في دمشق", true),
                        new CandidateOption("لجمع الضرائب والمكوس في ولايات المشرق", false),
                        new CandidateOption("لتدريب الجند وتجهيز الثغور البحرية", false),
                        new CandidateOption("لبناء السفن التجارية في البحر المتوسط", false)));
        CandidateMcq guarded = CrossModelQuizPipelineService.enforceCorrectOptionNeverLongest(candidate);
        int correctLen = guarded.options().stream().filter(CandidateOption::isCorrect).findFirst().orElseThrow().optionText().length();
        int maxOther = guarded.options().stream().filter(o -> !o.isCorrect()).mapToInt(o -> o.optionText().length()).max().orElse(0);
        assertThat(correctLen).isLessThanOrEqualTo(maxOther);

        RestTemplate origGeminiRt = geminiTextClient.getRestTemplate(), origClaudeRt = claudeClient.getRestTemplate(), origCapRt = youTubeCaptionService.getRestTemplate();
        RestTemplate mockGeminiRt = mock(RestTemplate.class), mockClaudeRt = mock(RestTemplate.class), mockCapRt = mock(RestTemplate.class);
        geminiTextClient.setRestTemplate(mockGeminiRt); claudeClient.setRestTemplate(mockClaudeRt); youTubeCaptionService.setRestTemplate(mockCapRt);
        blindTestService.setBackoffBaseMs(0L);

        try {
            String watchHtml = "<html><script>var p={\"captionTracks\":[{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=t&lang=ar\",\"languageCode\":\"ar\"}]};</script></html>";
            String json3Caps = "{\"events\":[{\"tStartMs\":5000,\"segs\":[{\"utf8\":\"تأسست الدولة الأموية في دمشق وشهدت تنظيم الدواوين والخراج والبريد في الأمصار الإسلامية.\"}]},"
                    + "{\"tStartMs\":55000,\"segs\":[{\"utf8\":\"أمر الخليفة بإنشاء ديوان الخاتم لمنع تزوير الرسائل الرسمية وحفظ أسرار الدولة.\"}]},"
                    + "{\"tStartMs\":135000,\"segs\":[{\"utf8\":\"وتطورت العلوم والترجمة والعمارة الإسلامية في الحواضر الكبرى عبر القرون.\"}]}]}";
            when(mockCapRt.exchange(contains("youtube.com/watch"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(watchHtml, HttpStatus.OK));
            when(mockCapRt.exchange(any(java.net.URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(json3Caps, HttpStatus.OK));

            when(mockGeminiRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "503 Overloaded", HttpHeaders.EMPTY,
                            "{\"error\":{\"message\":\"Model overloaded\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

            String pass1Json = Phase5GeminiTestSupport.buildPass1Json(objectMapper, "ClaudeAuto", 12, 2);
            String pass2Json = Phase5GeminiTestSupport.buildPass2Json(objectMapper, 12, 2);
            String blindJson = "{\"answers\":[]}";
            when(mockClaudeRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenAnswer(inv -> {
                        HttpEntity<String> ent = inv.getArgument(2);
                        String body = ent.getBody();
                        assertThat(body).doesNotContain("\"temperature\"").doesNotContain("minimal");
                        String reply = body.contains("verifiedMcqIndices") ? pass2Json
                                : (body.contains("blind multiple-choice") ? blindJson : pass1Json);
                        String wrapped = objectMapper.writeValueAsString(Map.of("content", List.of(Map.of("type", "text", "text", reply))));
                        return new ResponseEntity<>(wrapped, HttpStatus.OK);
                    });

            Playlist autoCourse = new Playlist("Auto Lookahead Course", "PL_AUTO_N1", "Auto N+1");
            autoCourse.setPublished(true);
            autoCourse = playlistRepository.save(autoCourse);
            Episode epN = episodeRepository.save(new Episode(autoCourse, 1, "Ep N", "vidAuto_epN1", 600));
            Episode epN1 = episodeRepository.save(new Episode(autoCourse, 2, "Ep N+1", "vidAuto_epN2", 600));

            autoQuizGenerationService.clearLocks();
            assertThat(autoQuizGenerationService.triggerIfEpisodeBankIncomplete(epN.getId())).isTrue();

            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline
                    && (!autoQuizGenerationService.isEpisodeBankReady(epN.getId())
                    || !autoQuizGenerationService.isEpisodeBankReady(epN1.getId()))) {
                Thread.sleep(50);
            }
            assertThat(autoQuizGenerationService.isEpisodeBankReady(epN.getId())).isTrue();
            assertThat(autoQuizGenerationService.isEpisodeBankReady(epN1.getId())).isTrue();
            assertThat(transcriptRepository.findByEpisodeId(epN.getId())).isPresent()
                    .get().extracting(EpisodeTranscript::getSource).isEqualTo("AUTOFETCH");
            assertThat(transcriptRepository.findByEpisodeId(epN1.getId())).isPresent()
                    .get().extracting(EpisodeTranscript::getSource).isEqualTo("AUTOFETCH");
        } finally {
            geminiTextClient.setRestTemplate(origGeminiRt);
            claudeClient.setRestTemplate(origClaudeRt);
            youTubeCaptionService.setRestTemplate(origCapRt);
        }

        Episode ep = transactionTemplate.execute(st -> {
            Episode e = episodeRepository.save(new Episode(course, 82, "Episode 82", "vid82_abc12", 4500));
            Quiz quiz = quizRepository.save(new Quiz(e));
            int order = 1;
            for (int c = 0; c < 4; c++) {
                for (int i = 0; i < 5; i++) {
                    Question q = new Question(quiz, "سؤال المقطع " + c + " رقم " + i, order++);
                    q.setStatus(QuestionStatus.APPROVED); q.setActive(true); q.setChunkIndex(c);
                    q.getOptions().add(new QuestionOption(q, "أ", true));
                    q.getOptions().add(new QuestionOption(q, "ب", false));
                    q.getOptions().add(new QuestionOption(q, "ج", false));
                    q.getOptions().add(new QuestionOption(q, "د", false));
                    questionRepository.save(q);
                }
            }
            return e;
        });
        List<Question> approved = questionRepository.findByEpisodeIdWithOptions(ep.getId());
        List<Question> selected = quizService.selectTenQuestionsForAttempt(student, ep, approved, LocalDateTime.now());
        assertThat(selected).hasSize(10);
        Map<Integer, Long> perChunk = new HashMap<>();
        for (Question q : selected) perChunk.merge(q.getChunkIndex(), 1L, Long::sum);
        perChunk.values().forEach(count -> assertThat(count).isLessThanOrEqualTo(3L));
    }

    @Test
    void autoGenCapStudentReportImmediateRetireJobConflictAndSecurityBoundariesWork() throws Exception {
        Episode ep = episodeRepository.save(new Episode(course, 83, "Episode 83", "vid83_abc12", 1200));
        for (int i = 0; i < 3; i++) {
            GenerationJob job = new GenerationJob(ep, "gemini-3.8-flash", true);
            job.setStatus("FAILED");
            generationJobRepository.save(job);
        }
        assertThat(generationJobService.canTriggerAutoGenerationToday(ep.getId())).isFalse();

        GenerationJob runningJob = new GenerationJob(ep, "claude-sonnet-5-5", false);
        runningJob.setStatus("RUNNING");
        runningJob = generationJobRepository.save(runningJob);

        mockMvc.perform(post("/api/admin/episodes/" + ep.getId() + "/generation-jobs")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"writer\":\"claude\"}"))
                .andExpect(status().isConflict());

        generationJobService.recoverStaleRunningJobs();
        assertThat(generationJobRepository.findById(runningJob.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");

        Question q = transactionTemplate.execute(st -> {
            Quiz quiz = quizRepository.save(new Quiz(ep));
            Question item = new Question(quiz, "سؤال للمراجعة؟", 1);
            item.setStatus(QuestionStatus.APPROVED); item.setActive(true); item.setSource("AUTO");
            item.getOptions().add(new QuestionOption(item, "١", true));
            item.getOptions().add(new QuestionOption(item, "٢", false));
            item.getOptions().add(new QuestionOption(item, "٣", false));
            item.getOptions().add(new QuestionOption(item, "٤", false));
            return questionRepository.save(item);
        });
        exposureRepository.save(new StudentQuestionExposure(student, ep, q, LocalDateTime.now()));
        quizAttemptRepository.save(new QuizAttempt(student, ep, WatchMode.ARABIC_SUB, 80.0, false, BigDecimal.ZERO));

        mockMvc.perform(post("/api/student/questions/" + q.getId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Unclear option\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportCount").value(1))
                .andExpect(jsonPath("$.cooldownReset").value(true));

        Question retired = questionRepository.findById(q.getId()).orElseThrow();
        assertThat(retired.getStatus()).isEqualTo(QuestionStatus.RETIRED);
        assertThat(retired.isActive()).isFalse();

        mockMvc.perform(get("/api/admin/episodes/" + ep.getId() + "/transcript").header(HttpHeaders.AUTHORIZATION, studentBearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/episodes/" + ep.getId() + "/generation-jobs").header(HttpHeaders.AUTHORIZATION, studentBearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/ai/test-connection").header(HttpHeaders.AUTHORIZATION, studentBearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep.getId() + "/unlock-quiz").header(HttpHeaders.AUTHORIZATION, studentBearer)).andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep.getId() + "/unlock-quiz")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Watched with family\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizUnlocked").value(true))
                .andExpect(jsonPath("$.verifiedWatchSeconds").value(0))
                .andExpect(jsonPath("$.adminUnlockedBy").value("phase8admin"));
    }
}
