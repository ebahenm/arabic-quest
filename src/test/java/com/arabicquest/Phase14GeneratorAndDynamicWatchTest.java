// Tests dynamic watch-time target formula (50m, 90m, 2h, 3h, <10m), transcript/bank status endpoints, and generator UI assets.
package com.arabicquest;

import com.arabicquest.model.Episode;
import com.arabicquest.model.EpisodeTranscript;
import com.arabicquest.model.Playlist;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.EpisodeTranscriptRepository;
import com.arabicquest.repository.PlaylistRepository;
import com.arabicquest.service.WatchCreditCalculator;
import com.arabicquest.service.WatchProgressService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase14_gen_watch_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=p14admin",
        "ADMIN_PASSWORD=Phase14StrongPassword!99",
        "ADMIN_SECRET_PATH=p14-secret-hq",
        "JWT_SECRET=phase14-generator-and-watch-jwt-secret-key-well-over-32-chars"
})
class Phase14GeneratorAndDynamicWatchTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WatchProgressService watchProgressService;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private EpisodeTranscriptRepository transcriptRepository;

    @Test
    void dynamicWatchFormula_handles50m_90m_2h_3h_andShortUnder10mEpisodes() {
        // 50m (3000s): round((3000 - 300) * 0.85) = 2295s
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(3000)).isEqualTo(2295);

        // 90m (5400s): round((5400 - 300) * 0.85) = 4335s
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(5400)).isEqualTo(4335);

        // 2h (7200s): round((7200 - 300) * 0.85) = 5865s
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(7200)).isEqualTo(5865);

        // 3h (10800s): round((10800 - 300) * 0.85) = 8925s
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(10800)).isEqualTo(8925);

        // Short (<10m): 8m (480s) => round(480 * 0.85) = 408s; 10m (600s) => 510s; 45s => clamped to 60s
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(480)).isEqualTo(408);
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(600)).isEqualTo(510);
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(45)).isEqualTo(60);

        // Boundary just over 10m (605s): round(305 * 0.85) = 259 => clamped to 300s
        assertThat(WatchCreditCalculator.computeRequiredWatchSeconds(605)).isEqualTo(300);

        // Verify WatchProgressService delegates to dynamic target (no 1800s cap on 2h episode)
        Playlist p = new Playlist("Dynamic Watch Course", "PL_P14_DYN", "");
        p.setPublished(true);
        p = playlistRepository.save(p);
        Episode twoHourEp = episodeRepository.save(new Episode(p, 1, "2-Hour Lecture", "P14_2HR", 7200));
        assertThat(watchProgressService.computeRequiredWatchSeconds(twoHourEp)).isEqualTo(5865);
    }

    @Test
    void transcriptAndQuestionBankStatusEndpoints_handleWithTranscript_withoutTranscript_andUnknownEpisode() {
        Playlist p = new Playlist("Status Endpoint Course", "PL_P14_STATUS", "");
        p.setPublished(true);
        p = playlistRepository.save(p);

        Episode epWithTr = episodeRepository.save(new Episode(p, 1, "Ep With Transcript", "P14_TR_1", 1800));
        Episode epWithoutTr = episodeRepository.save(new Episode(p, 2, "Ep Without Transcript", "P14_TR_2", 1800));

        EpisodeTranscript et = new EpisodeTranscript();
        et.setEpisode(epWithTr);
        et.setSource("UPLOAD");
        et.setPlainText("one two three four five six seven");
        et.setWordCount(7);
        et.setCuesJson("[]");
        et.setQualityStatus("PASS");
        et.setUploadedAt(LocalDateTime.now());
        transcriptRepository.save(et);

        HttpHeaders adminHeaders = loginAdminHeaders();

        // 1. Episode WITH saved transcript
        ResponseEntity<Map> trYes = restTemplate.exchange(
                url("/api/admin/episodes/" + epWithTr.getId() + "/transcript"),
                HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);
        assertThat(trYes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trYes.getBody()).containsEntry("hasTranscript", true).containsEntry("wordCount", 7);

        ResponseEntity<Map> bankYes = restTemplate.exchange(
                url("/api/admin/episodes/" + epWithTr.getId() + "/question-bank?status=ALL&type=ALL&reportedOnly=false"),
                HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);
        assertThat(bankYes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bankYes.getBody()).containsEntry("approvedMcqCount", 0).containsEntry("draftMcqCount", 0);

        // 2. Episode WITHOUT transcript
        ResponseEntity<Map> trNo = restTemplate.exchange(
                url("/api/admin/episodes/" + epWithoutTr.getId() + "/transcript"),
                HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);
        assertThat(trNo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trNo.getBody()).containsEntry("hasTranscript", false).containsEntry("wordCount", 0);

        // 3. Unknown episode ID -> clean 4xx client error (404/400, never 500)
        ResponseEntity<Map> trUnknown = restTemplate.exchange(
                url("/api/admin/episodes/9999999/transcript"),
                HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);
        assertThat(trUnknown.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> bankUnknown = restTemplate.exchange(
                url("/api/admin/episodes/9999999/question-bank?status=ALL&type=ALL&reportedOnly=false"),
                HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);
        assertThat(bankUnknown.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
    }

    @Test
    void studentWatch30mNoteRemoved_keepOpenNoticePreserved_andAdminApiExposesApiFetch() throws Exception {
        String indexHtml = Files.readString(Path.of("src/main/resources/static/index.html"));
        assertThat(indexHtml).doesNotContain("Watch 30 minutes (or the full video if shorter)");

        String mobileWatchJs = Files.readString(Path.of("src/main/resources/static/mobile-watch.js"));
        assertThat(mobileWatchJs).contains("Keep this tab open and your screen unlocked while watching");

        String adminApiJs = Files.readString(Path.of("src/main/resources/private-hq/data/admin-api.js"));
        assertThat(adminApiJs).contains("apiFetch(url");

        String pillsJs = Files.readString(Path.of("src/main/resources/private-hq/components/generator-status-pills.js"));
        assertThat(pillsJs).contains("Promise.allSettled").doesNotContain("innerHTML");

        String genCardJs = Files.readString(Path.of("src/main/resources/private-hq/pages/generator-card-ui.js"));
        assertThat(genCardJs).doesNotContain("innerHTML");
    }

    private HttpHeaders loginAdminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = restTemplate.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", "p14admin", "password", "Phase14StrongPassword!99"), h),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        HttpHeaders auth = new HttpHeaders();
        auth.set(HttpHeaders.COOKIE, String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList()));
        auth.set("X-Requested-With", "XMLHttpRequest");
        return auth;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
