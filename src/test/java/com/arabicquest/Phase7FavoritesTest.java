package com.arabicquest;

import com.arabicquest.model.Episode;
import com.arabicquest.model.Playlist;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.PlaylistRepository;
import com.arabicquest.repository.StudentFavoriteEpisodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase7_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase7admin",
        "ADMIN_PASSWORD=Phase7AdminPass!2026",
        "ADMIN_SECRET_PATH=phase7-vault",
        "JWT_SECRET=phase7-jwt-secret-key-with-plenty-of-entropy-over-32-chars"
})
class Phase7FavoritesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String adminBearer;

    @BeforeEach
    void loginAdmin() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.70.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"phase7admin\",\"password\":\"Phase7AdminPass!2026\"}"))
                .andExpect(status().isOk())
                .andReturn();
        this.adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void favoriteToggle_isIdempotent_perStudentIsolated_reflectedInSyllabus_andBlockedForUnpublishedCourses() throws Exception {
        long[] ids = transactionTemplate.execute(status -> {
            Playlist pubCourse = new Playlist("Published History Course", "PL_P7_PUB_01", "Published");
            pubCourse.setPublished(true);
            pubCourse.setArchived(false);
            pubCourse = playlistRepository.save(pubCourse);

            Episode ep1 = episodeRepository.save(new Episode(pubCourse, 1, "Episode 1: Córdoba", "p7Vid000001", 1200));
            Episode ep2 = episodeRepository.save(new Episode(pubCourse, 2, "Episode 2: Baghdad", "p7Vid000002", 1500));

            Playlist draftCourse = new Playlist("Draft Secret Course", "PL_P7_DRAFT_01", "Unpublished");
            draftCourse.setPublished(false);
            draftCourse = playlistRepository.save(draftCourse);
            Episode draftEp = episodeRepository.save(new Episode(draftCourse, 1, "Hidden Episode", "p7Vid000099", 900));

            return new long[]{pubCourse.getId(), ep1.getId(), ep2.getId(), draftEp.getId()};
        });

        long pubCourseId = ids[0];
        long ep1Id = ids[1];
        long ep2Id = ids[2];
        long draftEpId = ids[3];

        String studentABearer = createAndLoginStudent("fav_student_a", "FavPassA!2026", "10.70.1.10");
        String studentBBearer = createAndLoginStudent("fav_student_b", "FavPassB!2026", "10.70.1.11");

        // Initially neither episode is favourited
        mockMvc.perform(get("/api/student/courses/" + pubCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentABearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[0].favorited").value(false))
                .andExpect(jsonPath("$.episodes[1].favorited").value(false));

        // Favourite Episode 2 twice (idempotent unique constraint check)
        mockMvc.perform(post("/api/student/episodes/" + ep2Id + "/favorite")
                        .header(HttpHeaders.AUTHORIZATION, studentABearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId").value(ep2Id))
                .andExpect(jsonPath("$.favorited").value(true));

        mockMvc.perform(post("/api/student/episodes/" + ep2Id + "/favorite")
                        .header(HttpHeaders.AUTHORIZATION, studentABearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(true));

        assertThat(studentFavoriteEpisodeRepository.count()).isEqualTo(1L);

        // Student A sees ep2 favourited in syllabus & episodes endpoints; Student B still sees false
        mockMvc.perform(get("/api/student/courses/" + pubCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentABearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[0].favorited").value(false))
                .andExpect(jsonPath("$.episodes[1].favorited").value(true));

        mockMvc.perform(get("/api/student/episodes").param("courseId", String.valueOf(pubCourseId))
                        .header(HttpHeaders.AUTHORIZATION, studentABearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].favorited").value(false))
                .andExpect(jsonPath("$[1].favorited").value(true));

        mockMvc.perform(get("/api/student/courses/" + pubCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentBBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[0].favorited").value(false))
                .andExpect(jsonPath("$.episodes[1].favorited").value(false));

        // Student cannot favourite an episode belonging to an unpublished course (403)
        mockMvc.perform(post("/api/student/episodes/" + draftEpId + "/favorite")
                        .header(HttpHeaders.AUTHORIZATION, studentABearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        // Un-favourite Episode 2 (twice — idempotent)
        mockMvc.perform(delete("/api/student/episodes/" + ep2Id + "/favorite")
                        .header(HttpHeaders.AUTHORIZATION, studentABearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false));

        mockMvc.perform(delete("/api/student/episodes/" + ep2Id + "/favorite")
                        .header(HttpHeaders.AUTHORIZATION, studentABearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false));

        assertThat(studentFavoriteEpisodeRepository.count()).isZero();

        mockMvc.perform(get("/api/student/courses/" + pubCourseId)
                        .header(HttpHeaders.AUTHORIZATION, studentABearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[1].favorited").value(false));
    }

    private String createAndLoginStudent(String username, String password, String ip) throws Exception {
        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"fullName\":\"" + username + "\"}"))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr(ip); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return "Bearer " + body.get("token").asText();
    }
}
