// Verifies automatic fallback from Claude to GeminiTextClient on HTTP 400/402/403/429/5xx billing/credit errors across all 3 passes.
package com.arabicquest;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase9_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase9admin", "ADMIN_PASSWORD=Phase9AdminPass#2026", "ADMIN_SECRET_PATH=phase9-hq",
        "JWT_SECRET=phase9-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "GEMINI_API_KEY=test-gemini-key-phase9-888", "GEMINI_MODEL=gemini-3.8-flash",
        "ANTHROPIC_API_KEY=test-claude-key-phase9-999", "CLAUDE_MODEL=claude-sonnet-5-5",
        "AUTO_QUIZ_GENERATION_ENABLED=true"
})
class Phase9ClaudeFallbackTest {

    private static final String OUT_OF_CREDITS_JSON =
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.\"}}";

    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private EpisodeTranscriptRepository transcriptRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TranscriptParserService transcriptParserService;
    @Autowired private GeminiTextClient geminiTextClient;
    @Autowired private ClaudeClient claudeClient;
    @Autowired private BlindTestService blindTestService;
    @Autowired private CrossModelQuizPipelineService pipelineService;
    @Autowired private AutoQuizGenerationService autoQuizGenerationService;
    @Autowired private GenerationJobService generationJobService;

    private RestTemplate origGeminiRt;
    private RestTemplate origClaudeRt;
    private Playlist course;

    @BeforeEach
    void setUp() {
        origGeminiRt = geminiTextClient.getRestTemplate();
        origClaudeRt = claudeClient.getRestTemplate();
        blindTestService.setBackoffBaseMs(0L);
        autoQuizGenerationService.clearLocks();
        course = playlistRepository.findAll().stream()
                .filter(p -> "Phase9 Course".equals(p.getTitle()))
                .findFirst()
                .orElseGet(() -> {
                    Playlist p = new Playlist("Phase9 Course", "PL_PHASE9", "Claude Fallback Test");
                    p.setPublished(true);
                    return playlistRepository.save(p);
                });
    }

    @AfterEach
    void tearDown() {
        geminiTextClient.setRestTemplate(origGeminiRt);
        claudeClient.setRestTemplate(origClaudeRt);
        autoQuizGenerationService.clearLocks();
    }

    @Test
    void claudeHttp400OutOfCreditsFallsBackToGeminiOnStoredVttAndSavesMcqsAndOpenQuestions() {
        assertThat(claudeClient.isConfigured()).isTrue();
        assertThat(geminiTextClient.isConfigured()).isTrue();
        assertThat(geminiTextClient.getModel()).isEqualTo("gemini-3.8-flash");

        Episode ep1 = saveEpisodeWithStoredVtt(91, "Episode 91 Stored VTT", "vid91_vtt01", 900);
        Episode ep2 = saveEpisodeWithStoredVtt(92, "Episode 92 Lookahead VTT", "vid92_vtt02", 900);

        RestTemplate mockClaudeRt = mock(RestTemplate.class);
        RestTemplate mockGeminiRt = mock(RestTemplate.class);
        claudeClient.setRestTemplate(mockClaudeRt);
        geminiTextClient.setRestTemplate(mockGeminiRt);

        AtomicInteger claudeCalls = new AtomicInteger();
        AtomicInteger geminiCalls = new AtomicInteger();
        AtomicReference<String> pass1GeminiBody = new AtomicReference<>();

        when(mockClaudeRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    claudeCalls.incrementAndGet();
                    throw HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST, "400 Bad Request", HttpHeaders.EMPTY,
                            OUT_OF_CREDITS_JSON.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                });

