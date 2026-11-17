// Integration tests for Pipeline v2: multi-chunk >60m VTT, timestamp recovery, progress commits, stale-job expiry, cancel endpoint, and Throwable handling.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.arabicquest.service.PipelineCandidateNormalizer.ChunkNormalizationResult;
import com.arabicquest.service.PipelineCandidateNormalizer.ParsedPass1Batch;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private GenerationJobRepository generationJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private StudentQuestionExposureRepository exposureRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TranscriptParserService transcriptParserService;
    @Autowired private TranscriptQualityGate transcriptQualityGate;
    @Autowired private GeminiTextClient geminiTextClient;
    @Autowired private ClaudeClient claudeClient;
    @Autowired private BlindTestService blindTestService;
    @Autowired private CrossModelQuizPipelineService pipelineService;
    @Autowired private GenerationJobService generationJobService;
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
        String rollingVtt = "WEBVTT\n\n00:00:05.000 --> 00:00:08.000\n<c>تأسست</c> الدولة الأموية في دمشق [Music]\n\n"
                + "00:00:07.500 --> 00:00:12.000\nتأسست الدولة الأموية في دمشق على يد معاوية بن أبي سفيان عام واحد وأربعين للهجرة\n\n"
                + "00:00:55.000 --> 00:01:05.000\nوشهدت هذه المرحلة تنظيم الدواوين وتطوير الإدارة المالية والبريد في الأمصار الإسلامية الكبرى\n";
        TranscriptParserService.ParsedTranscript parsed = transcriptParserService.parseSrtOrVtt(rollingVtt);
        assertThat(parsed.plainText()).doesNotContain("[Music]").doesNotContain("<c>");
        assertThat(parsed.plainText().split("تأسست الدولة الأموية", -1).length - 1).isEqualTo(1);
        List<TranscriptParserService.TranscriptChunk> chunks = transcriptParserService.buildMarkedChunks(parsed.cues(), parsed.plainText(), 120);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).markedText()).contains("[00:05]").contains("[00:55]");
        assertThat(transcriptParserService.isTimestampValidForChunk(56, chunks.get(0))).isTrue();
        assertThat(transcriptParserService.isTimestampValidForChunk(250, chunks.get(0))).isFalse();
        assertThat(transcriptQualityGate.evaluate(parsed, 1800).passed()).isFalse();

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
    void timestampFormatsCueRecoveryLongestOptionPreservationMaxTokensRetryAndMultiChunkVttWork() throws Exception {
        assertThat(ClaudeClient.CONNECT_TIMEOUT_MS).isEqualTo(15_000);
        assertThat(ClaudeClient.READ_TIMEOUT_MS).isEqualTo(120_000);
        assertThat(PipelineCandidateNormalizer.parseFlexibleTimestampSeconds(125)).isEqualTo(125);
        assertThat(PipelineCandidateNormalizer.parseFlexibleTimestampSeconds("1620")).isEqualTo(1620);
        assertThat(PipelineCandidateNormalizer.parseFlexibleTimestampSeconds("12:45")).isEqualTo(765);
        assertThat(PipelineCandidateNormalizer.parseFlexibleTimestampSeconds("[4:05]")).isEqualTo(245);
        assertThat(PipelineCandidateNormalizer.parseFlexibleTimestampSeconds("65:30")).isEqualTo(3930);
        assertThat(PipelineCandidateNormalizer.parseFlexibleTimestampSeconds("1:05:30")).isEqualTo(3930);

        String fullCorrectText = "To prevent forgery of official caliphal correspondence and seal state decrees in Damascus (ديوان الخاتم)";
        CandidateMcq longestCorrect = new CandidateMcq("Why was the Registry of the Seal (ديوان الخاتم) created?", QuestionType.REASONING, "ديوان الخاتم", 60,
                List.of(new CandidateOption(fullCorrectText, true), new CandidateOption("Tax collection (الخراج)", false),
                        new CandidateOption("Naval defense (الثغور)", false), new CandidateOption("Coin minting (السكة)", false)));
        CandidateMcq kept = CrossModelQuizPipelineService.enforceCorrectOptionNeverLongest(longestCorrect);
        assertThat(kept).isNotNull();
        assertThat(kept.options().get(0).optionText()).isEqualTo(fullCorrectText);
        assertThat(PipelineCandidateNormalizer.isOptionRuleCompliant(kept)).isFalse();
        CandidateMcq forbidden = new CandidateMcq("Q?", QuestionType.RECALL, "ev", 10,
                List.of(new CandidateOption("All of the above", true), new CandidateOption("B", false), new CandidateOption("C", false), new CandidateOption("D", false)));
        assertThat(CrossModelQuizPipelineService.enforceCorrectOptionNeverLongest(forbidden)).isNull();

        String longVtt = Phase5GeminiTestSupport.buildRealisticMultiChunkArabicVttOver60Min();
        TranscriptParserService.ParsedTranscript parsedLong = transcriptParserService.parseSrtOrVtt(longVtt);
        List<TranscriptParserService.TranscriptChunk> longChunks = transcriptParserService.buildMarkedChunks(parsedLong.cues(), parsedLong.plainText(), 4080);
        assertThat(longChunks).hasSizeGreaterThanOrEqualTo(3);

        CandidateMcq nullTsCandidate = new CandidateMcq("What was established in Damascus (دمشق)?", QuestionType.REASONING,
                "أنشأ الخليفة ديوان الخاتم في دمشق لحفظ الرسائل الرسمية", null, longestCorrect.options());
        ChunkNormalizationResult normRes = PipelineCandidateNormalizer.normalizeChunkCandidates(
                new ParsedPass1Batch(List.of(nullTsCandidate), List.of()), longChunks.get(0), parsedLong.cues(), transcriptParserService);
        assertThat(normRes.counts().droppedNullTimestamp).isEqualTo(1);
        assertThat(normRes.counts().timestampValid).isEqualTo(1);
        assertThat(normRes.keptMcqs().get(0).evidenceTimestamp()).isNotNull().isBetween(15, 1500);

        RestTemplate origGeminiRt = geminiTextClient.getRestTemplate(), origClaudeRt = claudeClient.getRestTemplate();
        RestTemplate mockGeminiRt = mock(RestTemplate.class), mockClaudeRt = mock(RestTemplate.class);
        geminiTextClient.setRestTemplate(mockGeminiRt); claudeClient.setRestTemplate(mockClaudeRt);
        blindTestService.setBackoffBaseMs(0L);
        try {
            AtomicInteger geminiCalls = new AtomicInteger();
            when(mockGeminiRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenAnswer(inv -> {
                        geminiCalls.incrementAndGet();
                        throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429 Rate Limit", HttpHeaders.EMPTY,
                                "{\"error\":{\"message\":\"Quota exceeded\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                    });
            AtomicInteger pass1Calls = new AtomicInteger();
            String validBatch6 = Phase5GeminiTestSupport.buildMultiFormatPass1Json(objectMapper, "RetryBatch", 8, 1);
            String validBatch12 = Phase5GeminiTestSupport.buildMultiFormatPass1Json(objectMapper, "FullBatch", 10, 1);
            String pass2Json = Phase5GeminiTestSupport.buildPass2Json(objectMapper, 10, 1);
            when(mockClaudeRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenAnswer(inv -> {
                        String body = ((HttpEntity<String>) inv.getArgument(2)).getBody();
                        assertThat(body).doesNotContain("\"temperature\"");
                        if (body.contains("verifiedMcqIndices")) {
                            return new ResponseEntity<>(objectMapper.writeValueAsString(Map.of("stop_reason", "end_turn",
                                    "content", List.of(Map.of("type", "text", "text", "```json\n" + pass2Json + "\n```")))), HttpStatus.OK);
                        }
                        if (body.contains("blind multiple-choice")) {
                            return new ResponseEntity<>(objectMapper.writeValueAsString(Map.of("stop_reason", "end_turn",
                                    "content", List.of(Map.of("type", "text", "text", "{\"answers\":[]}")))), HttpStatus.OK);
                        }
                        int callNum = pass1Calls.incrementAndGet();
                        if (callNum == 1) {
                            return new ResponseEntity<>(objectMapper.writeValueAsString(Map.of("stop_reason", "max_tokens",
                                    "usage", Map.of("input_tokens", 500, "output_tokens", 8192),
                                    "content", List.of(Map.of("type", "text", "text", "{\"mcqs\":[{\"questionText\":\"Truncated")))), HttpStatus.OK);
                        }
                        String json = body.contains("generate 6") ? validBatch6 : validBatch12;
                        return new ResponseEntity<>(objectMapper.writeValueAsString(Map.of("stop_reason", "end_turn",
                                "content", List.of(Map.of("type", "text", "text", "```json\n" + json + "\n```")))), HttpStatus.OK);
                    });

            Episode epVtt = episodeRepository.save(new Episode(course, 84, "Long VTT Ep", "vid84_longvtt", 4080));
            Map<String, Object> uploadBody = Map.of("playlistId", course.getId(), "confirmSave", true,
                    "files", List.of(Map.of("filename", "vid84_longvtt.vtt", "content", longVtt)));
            mockMvc.perform(post("/api/admin/transcripts/upload").header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest")
                            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(uploadBody)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.savedCount").value(1));

            List<Integer> progressPoints = new ArrayList<>();
            CrossModelQuizPipelineService.PipelineResult res = pipelineService.executePipeline(
                    epVtt.getId(), "claude", null, false, "MANUAL", (stage, pct) -> progressPoints.add(pct));
            assertThat(geminiCalls.get()).isEqualTo(1);
            assertThat(progressPoints).isNotEmpty().endsWith(100);
            assertThat(res.savedMcqs()).isGreaterThanOrEqualTo(10);
            assertThat(res.savedOpenQuestions()).isGreaterThanOrEqualTo(1);

            when(mockClaudeRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenAnswer(inv -> { throw new AssertionError("Simulated fatal Throwable in Claude worker"); });
            Episode epErr = episodeRepository.save(new Episode(course, 85, "Throwable Ep", "vid85_throw", 600));
            epErr.setTranscriptText("محاضرة قصيرة حول تاريخ العلوم في الحواضر الإسلامية.");
            episodeRepository.save(epErr);
            GenerationJob errJob = generationJobService.enqueueJob(epErr.getId(), "claude-sonnet-5-5", epErr.getTranscriptText(), false);
            GenerationJob finishedErrJob = generationJobService.awaitJob(errJob.getId(), 5000);
            assertThat(finishedErrJob.getStatus()).isEqualTo("FAILED");
            assertThat(finishedErrJob.getSanitizedError()).contains("Simulated fatal Throwable");
        } finally {
            geminiTextClient.setRestTemplate(origGeminiRt);
            claudeClient.setRestTemplate(origClaudeRt);
        }
    }

    @Test
    void staleJobExpiryJobCancelStudentReportImmediateRetireAndSecurityBoundariesWork() throws Exception {
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
                        .contentType(MediaType.APPLICATION_JSON).content("{\"writerModel\":\"claude\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.jobId").value(runningJob.getId()));

        mockMvc.perform(post("/api/admin/generation-jobs/" + runningJob.getId() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage").value("CANCELLED"));

        GenerationJob staleJob = new GenerationJob(ep, "claude-sonnet-5-5", false);
        staleJob.setStatus("RUNNING");
        staleJob = generationJobRepository.save(staleJob);
        int expired = generationJobService.expireStaleActiveJobs(LocalDateTime.now().plusMinutes(1));
        assertThat(expired).isGreaterThanOrEqualTo(1);
        GenerationJob reloadedStale = generationJobRepository.findById(staleJob.getId()).orElseThrow();
        assertThat(reloadedStale.getStatus()).isEqualTo("FAILED");
        assertThat(reloadedStale.getSanitizedError()).contains("Timed out / stale");

        Question q = transactionTemplate.execute(st -> {
            Quiz quiz = quizRepository.save(new Quiz(ep));
            Question item = new Question(quiz, "سؤال للمراجعة؟", 1);
            item.setStatus(QuestionStatus.APPROVED); item.setActive(true); item.setSource("AUTO");
            for (int idx = 1; idx <= 4; idx++) item.getOptions().add(new QuestionOption(item, "خيار " + idx, idx == 1));
            return questionRepository.save(item);
        });
        exposureRepository.save(new StudentQuestionExposure(student, ep, q, LocalDateTime.now()));
        quizAttemptRepository.save(new QuizAttempt(student, ep, WatchMode.ARABIC_SUB, 80.0, false, BigDecimal.ZERO));

        mockMvc.perform(post("/api/student/questions/" + q.getId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Unclear option\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reportCount").value(1)).andExpect(jsonPath("$.cooldownReset").value(true));
        assertThat(questionRepository.findById(q.getId()).orElseThrow().getStatus()).isEqualTo(QuestionStatus.RETIRED);

        mockMvc.perform(post("/api/admin/generation-jobs/" + runningJob.getId() + "/cancel").header(HttpHeaders.AUTHORIZATION, studentBearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep.getId() + "/unlock-quiz")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Watched with family\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quizUnlocked").value(true)).andExpect(jsonPath("$.adminUnlockedBy").value("phase8admin"));

        mockMvc.perform(get("/mobile.css")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("100dvh")));
        mockMvc.perform(get("/quiz-empty-state.js")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("innerHTML"))));
        mockMvc.perform(get("/mobile-episode-list.js")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("innerHTML"))));
        mockMvc.perform(get("/mobile-watch.js")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("innerHTML"))));
        mockMvc.perform(get("/phase8-hq/admin-mobile.css")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(".table-wrap")));
        mockMvc.perform(get("/phase8-hq/modules/components/admin-mobile-cards.js")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("innerHTML"))));
    }
}
