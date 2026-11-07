package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.YouTubePlaylistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase5_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase5admin",
        "ADMIN_PASSWORD=Phase5AdminPass!2026",
        "ADMIN_SECRET_PATH=phase5-vault",
        "JWT_SECRET=phase5-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "YOUTUBE_API_KEY=yt-secret-header-key-999",
        "app.watch.required-cap-seconds=1800",
        "app.quiz.retry-cooldown-seconds=600"
})
class Phase5YouTubeCourseImportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private YouTubePlaylistService youTubePlaylistService;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private OpenQuestionRepository openQuestionRepository;

    @Autowired
    private OpenAnswerSubmissionRepository openAnswerSubmissionRepository;

    @Autowired
    private EpisodeRewardClaimRepository episodeRewardClaimRepository;

    @Autowired
    private PlaylistBonusClaimRepository playlistBonusClaimRepository;

    @Autowired
    private WatchSessionRepository watchSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private RestTemplate originalRestTemplate;
    private String adminBearer;

    @BeforeEach
    void setUp() throws Exception {
        this.originalRestTemplate = youTubePlaylistService.getRestTemplate();
        youTubePlaylistService.setYoutubeApiKey("yt-secret-header-key-999");

        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.50.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"phase5admin\",\"password\":\"Phase5AdminPass!2026\"}"))
                .andExpect(status().isOk())
                .andReturn();
        this.adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @AfterEach
    void tearDown() {
        youTubePlaylistService.setRestTemplate(this.originalRestTemplate);
        youTubePlaylistService.setYoutubeApiKey("yt-secret-header-key-999");
    }

    @Test
    void playlistIdExtraction_andIso8601DurationParsing_handleEdgeCasesCleanly() {
        assertThat(YouTubePlaylistService.extractPlaylistId(
                "https://www.youtube.com/playlist?list=PL_ABBASID_2026&si=xyz"))
                .isEqualTo("PL_ABBASID_2026");
        assertThat(YouTubePlaylistService.extractPlaylistId(
                "https://www.youtube.com/watch?v=abc12345&list=PL-GOLDEN-AGE_01&index=3"))
                .isEqualTo("PL-GOLDEN-AGE_01");
        assertThat(YouTubePlaylistService.extractPlaylistId("PL_RAW_ID_12345"))
                .isEqualTo("PL_RAW_ID_12345");

        assertThatThrownBy(() -> YouTubePlaylistService.extractPlaylistId("https://evil.example/no-id?list=bad$chars!"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(YouTubePlaylistService.parseIso8601DurationSeconds("PT45S")).isEqualTo(45);
        assertThat(YouTubePlaylistService.parseIso8601DurationSeconds("PT14M30S")).isEqualTo(870);
        assertThat(YouTubePlaylistService.parseIso8601DurationSeconds("PT1H5M20S")).isEqualTo(3920);
        assertThat(YouTubePlaylistService.parseIso8601DurationSeconds("P1DT2H")).isEqualTo(93_600);
        assertThat(YouTubePlaylistService.parseIso8601DurationSeconds("P0D")).isEqualTo(0);
    }

    @Test
    void youtubePreviewAndImport_paginates50PerPage_skipsPrivateVideos_usesHeaderKey_createsDraft_andEnforcesYoutubeDuration() throws Exception {
        List<URI> capturedUris = new ArrayList<>();
        List<HttpHeaders> capturedHeaders = new ArrayList<>();

        youTubePlaylistService.setRestTemplate(new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
                capturedUris.add(url);
                capturedHeaders.add(requestEntity.getHeaders());
                String path = url.getPath();
                String query = url.getQuery() != null ? url.getQuery() : "";

                String json;
                if (path.endsWith("/playlists")) {
                    json = """
                            {
                              "items": [{
                                "id": "PL_PAGINATED_55",
                                "status": { "privacyStatus": "public" },
                                "snippet": {
                                  "title": "History of Al-Andalus",
                                  "description": "52-part series on Cordoba and Granada",
                                  "thumbnails": {
                                    "high": { "url": "https://i.ytimg.com/vi/andalus/hqdefault.jpg" }
                                  }
                                }
                              }]
                            }
                            """;
                } else if (path.endsWith("/playlistItems") && !query.contains("pageToken=PAGE_2")) {
                    // Page 1: 48 public videos + 1 Private video + 1 Deleted video = 50 items
                    StringBuilder items = new StringBuilder();
                    for (int i = 1; i <= 48; i++) {
                        if (i > 1) items.append(",");
                        String vid = String.format("VID_AND_%02d", i);
                        items.append(String.format("""
                                {
                                  "snippet": { "title": "Al-Andalus Episode %d" },
                                  "contentDetails": { "videoId": "%s" },
                                  "status": { "privacyStatus": "public" }
                                }
                                """, i, vid));
                    }
                    items.append("""
                            ,{
                              "snippet": { "title": "Private video" },
                              "contentDetails": { "videoId": "VID_PRIVATE_1" },
                              "status": { "privacyStatus": "private" }
                            },
                            {
                              "snippet": { "title": "Deleted video" },
                              "contentDetails": { "videoId": "VID_DELETED_1" },
                              "status": { "privacyStatus": "privacyStatusUnspecified" }
                            }
                            """);
                    json = "{ \"nextPageToken\": \"PAGE_2\", \"items\": [" + items + "] }";
                } else if (path.endsWith("/playlistItems")) {
                    // Page 2: 4 public videos + 1 video that turns out private in videos.list
                    StringBuilder items = new StringBuilder();
                    for (int i = 49; i <= 52; i++) {
                        if (i > 49) items.append(",");
                        String vid = String.format("VID_AND_%02d", i);
                        items.append(String.format("""
                                {
                                  "snippet": { "title": "Al-Andalus Episode %d" },
                                  "contentDetails": { "videoId": "%s" },
                                  "status": { "privacyStatus": "public" }
                                }
                                """, i, vid));
                    }
                    items.append("""
                            ,{
                              "snippet": { "title": "Hidden Episode" },
                              "contentDetails": { "videoId": "VID_HIDDEN_99" },
                              "status": { "privacyStatus": "public" }
                            }
                            """);
                    json = "{ \"items\": [" + items + "] }";
                } else if (path.endsWith("/videos")) {
                    String idsParam = Arrays.stream(query.split("&"))
                            .filter(kv -> kv.startsWith("id="))
                            .map(kv -> kv.substring(3).replace("%2C", ","))
                            .findFirst()
                            .orElse("");
                    String[] requestedIds = idsParam.split(",");
                    assertThat(requestedIds.length).isLessThanOrEqualTo(50);

                    StringBuilder vItems = new StringBuilder();
                    boolean first = true;
                    for (String vid : requestedIds) {
                        if ("VID_HIDDEN_99".equals(vid)) {
                            if (!first) vItems.append(",");
                            first = false;
                            vItems.append("""
                                    {
                                      "id": "VID_HIDDEN_99",
                                      "contentDetails": { "duration": "PT10M" },
                                      "status": { "privacyStatus": "private", "uploadStatus": "processed" },
                                      "snippet": { "title": "Hidden Episode" }
                                    }
                                    """);
                            continue;
                        }
                        if (!first) vItems.append(",");
                        first = false;
                        // Ep 1 = 12 min (720s), Ep 2 = 40 min (2400s), others = 20 min (1200s)
                        String iso = "VID_AND_01".equals(vid) ? "PT12M"
                                : ("VID_AND_02".equals(vid) ? "PT40M" : "PT20M");
                        vItems.append(String.format("""
                                {
                                  "id": "%s",
                                  "contentDetails": { "duration": "%s" },
                                  "status": { "privacyStatus": "public", "uploadStatus": "processed" },
                                  "snippet": { "title": "Video %s" }
                                }
                                """, vid, iso, vid));
                    }
                    json = "{ \"items\": [" + vItems + "] }";
                } else {
                    throw new AssertionError("Unexpected YouTube API path: " + path);
                }

                @SuppressWarnings("unchecked")
                T body = (T) json;
                return ResponseEntity.ok(body);
            }
        });

        String studentBearer = createAndLoginStudent("p5_student_import", "10.50.0.10");

        // Student cannot call admin YouTube endpoints
        mockMvc.perform(post("/api/admin/courses/youtube-preview")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistUrlOrId\":\"PL_PAGINATED_55\"}"))
                .andExpect(status().isForbidden());

        // Step 1: Admin previews playlist (writes nothing to DB)
        long countBefore = playlistRepository.count();
        mockMvc.perform(post("/api/admin/courses/youtube-preview")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistUrlOrId\":\"https://www.youtube.com/playlist?list=PL_PAGINATED_55\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.youtubePlaylistId").value("PL_PAGINATED_55"))
                .andExpect(jsonPath("$.title").value("History of Al-Andalus"))
                .andExpect(jsonPath("$.episodeCount").value(52))
                .andExpect(jsonPath("$.skippedCount").value(3))
                .andExpect(jsonPath("$.alreadyImported").value(false));
        assertThat(playlistRepository.count()).isEqualTo(countBefore);

        // Verify API key was sent ONLY in X-Goog-Api-Key header and NEVER in URL
        assertThat(capturedUris).hasSize(5); // 1 playlist + 2 playlistItems pages + 2 batched videos calls (50 + 3)
        for (URI u : capturedUris) {
            assertThat(u.toString()).doesNotContain("yt-secret-header-key-999");
            assertThat(u.toString()).doesNotContain("key=");
        }
        for (HttpHeaders h : capturedHeaders) {
            assertThat(h.getFirst("X-Goog-Api-Key")).isEqualTo("yt-secret-header-key-999");
        }

        // Step 2: Save as Draft Course (created unpublished!)
        MvcResult importRes = mockMvc.perform(post("/api/admin/courses/youtube-import")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playlistUrlOrId": "https://www.youtube.com/playlist?list=PL_PAGINATED_55",
                                  "rateArabicSub": 18.00,
                                  "rateEnglishDub": 11.00,
                                  "completionBonus": 120.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.activeEpisodeCount").value(52))
                .andExpect(jsonPath("$.episodes[0].quizReadinessStatus").value("NO_BANK"))
                .andExpect(jsonPath("$.episodes[0].quizReadinessLabel").value("No bank"))
                .andReturn();

        JsonNode importedCourse = objectMapper.readTree(importRes.getResponse().getContentAsString());
        long courseId = importedCourse.get("id").asLong();
        long ep1Id = importedCourse.get("episodes").get(0).get("id").asLong();
        long ep2Id = importedCourse.get("episodes").get(1).get("id").asLong();

        // Duplicate import of same playlist is rejected cleanly with 409 Conflict
        mockMvc.perform(post("/api/admin/courses/youtube-import")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistUrlOrId\":\"PL_PAGINATED_55\"}"))
                .andExpect(status().isConflict());

        // Student cannot see draft course before publish
        mockMvc.perform(get("/api/student/courses/" + courseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        // Admin publishes the course
        mockMvc.perform(post("/api/admin/courses/" + courseId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        // Authoritative YouTube duration drives watch requirement: Ep 1 = min(1800, 720) = 720s; Ep 2 = min(1800, 2400) = 1800s
        MvcResult detailRes = mockMvc.perform(get("/api/student/courses/" + courseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailRes.getResponse().getContentAsString());
        JsonNode ep1Json = detail.get("episodes").get(0);
        JsonNode ep2Json = detail.get("episodes").get(1);
        assertThat(ep1Json.get("id").asLong()).isEqualTo(ep1Id);
        assertThat(ep1Json.get("durationSeconds").asInt()).isEqualTo(720);
        assertThat(ep1Json.get("watchStatus").get("requiredWatchSeconds").asInt()).isEqualTo(720);
        assertThat(ep2Json.get("id").asLong()).isEqualTo(ep2Id);
        assertThat(ep2Json.get("durationSeconds").asInt()).isEqualTo(2400);
        assertThat(ep2Json.get("watchStatus").get("requiredWatchSeconds").asInt()).isEqualTo(1800);
    }

    @Test
    void resync_keepsExistingQuizzesAndProgress_marksRemovedVideosInactive_andNeverRevokesEarnedCompletionBonus() throws Exception {
        final boolean[] afterPlaylistUpdate = {false};

        youTubePlaylistService.setRestTemplate(new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
                String path = url.getPath();
                String json;
                if (path.endsWith("/playlists")) {
                    json = """
                            {
                              "items": [{
                                "id": "PL_RESYNC_01",
                                "status": { "privacyStatus": "public" },
                                "snippet": { "title": "Mini Caliphate Course", "description": "2 episodes initially" }
                              }]
                            }
                            """;
                } else if (path.endsWith("/playlistItems")) {
                    if (!afterPlaylistUpdate[0]) {
                        json = """
                                {
                                  "items": [
                                    { "snippet": { "title": "Ep 1 Original" }, "contentDetails": { "videoId": "VID_RS_01" }, "status": { "privacyStatus": "public" } },
                                    { "snippet": { "title": "Ep 2 Original" }, "contentDetails": { "videoId": "VID_RS_02" }, "status": { "privacyStatus": "public" } }
                                  ]
                                }
                                """;
                    } else {
                        // VID_RS_01 updated title, VID_RS_02 removed, VID_RS_03 newly added!
                        json = """
                                {
                                  "items": [
                                    { "snippet": { "title": "Ep 1 Remastered Title" }, "contentDetails": { "videoId": "VID_RS_01" }, "status": { "privacyStatus": "public" } },
                                    { "snippet": { "title": "Ep 3 Bonus Lecture" }, "contentDetails": { "videoId": "VID_RS_03" }, "status": { "privacyStatus": "public" } }
                                  ]
                                }
                                """;
                    }
                } else {
                    if (!afterPlaylistUpdate[0]) {
                        json = """
                                {
                                  "items": [
                                    { "id": "VID_RS_01", "contentDetails": { "duration": "PT15M" }, "status": { "privacyStatus": "public", "uploadStatus": "processed" }, "snippet": { "title": "Ep 1 Original" } },
                                    { "id": "VID_RS_02", "contentDetails": { "duration": "PT16M" }, "status": { "privacyStatus": "public", "uploadStatus": "processed" }, "snippet": { "title": "Ep 2 Original" } }
                                  ]
                                }
                                """;
                    } else {
                        json = """
                                {
                                  "items": [
                                    { "id": "VID_RS_01", "contentDetails": { "duration": "PT25M" }, "status": { "privacyStatus": "public", "uploadStatus": "processed" }, "snippet": { "title": "Ep 1 Remastered Title" } },
                                    { "id": "VID_RS_03", "contentDetails": { "duration": "PT18M" }, "status": { "privacyStatus": "public", "uploadStatus": "processed" }, "snippet": { "title": "Ep 3 Bonus Lecture" } }
                                  ]
                                }
                                """;
                    }
                }
                @SuppressWarnings("unchecked")
                T body = (T) json;
                return ResponseEntity.ok(body);
            }
        });

        // 1. Import 2-episode course and publish it
        MvcResult importRes = mockMvc.perform(post("/api/admin/courses/youtube-import")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playlistUrlOrId\":\"PL_RESYNC_01\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode imported = objectMapper.readTree(importRes.getResponse().getContentAsString());
        long courseId = imported.get("id").asLong();
        long ep1Id = imported.get("episodes").get(0).get("id").asLong();
        long ep2Id = imported.get("episodes").get(1).get("id").asLong();

        mockMvc.perform(post("/api/admin/courses/" + courseId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk());

        // 2. Seed Ep 1 with READY bank (10 approved MCQs + 1 approved open) and Ep 2 initially with DRAFTS_ONLY, verify readiness labels, then approve Ep 2
        transactionTemplate.executeWithoutResult(status -> {
            seedEpisodeBank(ep1Id, QuestionStatus.APPROVED, 10, 1);
            seedEpisodeBank(ep2Id, QuestionStatus.DRAFT, 10, 1);
        });

        mockMvc.perform(get("/api/admin/courses/" + courseId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[0].quizReadinessStatus").value("READY"))
                .andExpect(jsonPath("$.episodes[1].quizReadinessStatus").value("DRAFTS_ONLY"));

        mockMvc.perform(post("/api/admin/episodes/" + ep2Id + "/questions/bulk-approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk());

        // 3. Student completes Ep 1 and Ep 2 -> admin approves written answers -> completion bonus ($100) awarded!
        String studentBearer = createAndLoginStudent("p5_resync_student", "10.50.0.20");
        long submission1Id = createPendingWrittenSubmission("p5_resync_student", ep1Id);
        mockMvc.perform(post("/api/admin/open-answers/" + submission1Id + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Great work on Ep 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardCreated").value(true))
                .andExpect(jsonPath("$.bonusAwarded").value(false));

        long submission2Id = createPendingWrittenSubmission("p5_resync_student", ep2Id);
        mockMvc.perform(post("/api/admin/open-answers/" + submission2Id + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Completed the whole course!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardCreated").value(true))
                .andExpect(jsonPath("$.bonusAwarded").value(true))
                .andExpect(jsonPath("$.bonusAmount").value(100.00));

        // 4. YouTube playlist changes: VID_RS_01 updated, VID_RS_02 removed, VID_RS_03 added -> Re-sync!
        afterPlaylistUpdate[0] = true;
        MvcResult resyncRes = mockMvc.perform(post("/api/admin/courses/" + courseId + "/resync")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedEpisodes").value(1))
                .andExpect(jsonPath("$.updatedEpisodes").value(1))
                .andExpect(jsonPath("$.deactivatedEpisodes").value(1))
                .andReturn();

        JsonNode resyncCourse = objectMapper.readTree(resyncRes.getResponse().getContentAsString()).get("course");
        long ep3Id = resyncCourse.get("episodes").get(2).get("id").asLong();

        // Ep 1 updated title + duration, kept its 10 approved MCQs
        Episode ep1After = episodeRepository.findById(ep1Id).orElseThrow();
        assertThat(ep1After.getTitle()).isEqualTo("Ep 1 Remastered Title");
        assertThat(ep1After.getDurationSeconds()).isEqualTo(1500);
        assertThat(questionRepository.countByQuizEpisodeIdAndStatusAndActiveTrue(ep1Id, QuestionStatus.APPROVED)).isEqualTo(10L);

        // Ep 2 NOT deleted — marked inactive, quizzes & student reward preserved!
        Episode ep2After = episodeRepository.findById(ep2Id).orElseThrow();
        assertThat(ep2After.isEnabled()).isFalse();
        assertThat(questionRepository.countByQuizEpisodeIdAndStatusAndActiveTrue(ep2Id, QuestionStatus.APPROVED)).isEqualTo(10L);

        // Student's earned completion bonus ($100) + 2 episode rewards ($30) = $130.00 NEVER revoked!
        mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned").value(130.00))
                .andExpect(jsonPath("$.bonusUnlocked").value(true))
                .andExpect(jsonPath("$.completionBonusAmount").value(100.00));

        mockMvc.perform(get("/api/student/courses/" + courseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.course.bonusEarned").value(true))
                .andExpect(jsonPath("$.course.earnedAmount").value(130.00));

        // 5. Admin can reorder episodes and edit course/episode metadata cleanly
        mockMvc.perform(put("/api/admin/courses/" + courseId + "/episodes/reorder")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"episodeIds\":[" + ep3Id + "," + ep1Id + "," + ep2Id + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[0].id").value(ep3Id))
                .andExpect(jsonPath("$.episodes[0].episodeNumber").value(1))
                .andExpect(jsonPath("$.episodes[1].id").value(ep1Id))
                .andExpect(jsonPath("$.episodes[1].episodeNumber").value(2));
    }

    private void seedEpisodeBank(long episodeId, QuestionStatus status, int mcqCount, int openCount) {
        Episode ep = episodeRepository.findById(episodeId).orElseThrow();
        Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElseGet(() -> quizRepository.save(new Quiz(ep)));
        for (int i = 1; i <= mcqCount; i++) {
            Question q = new Question(quiz, "Question " + i + " for ep " + episodeId, i);
            q.setStatus(status);
            q.setActive(true);
            q.setType(QuestionType.RECALL);
            q.getOptions().add(new QuestionOption(q, "Correct " + i, true));
            q.getOptions().add(new QuestionOption(q, "Wrong A " + i, false));
            q.getOptions().add(new QuestionOption(q, "Wrong B " + i, false));
            q.getOptions().add(new QuestionOption(q, "Wrong C " + i, false));
            quiz.getQuestions().add(q);
        }
        quizRepository.save(quiz);

        for (int j = 1; j <= openCount; j++) {
            OpenQuestion oq = new OpenQuestion(ep, "Explain key lesson " + j + " from episode " + episodeId, "Transcript quote", status);
            openQuestionRepository.save(oq);
        }
    }

    private long createPendingWrittenSubmission(String username, long episodeId) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Episode ep = episodeRepository.findById(episodeId).orElseThrow();
            User student = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException("Student not found"));
            OpenQuestion oq = openQuestionRepository.findByEpisodeIdAndStatusAndActiveTrueOrderByIdAsc(episodeId, QuestionStatus.APPROVED)
                    .getFirst();
            OpenAnswerSubmission sub = new OpenAnswerSubmission(
                    student,
                    ep,
                    oq,
                    WatchMode.ARABIC_SUB,
                    "This episode explains how governance, trade routes, and scholarship strengthened the historical state."
            );
            return openAnswerSubmissionRepository.save(sub).getId();
        }));
    }

    private String createAndLoginStudent(String username, String ip) throws Exception {
        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"username\":\"%s\",\"password\":\"StudentPass!2026\",\"fullName\":\"Phase5 Student\"}",
                                username)))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr(ip); return req; })
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"StudentPass!2026\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        String bearer = "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();

        // Touch /api/student/courses once or create a watch session if needed; we also have UserRepository!
        return bearer;
    }
}
