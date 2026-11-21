// Tests written text source parsing, time formats, written quality gate, pipeline timestamps (mocked AI), and strict admin-only isolation.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import com.arabicquest.service.TranscriptParserService.TranscriptChunk;
import com.arabicquest.service.WrittenTextSourceParser.ParsedWrittenSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase10_textsrc_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase10admin", "ADMIN_PASSWORD=Phase10AdminPass#2026", "ADMIN_SECRET_PATH=phase10-hq",
        "JWT_SECRET=phase10-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "GEMINI_API_KEY=test-gemini-key-p10", "GEMINI_MODEL=gemini-3.8-flash",
        "ANTHROPIC_API_KEY=test-claude-key-p10", "CLAUDE_MODEL=claude-sonnet-5-5"
})
class Phase10TextSourceImportTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private EpisodeTranscriptRepository transcriptRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private WrittenTextSourceService writtenTextSourceService;
    @Autowired private TranscriptQualityGate qualityGate;
    @Autowired private CrossModelQuizPipelineService pipelineService;
    @Autowired private ClaudeClient claudeClient;
    @Autowired private GeminiTextClient geminiTextClient;

    private static String adminBearer, studentBearer;
    private Playlist course;
    private Episode episode;

    private static final String SAMPLE_MULTI_SECTION_AR = """
            ## السؤال الأول: لماذا تأسس ديوان الخاتم في الدولة الأموية؟
            https://youtu.be/abc123XYZ?t=2m15s
            الإجابة المختصرة:
            تأسس ديوان الخاتم لمنع تزوير الرسائل والأوامر المالية الصادرة عن الخليفة معاوية بن أبي سفيان بعد حادثة تزوير مشهورة.
            الإجابة التفصيلية:
            أدى اتساع الدولة الإسلامية إلى كثرة المراسلات الإدارية بين دمشق والولايات البعيدة، فكان لا بد من توثيق الكتب الرسمية بختم الخليفة وحفظ نسخة منها في السجلات لضبط الإنفاق ومنع التلاعب بالأموال العامة.
            أبرز الخلاصات:
            - ديوان الخاتم مثل نقلة مؤسسية في تاريخ الإدارة الإسلامية والرقابة المالية.
            - حفظ النسخ الرسمية أرسى مبدأ الأرشفة الإدارية في الدولة.
            المراجع المذكورة:
            - تاريخ الطبري، المجلد الخامس، باب تنظيم الدواوين.
            - الكامل في التاريخ لابن الأثير حول إصلاحات معاوية الإدارية.

            ## السؤال الثاني: كيف نظم عبد الملك بن مروان تعريب الدواوين؟
            1:05:30
            الإجابة المختصرة:
            نقل عبد الملك بن مروان دواوين الخراج من الفارسية واليونانية إلى اللغة العربية لتوحيد الإدارة المركزية وتعزيز الاستقلال المالي.
            الإجابة التفصيلية:
            استعان عبد الملك بكتّاب متمرسين مثل صالح بن عبد الرحمن وسليمان بن سعد الخشني لتحويل السجلات المالية في العراق والشام إلى العربية مما مكن الدولة من مراجعة الحسابات بدقة وضرب الدينار الإسلامي المستقل.
            أبرز الخلاصات:
            - تعريب الدواوين ارتبط ارتباطاً وثيقاً بتعريب العملة والسيادة الاقتصادية.
            المراجع المذكورة:
            - فتوح البلدان للبلاذري، فصل أمر الديوان والنقود.
            """;

    @BeforeEach
    void setUp() throws Exception {
        if (adminBearer == null) {
            MvcResult res = mockMvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("10.100.0.1"); return req; })
                            .contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "XMLHttpRequest")
                            .content("{\"username\":\"phase10admin\",\"password\":\"Phase10AdminPass#2026\"}")).andExpect(status().isOk()).andReturn();
            adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
        }
        if (studentBearer == null) {
            studentBearer = Phase5GeminiTestSupport.createStudentAndLogin(mockMvc, objectMapper, adminBearer, "p10student", "10.100.0.2");
        }
        course = playlistRepository.findAll().stream().filter(p -> "Phase10 Written Course".equals(p.getTitle())).findFirst()
                .orElseGet(() -> {
                    Playlist p = new Playlist("Phase10 Written Course", "PL_PHASE10", "Written source tests");
                    p.setPublished(true);
                    return playlistRepository.save(p);
                });
        episode = episodeRepository.findByPlaylistIdOrderByEpisodeNumberAsc(course.getId()).stream().findFirst()
                .orElseGet(() -> {
                    Episode ep = new Episode(course, 1, "تنظيم الدواوين الأموية", "vid_p10_ep1", 5400);
                    ep.setEnabled(true);
                    return episodeRepository.save(ep);
                });
    }

    @Test
    void test1_timeFormatParsing_supportsMmSs_hMmSs_andYoutubeTParam() {
        assertThat(WrittenTextSourceService.parseStartTimeSeconds("04:20")).isEqualTo(260);
        assertThat(WrittenTextSourceService.parseStartTimeSeconds("[12:05]")).isEqualTo(725);
        assertThat(WrittenTextSourceService.parseStartTimeSeconds("1:05:30")).isEqualTo(3930);
        assertThat(WrittenTextSourceService.parseStartTimeSeconds("https://youtu.be/abc123?t=185")).isEqualTo(185);
        assertThat(WrittenTextSourceService.parseStartTimeSeconds("https://www.youtube.com/watch?v=abc123&t=2m15s")).isEqualTo(135);
        assertThat(WrittenTextSourceService.parseStartTimeSeconds("https://www.youtube.com/watch?v=abc123&t=1h2m3s")).isEqualTo(3723);
    }

    @Test
    void test2_multiSectionParsing_andWrittenQualityGateSkipsWpm() {
        ParsedWrittenSource parsed = writtenTextSourceService.parseWrittenSource(SAMPLE_MULTI_SECTION_AR, 5400);
        assertThat(parsed.sections()).hasSize(2);
        assertThat(parsed.sections().get(0).heading()).contains("ديوان الخاتم");
        assertThat(parsed.sections().get(0).startSeconds()).isEqualTo(135);
        assertThat(parsed.sections().get(0).endSeconds()).isEqualTo(3930);
        assertThat(parsed.sections().get(0).keyTakeaways()).contains("الأرشفة الإدارية");
        assertThat(parsed.sections().get(0).references()).contains("تاريخ الطبري");

        assertThat(parsed.sections().get(1).heading()).contains("تعريب الدواوين");
        assertThat(parsed.sections().get(1).startSeconds()).isEqualTo(3930);
        assertThat(parsed.sections().get(1).endSeconds()).isEqualTo(5400);
        assertThat(parsed.sections().get(1).references()).contains("فتوح البلدان");

        // Even though ~120 words over a 90-minute (5400s) episode would fail WPM (~1.3 WPM < 25),
        // the written-text quality gate skips WPM and passes on word count + Arabic ratio.
        TranscriptQualityGate.QualityAssessment qa = qualityGate.evaluateWrittenTextSource(
                parsed.plainText(), parsed.wordCount(), parsed.sections().size());
        assertThat(qa.passed()).isTrue();
        assertThat(qa.status()).isEqualTo("PASS");

        // Too-short text fails minimum word count
        TranscriptQualityGate.QualityAssessment shortQa = qualityGate.evaluateWrittenTextSource("نص عربي قصير جداً", 4, 1);
        assertThat(shortQa.passed()).isFalse();
        assertThat(shortQa.reasons().get(0)).contains("too short");

        // Mostly English text fails Arabic ratio
        String englishText = "word ".repeat(70).trim();
        TranscriptQualityGate.QualityAssessment engQa = qualityGate.evaluateWrittenTextSource(englishText, 70, 1);
        assertThat(engQa.passed()).isFalse();
        assertThat(engQa.reasons().get(0)).contains("Arabic script");
    }

    @Test
    void test3_previewConfirmUpload_andPipelineUsesSectionChunksAndDefaultTimestamp() throws Exception {
        String secretUrl = "https://internal-admin-notes.example.org/secret-article-99";
        // 1. Preview via multipart .md upload (confirmSave=false)
        MockMultipartFile mdFile = new MockMultipartFile("file", "ep1.md", "text/markdown",
                SAMPLE_MULTI_SECTION_AR.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/episodes/" + episode.getId() + "/text-source/upload")
                        .file(mdFile)
                        .param("sourceUrl", secretUrl)
                        .param("confirmSave", "false")
                        .header("Authorization", adminBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.sectionCount").value(2))
                .andExpect(jsonPath("$.qualityStatus").value("PASS"))
                .andExpect(jsonPath("$.sections[0].startTimeFormatted").value("02:15"));

        // 2. Confirm save via JSON endpoint
        String body = objectMapper.writeValueAsString(Map.of(
                "text", SAMPLE_MULTI_SECTION_AR, "sourceUrl", secretUrl, "confirmSave", true));
        mockMvc.perform(post("/api/admin/episodes/" + episode.getId() + "/text-source")
                        .header("Authorization", adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.source").value("TEXT_SOURCE"));

        EpisodeTranscript stored = transcriptRepository.findByEpisodeId(episode.getId()).orElseThrow();
        assertThat(stored.getSource()).isEqualTo("TEXT_SOURCE");
        assertThat(stored.getSourceUrl()).isEqualTo(secretUrl);

        List<TranscriptChunk> chunks = writtenTextSourceService.buildChunksFromTranscript(stored, episode.getDurationSeconds());
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).startSeconds()).isEqualTo(135);
        assertThat(chunks.get(0).endSeconds()).isEqualTo(3930);
        assertThat(chunks.get(1).startSeconds()).isEqualTo(3930);
        assertThat(chunks.get(1).endSeconds()).isEqualTo(5400);

        // 3. Run pipeline with mocked AI; omit evidenceMarker on one question so it defaults to section start!
        RestTemplate mockClaude = mock(RestTemplate.class);
        RestTemplate mockGemini = mock(RestTemplate.class);
        claudeClient.setRestTemplate(mockClaude);
        geminiTextClient.setRestTemplate(mockGemini);

        String pass1Json = """
                {"mcqs":[
                  {"questionText":"Why did Muawiya establish the Registry of the Seal (ديوان الخاتم)?",
                   "type":"REASONING","evidence":"To prevent forged official decrees after a forgery incident.",
                   "options":[
                     {"optionText":"To prevent forgery of official caliphal decrees and financial orders","isCorrect":true},
                     {"optionText":"To translate provincial tax registers from Persian into Arabic script","isCorrect":false},
                     {"optionText":"To mint the first independent gold Islamic dinar across Damascus","isCorrect":false},
                     {"optionText":"To collect historical chronicles compiled by al-Tabari and Ibn al-Athir","isCorrect":false}
                   ]}
                ],"openQuestions":[
                  {"promptText":"Explain the administrative significance of the Registry of the Seal (ديوان الخاتم) citing al-Tabari.",
                   "evidence":"Covers anti-forgery verification, archiving copies, and financial oversight."}
                ]}""";
        String claudeResp = objectMapper.writeValueAsString(Map.of(
                "stop_reason", "end_turn",
                "usage", Map.of("output_tokens", 250),
                "content", List.of(Map.of("type", "text", "text", pass1Json))));
        when(mockClaude.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(claudeResp));

        String pass2Json = "{\"verifiedMcqIndices\":[0],\"verifiedOpenIndices\":[0]}";
        String blindJson = "{\"answers\":[{\"index\":0,\"chosenIndex\":1}]}"; // Wrong guess -> survives blind test!
        String gemPass2 = objectMapper.writeValueAsString(Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", pass2Json)))))));
        String gemBlind = objectMapper.writeValueAsString(Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", blindJson)))))));
        when(mockGemini.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(gemPass2), ResponseEntity.ok(gemBlind),
                            ResponseEntity.ok(gemPass2), ResponseEntity.ok(gemBlind));

        CrossModelQuizPipelineService.PipelineResult result = pipelineService.executePipeline(
                episode.getId(), "claude", null, false, "MANUAL", null);
        assertThat(result.savedMcqs()).isGreaterThanOrEqualTo(1);
        assertThat(result.savedOpenQuestions()).isGreaterThanOrEqualTo(1);

        // Verify evidenceTimestamp defaulted to the section start (135s or 3930s)
        List<Question> savedQs = questionRepository.findByEpisodeIdWithOptions(episode.getId());
        assertThat(savedQs).isNotEmpty();
        assertThat(savedQs.get(0).getEvidenceTimestamp()).isIn(135, 3930);
    }

    @Test
    void test4_adminOnlyAccess_andNoTextSourceLeakInStudentOrPublicResponses() throws Exception {
        String secretMarker = "SECRET_WRITTEN_TEXT_UNIQUE_MARKER_987654";
        String secretUrl = "https://private-admin-source.example.org/never-leak-777";
        String textWithSecret = "## السؤال الأول: " + secretMarker + "\n01:00\n"
                + "الإجابة المختصرة: هذا نص عربي تجريبي طويل للتحقق من عدم تسريب محتوى النص المكتوب إلى الطلاب "
                + "في أي واجهة برمجية عامة أو طلابية على الإطلاق، بل يبقى محفوظاً للإدارة فقط مع الرابط المرجعي السري.";
        writtenTextSourceService.previewOrSave(episode.getId(), textWithSecret, secretUrl, true);

        // Students get 403 on all text-source endpoints
        mockMvc.perform(post("/api/admin/episodes/" + episode.getId() + "/text-source")
                        .header("Authorization", studentBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\",\"confirmSave\":false}"))
                .andExpect(status().isForbidden());

        MockMultipartFile f = new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/episodes/" + episode.getId() + "/text-source/upload").file(f)
                        .header("Authorization", studentBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/episodes/" + episode.getId() + "/text-source")
                        .header("Authorization", studentBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        // Verify NO leak in student or public endpoints
        List<String> studentAndPublicPaths = List.of(
                "/api/public/config",
                "/api/student/courses",
                "/api/student/courses/" + course.getId(),
                "/api/student/episodes?courseId=" + course.getId()
        );
        for (String path : studentAndPublicPaths) {
            String resp = mockMvc.perform(get(path)
                            .header("Authorization", studentBearer).header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(resp).as("Response for %s must never leak TEXT_SOURCE content", path).doesNotContain(secretMarker);
            assertThat(resp).as("Response for %s must never leak sourceUrl", path).doesNotContain(secretUrl);
        }
    }
}