        String pass1Json = Phase5GeminiTestSupport.buildMultiFormatPass1Json(objectMapper, "GeminiFallback", 12, 2);
        String pass2Json = Phase5GeminiTestSupport.buildPass2Json(objectMapper, 12, 2);
        when(mockGeminiRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    geminiCalls.incrementAndGet();
                    String body = ((HttpEntity<String>) inv.getArgument(2)).getBody();
                    if (body.contains("verifiedMcqIndices")) {
                        return ResponseEntity.ok(Phase5GeminiTestSupport.wrapGeminiText(objectMapper, pass2Json));
                    }
                    if (body.contains("blind multiple-choice")) {
                        return ResponseEntity.ok(Phase5GeminiTestSupport.wrapGeminiText(objectMapper, "{\"answers\":[]}"));
                    }
                    pass1GeminiBody.set(body);
                    return ResponseEntity.ok(Phase5GeminiTestSupport.wrapGeminiText(objectMapper, pass1Json));
                });

        CrossModelQuizPipelineService.PipelineResult result = pipelineService.executePipeline(
                ep1.getId(), "claude", null, false, "MANUAL", null);

        assertThat(claudeCalls.get()).isEqualTo(1);
        assertThat(geminiCalls.get()).isGreaterThanOrEqualTo(3);
        assertThat(pass1GeminiBody.get()).isNotNull()
                .contains("<episode_transcript>")
                .contains("ديوان الخاتم")
                .doesNotContain("file_data");
        assertThat(result.writerModelUsed()).isEqualTo("gemini-3.8-flash");
        assertThat(result.verifierModelUsed()).isEqualTo("gemini-3.8-flash");
        assertThat(result.savedMcqs()).isEqualTo(12);
        assertThat(result.savedOpenQuestions()).isGreaterThanOrEqualTo(1);

        List<Question> savedMcqs = questionRepository.findByEpisodeIdWithOptions(ep1.getId());
        List<OpenQuestion> savedOpens = openQuestionRepository.findByEpisodeIdOrderByIdAsc(ep1.getId());
        assertThat(savedMcqs).hasSize(12).allMatch(q -> q.getOptions().size() == 4 && q.getEvidenceTimestamp() != null);
        assertThat(savedOpens).isNotEmpty();

        // Also verify automatic background run on Episode N+1 completes seamlessly via Gemini fallback
        GenerationJob autoJob = generationJobService.enqueueJob(ep2.getId(), claudeClient.getModel(), null, true);
        GenerationJob doneAutoJob = generationJobService.awaitJob(autoJob.getId(), 5000);
        assertThat(doneAutoJob.getStatus()).isEqualTo("DONE");
        assertThat(doneAutoJob.getSavedCount()).isEqualTo(12);
        // Single-model fallback (both writer & verifier = gemini-3.8-flash) must stay DRAFT under strict AutoApprovalPolicy
        assertThat(autoQuizGenerationService.isEpisodeBankReady(ep2.getId())).isFalse();
        assertThat(questionRepository.findByEpisodeIdWithOptions(ep2.getId()))
                .hasSize(12)
                .allMatch(q -> q.getStatus() == QuestionStatus.DRAFT && "AUTO".equals(q.getSource()) && q.isSingleModel());
    }

    @Test
    void pass2AndPass3ClaudeBillingQuotaErrorsFallBackToGemini_andBothUnavailableFailsCleanly() {
        assertThat(PipelineModelFallbackHelper.isClaudeFallbackError(
                new AiProviderClientException(400, "Claude API returned HTTP 400: credit balance is too low"))).isTrue();
        assertThat(PipelineModelFallbackHelper.isClaudeFallbackError(
                new AiProviderClientException(402, "Claude API returned HTTP 402: billing required"))).isTrue();
        assertThat(PipelineModelFallbackHelper.isClaudeFallbackError(
                new AiProviderClientException(403, "Claude API returned HTTP 403: API access turned off"))).isTrue();
        assertThat(PipelineModelFallbackHelper.isClaudeFallbackError(
                new IllegalStateException("Quota exceeded for organization"))).isTrue();
        assertThat(PipelineModelFallbackHelper.isClaudeFallbackError(
                new AssertionError("Fatal assertion error"))).isFalse();

        Episode epPass2 = saveEpisodeWithStoredVtt(93, "Episode 93 Pass2 Fallback", "vid93_vtt03", 900);
        RestTemplate mockClaudeRt = mock(RestTemplate.class);
        RestTemplate mockGeminiRt = mock(RestTemplate.class);
        claudeClient.setRestTemplate(mockClaudeRt);
        geminiTextClient.setRestTemplate(mockGeminiRt);

        AtomicInteger claudePass2Calls = new AtomicInteger();
        when(mockClaudeRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    claudePass2Calls.incrementAndGet();
                    throw HttpClientErrorException.create(
                            HttpStatus.FORBIDDEN, "403 Forbidden", HttpHeaders.EMPTY,
                            "{\"error\":{\"message\":\"Billing is turned off for this workspace\"}}".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8);
                });

        String pass1Json = Phase5GeminiTestSupport.buildMultiFormatPass1Json(objectMapper, "Pass2Fallback", 10, 1);
        String pass2Json = Phase5GeminiTestSupport.buildPass2Json(objectMapper, 10, 1);
        when(mockGeminiRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    String body = ((HttpEntity<String>) inv.getArgument(2)).getBody();
                    if (body.contains("verifiedMcqIndices")) {
                        return ResponseEntity.ok(Phase5GeminiTestSupport.wrapGeminiText(objectMapper, pass2Json));
                    }
                    if (body.contains("blind multiple-choice")) {
                        return ResponseEntity.ok(Phase5GeminiTestSupport.wrapGeminiText(objectMapper, "{\"answers\":[]}"));
                    }
                    return ResponseEntity.ok(Phase5GeminiTestSupport.wrapGeminiText(objectMapper, pass1Json));
                });

        // Writer is explicitly Gemini, so Pass 2 verifier tries Claude first, fails with 403 billing turned off, and falls back to Gemini
        CrossModelQuizPipelineService.PipelineResult pass2Res = pipelineService.executePipeline(
                epPass2.getId(), "gemini", null, false, "MANUAL", null);
        assertThat(claudePass2Calls.get()).isEqualTo(1);
        assertThat(pass2Res.savedMcqs()).isEqualTo(10);
        assertThat(pass2Res.savedOpenQuestions()).isEqualTo(1);

        // Verify direct Pass 3 BlindTestService fallback when Claude fails with out-of-credits
        AtomicBoolean geminiUnavail = new AtomicBoolean(false);
        AtomicBoolean claudeUnavail = new AtomicBoolean(false);
        CandidateMcq sampleMcq = new CandidateMcq("Why was Bayt al-Hikma founded?", QuestionType.REASONING, "بيت الحكمة", 45,
                List.of(new CandidateOption("Translation and astronomy", true),
                        new CandidateOption("Naval shipyards", false),
                        new CandidateOption("Coin minting", false),
                        new CandidateOption("Silk taxation", false)));
        List<BlindTestService.BlindTestedCandidate> blindTested = blindTestService.runBlindTestForChunk(
                List.of(sampleMcq), 1, true, geminiUnavail, claudeUnavail);
        assertThat(claudeUnavail.get()).isTrue();
        assertThat(geminiUnavail.get()).isFalse();
        assertThat(blindTested).hasSize(1);

        // Verify clean combined failure when both Claude and Gemini fail in the same job
        Episode epBothFail = saveEpisodeWithStoredVtt(94, "Episode 94 Both Fail", "vid94_vtt04", 900);
        when(mockGeminiRt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    throw HttpServerErrorException.create(
                            HttpStatus.SERVICE_UNAVAILABLE, "503 Service Unavailable", HttpHeaders.EMPTY,
                            "{\"error\":{\"message\":\"Gemini overloaded\"}}".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8);
                });

        assertThatThrownBy(() -> pipelineService.executePipeline(epBothFail.getId(), "claude", null, false, "MANUAL", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Both Claude and Gemini are unavailable for this job")
                .hasMessageContaining("Claude:")
                .hasMessageContaining("Gemini:");
    }

    private Episode saveEpisodeWithStoredVtt(int epNum, String title, String videoId, int durationSec) {
        String vtt = Phase5GeminiTestSupport.buildRealisticMultiChunkArabicVttOver60Min();
        TranscriptParserService.ParsedTranscript parsed = transcriptParserService.parseSrtOrVtt(vtt);
        List<TranscriptParserService.Cue> shortCues = parsed.cues().subList(0, Math.min(18, parsed.cues().size()));
        String plain = String.join(" ", shortCues.stream().map(TranscriptParserService.Cue::text).toList());
        return transactionTemplate.execute(st -> {
            Episode ep = new Episode(course, epNum, title, videoId, durationSec);
            ep.setTranscriptText(plain);
            ep = episodeRepository.save(ep);
            EpisodeTranscript et = new EpisodeTranscript();
            et.setEpisode(ep);
            et.setSource("UPLOAD");
            et.setCuesJson(transcriptParserService.toCuesJson(shortCues));
            et.setPlainText(plain);
            et.setWordCount(plain.split("\\s+").length);
            et.setQualityStatus("PASS");
            et.setQualityReport("{\"passed\":true}");
            et.setUseAnyway(false);
            et.setUploadedAt(LocalDateTime.now());
            transcriptRepository.save(et);
            return ep;
        });
    }
}
