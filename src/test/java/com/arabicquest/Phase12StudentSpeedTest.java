// Verifies that loading 67 student episodes uses a small, constant number of SQL queries via Hibernate Statistics.
package com.arabicquest;

import com.arabicquest.dto.EpisodeStudentResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.CourseService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase12_speed_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "ADMIN_USERNAME=p12admin",
        "ADMIN_PASSWORD=Phase12StrongPassword!99",
        "ADMIN_SECRET_PATH=p12-secret-hq",
        "JWT_SECRET=phase12-student-speed-jwt-secret-key-well-over-32-chars",
        "app.watch.required-cap-seconds=1800",
        "app.quiz.retry-cooldown-seconds=3600"
})
class Phase12StudentSpeedTest {

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CourseService courseService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private WatchSessionRepository watchSessionRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private EpisodeRewardClaimRepository episodeRewardClaimRepository;
    @Autowired private StudentEpisodeOverrideRepository studentEpisodeOverrideRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void loading67EpisodesUsesSmallConstantSqlQueryCount() {
        User student = userRepository.save(new User(
                "speedstudent67",
                passwordEncoder.encode("StudentPass!123"),
                "Speed Student",
                Role.ROLE_STUDENT));

        Playlist bigCourse = createCourseWithEpisodes("PL_SPEED_67", "67-Episode Series", 67);
        Playlist smallCourse = createCourseWithEpisodes("PL_SPEED_5", "5-Episode Series", 5);

        List<Episode> bigEpisodes = episodeRepository.findActiveByPlaylistIdOrderByEpisodeNumberAsc(bigCourse.getId());
        assertThat(bigEpisodes).hasSize(67);

        // Seed realistic student data across multiple episodes (passed claim, watch sessions, failed attempt cooldown, override)
        Episode ep1 = bigEpisodes.get(0);
        Episode ep2 = bigEpisodes.get(1);
        Episode ep3 = bigEpisodes.get(2);

        episodeRewardClaimRepository.save(new EpisodeRewardClaim(student, ep1, WatchMode.ARABIC_SUB, new BigDecimal("15.00")));
        WatchSession ws2 = new WatchSession(student, ep2, WatchMode.ARABIC_SUB);
        ws2.setVerifiedWatchSeconds(900);
        ws2.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(2));
        watchSessionRepository.save(ws2);

        QuizAttempt failedAttempt = new QuizAttempt(student, ep2, WatchMode.ARABIC_SUB, 60.0, false, BigDecimal.ZERO);
        failedAttempt.setAttemptedAt(LocalDateTime.now().minusMinutes(5));
        quizAttemptRepository.save(failedAttempt);

        StudentEpisodeOverride ov3 = new StudentEpisodeOverride(student, ep3);
        ov3.setOverrideState(StudentEpisodeOverride.OverrideState.UNLOCKED);
        studentEpisodeOverrideRepository.save(ov3);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // Measure 5-episode course
        stats.clear();
        long t0Small = System.nanoTime();
        List<EpisodeStudentResponse> smallResult = courseService.getStudentEpisodes(student.getUsername(), smallCourse.getId(), false);
        long smallMs = (System.nanoTime() - t0Small) / 1_000_000L;
        long smallQueries = stats.getPrepareStatementCount();

        // Measure 67-episode course
        stats.clear();
        long t0Big = System.nanoTime();
        List<EpisodeStudentResponse> bigResult = courseService.getStudentEpisodes(student.getUsername(), bigCourse.getId(), false);
        long bigMs = (System.nanoTime() - t0Big) / 1_000_000L;
        long bigQueries = stats.getPrepareStatementCount();

        System.out.println("[SPEED_BENCHMARK] 67 episodes: queries=" + bigQueries + ", ms=" + bigMs
                + " | 5 episodes: queries=" + smallQueries + ", ms=" + smallMs);

        assertThat(smallResult).hasSize(5);
        assertThat(bigResult).hasSize(67);

        // Verify batched watch status, cooldown, passed & override semantics are preserved
        assertThat(bigResult.get(0).passed()).isTrue();
        assertThat(bigResult.get(0).locked()).isFalse();
        assertThat(bigResult.get(1).locked()).isFalse();
        assertThat(bigResult.get(1).watchStatus().verifiedWatchSeconds()).isEqualTo(900);
        assertThat(bigResult.get(1).watchStatus().retryCooldownRemainingSeconds()).isGreaterThan(0L);
        assertThat(bigResult.get(2).locked()).isFalse(); // admin UNLOCKED override
        assertThat(bigResult.get(3).locked()).isTrue();  // ep 4 still locked

        // Constant small query count regardless of episode count (previously ~270+ queries for 67 episodes)
        assertThat(bigQueries).isLessThanOrEqualTo(15L);
        assertThat(smallQueries).isLessThanOrEqualTo(15L);
        assertThat(bigQueries).isLessThanOrEqualTo(smallQueries);
    }

    @Test
    void postQuizSyncScriptIsPubliclyServed() {
        ResponseEntity<String> res = restTemplate.getForEntity("http://localhost:" + port + "/post-quiz-sync.js", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("window.PostQuizSync");
    }

    private Playlist createCourseWithEpisodes(String ytId, String title, int count) {
        Playlist p = new Playlist(title, ytId, "");
        p.setPublished(true);
        p = playlistRepository.save(p);
        for (int i = 1; i <= count; i++) {
            Episode ep = new Episode(p, i, title + " Ep " + i, ytId + "_V" + i, 2400);
            ep.setSortOrder(i);
            episodeRepository.save(ep);
        }
        return p;
    }
}
