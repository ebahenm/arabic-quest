// Shared mock Gemini payloads, async RestTemplate fixtures, and DB seed helpers for Phase 5 integration tests.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class Phase5GeminiTestSupport {

    private static final String[] TOPICS = {
            "Cordoba library", "Damascus astrolabe", "Samarkand meridian", "Granada cisterns",
            "Qayrawan parchment", "Baghdad pharmacy", "Fez university", "Aleppo caravan",
            "Timbuktu manuscript", "Cairo tables", "Toledo scriptorium", "Palermo globe",
            "Alexandria optics", "Isfahan algebra"
    };

    private Phase5GeminiTestSupport() {}

    static RestTemplate buildAsyncMockRestTemplate(
            ObjectMapper mapper, String prefix, int mcqs, int opens,
            CountDownLatch entered, CountDownLatch release,
            AtomicInteger callCounter, AtomicReference<String> pass1BodyRef) {
        RestTemplate rt = new RestTemplate();
        rt.setRequestFactory((uri, httpMethod) -> new MockClientHttpRequest(httpMethod, uri) {
            @Override
            protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                callCounter.incrementAndGet();
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                String body = getBodyAsString(StandardCharsets.UTF_8);
                String json = body.contains("verifiedMcqIndices")
                        ? buildPass2Json(mapper, mcqs, opens)
                        : buildPass1Json(mapper, prefix, mcqs, opens);
                if (!body.contains("verifiedMcqIndices") && pass1BodyRef != null) pass1BodyRef.set(body);
                MockClientHttpResponse resp = new MockClientHttpResponse(
                        wrapGeminiText(mapper, json).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return resp;
            }
        });
        return rt;
    }

    static String wrapGeminiText(ObjectMapper mapper, String text) {
        try {
            return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", text)))))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String buildPass1Json(ObjectMapper mapper, String prefix, int mcqs, int opens) {
        List<Map<String, Object>> mList = new ArrayList<>();
        for (int i = 0; i < mcqs; i++) {
            String t = TOPICS[i % TOPICS.length] + " #" + i;
            mList.add(Map.of("questionText", prefix + " role of " + t + "?", "type", "RECALL", "evidence", "Cite " + t,
                    "evidenceTimestamp", (i + 1) * 15, "options", List.of(
                            Map.of("optionText", prefix + " Right " + i, "isCorrect", true),
                            Map.of("optionText", prefix + " WrongA " + i, "isCorrect", false),
                            Map.of("optionText", prefix + " WrongB " + i, "isCorrect", false),
                            Map.of("optionText", prefix + " WrongC " + i, "isCorrect", false))));
        }
        List<Map<String, Object>> oList = new ArrayList<>();
        for (int i = 0; i < opens; i++) {
            oList.add(Map.of("promptText", prefix + " synthesize institution impact #" + i + ".", "evidence", "Rubric " + i, "evidenceTimestamp", 90));
        }
        try { return mapper.writeValueAsString(Map.of("mcqs", mList, "openQuestions", oList)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static String buildPass2Json(ObjectMapper mapper, int mcqs, int opens) {
        List<Integer> mIdx = new ArrayList<>(), oIdx = new ArrayList<>();
        for (int i = 0; i < mcqs; i++) mIdx.add(i);
        for (int i = 0; i < opens; i++) oIdx.add(i);
        try { return mapper.writeValueAsString(Map.of("verifiedMcqIndices", mIdx, "verifiedOpenIndices", oIdx)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static Episode createCourseAndEpisode(
            TransactionTemplate tx, PlaylistRepository playlists, EpisodeRepository episodes,
            String ytId, String epTitle, int dur, String transcript) {
        return tx.execute(st -> {
            Playlist p = new Playlist(ytId + " Course", ytId, "Test");
            p.setPublished(true);
            p = playlists.save(p);
            Episode ep = new Episode(p, 1, epTitle, "vid" + ytId.substring(Math.max(0, ytId.length() - 6)), dur);
            ep.setTranscriptText(transcript);
            return episodes.save(ep);
        });
    }

    static void seedApprovedBank(
            TransactionTemplate tx, EpisodeRepository episodes, QuizRepository quizzes,
            QuestionRepository questions, OpenQuestionRepository openQuestions, Long epId, int mcqCount) {
        tx.executeWithoutResult(st -> {
            Episode ep = episodes.findById(epId).orElseThrow();
            Quiz quiz = quizzes.save(new Quiz(ep));
            for (int i = 1; i <= mcqCount; i++) {
                Question q = new Question(quiz, "Seed Q" + i + "?", i);
                q.setStatus(QuestionStatus.APPROVED);
                q.setActive(true);
                q.getOptions().add(new QuestionOption(q, "Seed Wrong " + i + "A", false));
                q.getOptions().add(new QuestionOption(q, "Seed Right " + i, true));
                q.getOptions().add(new QuestionOption(q, "Seed Wrong " + i + "C", false));
                q.getOptions().add(new QuestionOption(q, "Seed Wrong " + i + "D", false));
                questions.save(q);
            }
            openQuestions.save(new OpenQuestion(ep, "Seed open prompt?", "Evidence", QuestionStatus.APPROVED));
        });
    }

    static String createStudentAndLogin(MockMvc mockMvc, ObjectMapper mapper, String adminBearer, String username, String ip) throws Exception {
        mockMvc.perform(post("/api/admin/students").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Pass!123456\",\"fullName\":\"" + username + "\"}"))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr(ip); return req; })
                        .header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Pass!123456\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }
}
