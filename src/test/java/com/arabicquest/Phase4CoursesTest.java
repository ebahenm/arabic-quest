package com.arabicquest;

import com.arabicquest.config.DataInitializer;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase4_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase4admin",
        "ADMIN_PASSWORD=Phase4AdminPass!2026",
        "ADMIN_SECRET_PATH=phase4-vault",
        "JWT_SECRET=phase4-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "app.watch.required-cap-seconds=1800",
        "app.quiz.retry-cooldown-seconds=600"
})
class Phase4CoursesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private OpenQuestionRepository openQuestionRepository;

    @Autowired
    private WatchSessionRepository watchSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String adminBearer;

    @BeforeEach
    void loginAdmin() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.40.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"phase4admin\",\"password\":\"Phase4AdminPass!2026\"}"))
                .andExpect(status().isOk())
                .andReturn();
        this.adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void newCoursesDefaultToUnpublished_andStudentsCannotSeeOrAccessUnpublishedOrArchivedCourses() throws Exception {
        long[] ids = transactionTemplate.execute(status -> {
            // 1. Brand-new Playlist defaults to published = false
            Playlist draft = new Playlist("Secret Draft Course", "PL_P4_DRAFT_01", "Unpublished draft");
            assertThat(draft.isPublished()).isFalse();
            draft = playlistRepository.save(draft);
            Episode draftEp = episodeRepository.save(new Episode(draft, 1, "Draft Ep 1", "VIDP4DRFT1", 1800));

            // 2. Archived course (even with published = true) must also be hidden from students
            Playlist archived = new Playlist("Archived Old Course", "PL_P4_ARCH_01", "Archived");
            archived.setPublished(true);
            archived.setArchived(true);
            archived = playlistRepository.save(archived);
            Episode archEp = episodeRepository.save(new Episode(archived, 1, "Archived Ep 1", "VIDP4ARCH1", 1800));

            // 3. Two published courses sorted by sortOrder
            Playlist pubLater = new Playlist("Ottoman Era", "PL_P4_PUB_20", "Second in catalog");
            pubLater.setPublished(true);
            pubLater.setSortOrder(20);
            pubLater.setCoverImageUrl("/images/hero-800.webp");
            pubLater.setRateArabicSub(new BigDecimal("15.00"));
            pubLater.setRateEnglishDub(new BigDecimal("10.00"));
            pubLater.setCompletionBonus(new BigDecimal("100.00"));
            pubLater = playlistRepository.save(pubLater);
            episodeRepository.save(new Episode(pubLater, 1, "Ottoman Ep 1", "VIDP4OTT01", 1800));
            episodeRepository.save(new Episode(pubLater, 2, "Ottoman Ep 2", "VIDP4OTT02", 1800));

            Playlist pubFirst = new Playlist("Rashidun Era", "PL_P4_PUB_10", "First in catalog");
            pubFirst.setPublished(true);
            pubFirst.setSortOrder(10);
            pubFirst.setRateArabicSub(new BigDecimal("20.00"));
            pubFirst.setRateEnglishDub(new BigDecimal("12.00"));
            pubFirst.setCompletionBonus(new BigDecimal("150.00"));
            pubFirst = playlistRepository.save(pubFirst);
            episodeRepository.save(new Episode(pubFirst, 1, "Rashidun Ep 1", "VIDP4RSH01", 1800));
            episodeRepository.save(new Episode(pubFirst, 2, "Rashidun Ep 2", "VIDP4RSH02", 1800));
            episodeRepository.save(new Episode(pubFirst, 3, "Rashidun Ep 3", "VIDP4RSH03", 1800));

            return new long[]{draft.getId(), draftEp.getId(), archived.getId(), archEp.getId(), pubFirst.getId(), pubLater.getId()};
        });

        long draftCourseId = ids[0];
        long draftEpId = ids[1];
        long archivedCourseId = ids[2];
        long pubFirstId = ids[4];
        long pubLaterId = ids[5];

        String studentBearer = createAndLoginStudent("catalogbro", "CatalogPass!123", "Catalog Brother", "10.40.1.1");

        String catalogJson = mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode catalog = objectMapper.readTree(catalogJson);
        assertThat(catalogJson).doesNotContain("Secret Draft Course").doesNotContain("Archived Old Course");

        // Verify ordering (pubFirst before pubLater) and possibleAmount calculations
        int idxFirst = -1;
        int idxLater = -1;
        for (int i = 0; i < catalog.size(); i++) {
            long cId = catalog.get(i).get("id").asLong();
            if (cId == pubFirstId) {
                idxFirst = i;
                assertThat(catalog.get(i).get("totalEpisodes").asInt()).isEqualTo(3);
                // 3 * $20 + $150 = $210.00
                assertThat(new BigDecimal(catalog.get(i).get("possibleAmount").asText())).isEqualByComparingTo("210.00");
            } else if (cId == pubLaterId) {
                idxLater = i;
                assertThat(catalog.get(i).get("totalEpisodes").asInt()).isEqualTo(2);
                // 2 * $15 + $100 = $130.00
                assertThat(new BigDecimal(catalog.get(i).get("possibleAmount").asText())).isEqualByComparingTo("130.00");
            }
        }
        assertThat(idxFirst).isGreaterThanOrEqualTo(0);
        assertThat(idxLater).isGreaterThan(idxFirst);

        // Student cannot fetch unpublished or archived course detail, episodes, heartbeat, or quiz (403 Forbidden)
        mockMvc.perform(get("/api/student/courses/" + draftCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/student/courses/" + archivedCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/student/episodes?courseId=" + draftCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/student/episodes/" + draftEpId + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":5}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/student/quizzes/" + draftEpId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void preUpgradeLegacyPlaylistRowWithNullPublishedBackfillsToPublishedCourse() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO playlists (title, youtube_playlist_id, description, arabic_reward_rate, english_reward_rate, completion_bonus, min_episodes_for_bonus, published, archived, sort_order, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)",
                "Legacy Production Course",
                "PL_LEGACY_NEON_01",
                "Pre-existing production row",
                new BigDecimal("15.00"),
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                1,
                Timestamp.valueOf(LocalDateTime.now())
        );

        transactionTemplate.executeWithoutResult(status ->
                DataInitializer.backfillExistingDatabaseCourses(playlistRepository));

        Playlist migrated = playlistRepository.findByYoutubePlaylistId("PL_LEGACY_NEON_01").orElseThrow();
        assertThat(migrated.isPublished()).isTrue();
        assertThat(migrated.isArchived()).isFalse();
        assertThat(migrated.getRateArabicSub()).isEqualByComparingTo("15.00");
        assertThat(migrated.getRateEnglishDub()).isEqualByComparingTo("10.00");
        assertThat(migrated.getCompletionBonus()).isEqualByComparingTo("100.00");

        String studentBearer = createAndLoginStudent("legacybro", "LegacyPass!123", "Legacy Brother", "10.40.1.2");
        String catalogJson = mockMvc.perform(get("/api/student/courses")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(catalogJson).contains("Legacy Production Course");
    }

    @Test
    void courseSyllabusAndCatalogTrackProgressAndEarnedAmount() throws Exception {
        long[] ids = transactionTemplate.execute(status -> {
            Playlist c = new Playlist("Mamluk History", "PL_P4_SYLLABUS", "Two-part course");
            c.setPublished(true);
            c.setSortOrder(5);
            c.setRateArabicSub(new BigDecimal("15.00"));
            c.setRateEnglishDub(new BigDecimal("10.00"));
            c.setCompletionBonus(new BigDecimal("100.00"));
            c = playlistRepository.save(c);

            Episode e1 = episodeRepository.save(new Episode(c, 1, "Mamluk Ep 1", "VIDP4MAM01", 1800));
            Episode e2 = episodeRepository.save(new Episode(c, 2, "Mamluk Ep 2", "VIDP4MAM02", 1800));
            return new long[]{c.getId(), e1.getId(), e2.getId()};
        });
        long courseId = ids[0];
        long ep1Id = ids[1];

        Map<String, String> correctByQ = seedApprovedBank(ep1Id, "P4_MAM");
        String studentBearer = createAndLoginStudent("syllabusbro", "SyllabusPass!123", "Syllabus Brother", "10.40.1.3");
        User student = userRepository.findByUsername("syllabusbro").orElseThrow();

        transactionTemplate.executeWithoutResult(status -> {
            Episode ep = episodeRepository.findById(ep1Id).orElseThrow();
            WatchSession ws = new WatchSession(student, ep, WatchMode.ARABIC_SUB);
            ws.setModeLocked(true);
            ws.setVerifiedWatchSeconds(1800);
            ws.setVideoEnded(true);
            watchSessionRepository.save(ws);
        });

        String quizJson = mockMvc.perform(get("/api/student/quizzes/" + ep1Id)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode quizView = objectMapper.readTree(quizJson);
        Map<Long, Long> answers = new HashMap<>();
        for (JsonNode qNode : quizView.get("questions")) {
            String expected = correctByQ.get(qNode.get("questionText").asText());
            for (JsonNode opt : qNode.get("options")) {
                if (expected.equals(opt.get("optionText").asText())) {
                    answers.put(qNode.get("id").asLong(), opt.get("id").asLong());
                }
            }
        }

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", quizView.get("sessionId").asText(),
                                "episodeId", ep1Id,
                                "watchMode", "ARABIC_SUB",
                                "answers", answers
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        mockMvc.perform(post("/api/student/episodes/" + ep1Id + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answerText\":\"The Mamluk institution combined military training with urban endowments that sustained scholarship across Cairo and Damascus.\"}"))
                .andExpect(status().isOk());

        String queueJson = mockMvc.perform(get("/api/admin/open-answers?status=PENDING")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long subId = objectMapper.readTree(queueJson).get(0).get("id").asLong();

        mockMvc.perform(post("/api/admin/open-answers/" + subId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Great historical synthesis.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/courses/" + courseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.course.passedEpisodes").value(1))
                .andExpect(jsonPath("$.course.totalEpisodes").value(2))
                .andExpect(jsonPath("$.course.progressPercentage").value(50))
                .andExpect(jsonPath("$.course.earnedAmount").value(15.0))
                .andExpect(jsonPath("$.course.possibleAmount").value(130.0))
                .andExpect(jsonPath("$.episodes[0].state").value("PASSED"))
                .andExpect(jsonPath("$.episodes[1].state").value("AVAILABLE"));
    }

    private Map<String, String> seedApprovedBank(long episodeId, String prefix) {
        return transactionTemplate.execute(status -> {
            Episode ep = episodeRepository.findById(episodeId).orElseThrow();
            Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElseGet(() -> quizRepository.save(new Quiz(ep)));
            Map<String, String> correctByQ = new HashMap<>();
            for (int i = 1; i <= 10; i++) {
                String qText = prefix + " Q" + i + "?";
                String correct = prefix + " Correct " + i;
                Question q = new Question(quiz, qText, i);
                q.setStatus(QuestionStatus.APPROVED);
                q.setActive(true);
                q.setType(QuestionType.RECALL);
                q.getOptions().add(new QuestionOption(q, correct, true));
                q.getOptions().add(new QuestionOption(q, prefix + " Wrong A " + i, false));
                q.getOptions().add(new QuestionOption(q, prefix + " Wrong B " + i, false));
                q.getOptions().add(new QuestionOption(q, prefix + " Wrong C " + i, false));
                quiz.getQuestions().add(q);
                correctByQ.put(qText, correct);
            }
            quizRepository.save(quiz);
            openQuestionRepository.save(new OpenQuestion(
                    ep,
                    "Explain the key theme of " + prefix + " in English.",
                    "Transcript quote",
                    QuestionStatus.APPROVED
            ));
            return correctByQ;
        });
    }

    private String createAndLoginStudent(String username, String password, String fullName, String ip) throws Exception {
        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "fullName", fullName
                        ))))
                .andExpect(status().isOk());

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr(ip); return req; })
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("token").asText();
    }
}
