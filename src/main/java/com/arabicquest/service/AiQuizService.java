package com.arabicquest.service;

import com.arabicquest.model.Episode;
import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionOption;
import com.arabicquest.model.QuestionStatus;
import com.arabicquest.model.Quiz;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.QuizRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.util.*;

@Service
public class AiQuizService {

    public static final int TARGET_QUESTION_COUNT = 20;
    private static final Logger log = LoggerFactory.getLogger(AiQuizService.class);

    private final QuizRepository quizRepository;
    private final EpisodeRepository episodeRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();
    private RestTemplate restTemplate = new RestTemplate();

    @Value("${GEMINI_API_KEY:${AI_API_KEY:}}")
    private String geminiApiKey;

    @Value("${GEMINI_MODEL:gemini-2.5-flash}")
    private String geminiModel;

    public AiQuizService(QuizRepository quizRepository, EpisodeRepository episodeRepository) {
        this.quizRepository = quizRepository;
        this.episodeRepository = episodeRepository;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public RestTemplate getRestTemplate() {
        return this.restTemplate;
    }

    @Transactional
    public Quiz generateQuizForEpisode(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + episodeId));

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured.");
        }

        List<GeneratedQuestion> aiList;
        try {
            aiList = callGeminiForQuestions(episode.getTitle(), episode.getEpisodeNumber());
        } catch (Exception e) {
            log.warn("Gemini quiz generation failed for episode {}: {}", episodeId, e.getClass().getSimpleName());
            throw new IllegalStateException("Gemini quiz generation failed: " + e.getClass().getSimpleName());
        }

        List<GeneratedQuestion> finalQuestions = new ArrayList<>();
        Set<String> seenQuestions = new HashSet<>();
        if (aiList != null) {
            for (GeneratedQuestion gq : aiList) {
                if (finalQuestions.size() >= TARGET_QUESTION_COUNT) {
                    break;
                }
                if (seenQuestions.add(gq.questionText)) {
                    finalQuestions.add(gq);
                }
            }
        }

        if (finalQuestions.isEmpty()) {
            throw new IllegalStateException("Gemini returned no valid questions for episode " + episodeId);
        }

        Quiz quiz = quizRepository.findByEpisodeId(episodeId)
                .orElseGet(() -> new Quiz(episode));
        quiz.setPassingScorePercentage(100.0);

        int nextOrder = quiz.getQuestions().size() + 1;
        for (GeneratedQuestion gq : finalQuestions) {
            Question question = new Question(quiz, gq.questionText, nextOrder++);
            question.setStatus(QuestionStatus.DRAFT);
            question.setActive(true);
            List<GeneratedOption> shuffledOpts = new ArrayList<>(gq.options);
            Collections.shuffle(shuffledOpts, secureRandom);
            for (GeneratedOption go : shuffledOpts) {
                QuestionOption opt = new QuestionOption(question, go.optionText, go.isCorrect);
                question.getOptions().add(opt);
            }
            quiz.getQuestions().add(question);
        }

        return quizRepository.save(quiz);
    }

    /**
     * Read-only student lookup: returns stored vocabulary JSON if present, never calling Gemini.
     */
    @Transactional(readOnly = true)
    public List<Map<String, String>> getVocabularyGuide(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));

        String cachedJson = episode.getVocabularyJson();
        if (cachedJson == null || cachedJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, String>> parsed = objectMapper.readValue(
                    cachedJson, new TypeReference<List<Map<String, String>>>() {});
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to parse cached vocabularyJson for episode {}", episodeId);
            return Collections.emptyList();
        }
    }

    private List<GeneratedQuestion> callGeminiForQuestions(String title, int episodeNumber) throws Exception {
        String prompt = "You are an Islamic history professor creating comprehension questions for Episode "
                + episodeNumber + " titled: \"" + title + "\".\n"
                + "Generate 20 distinct multiple-choice questions in English (Arabic terms may appear in parentheses), "
                + "each with 4 distinct options (exactly 1 correct and 3 plausible distractors).";

        Map<String, Object> optionSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "text", Map.of("type", "STRING"),
                        "correct", Map.of("type", "BOOLEAN")
                ),
                "required", List.of("text", "correct")
        );

        Map<String, Object> questionSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "question", Map.of("type", "STRING"),
                        "options", Map.of("type", "ARRAY", "items", optionSchema)
                ),
                "required", List.of("question", "options")
        );

        Map<String, Object> responseSchema = Map.of(
                "type", "ARRAY",
                "items", questionSchema
        );

        String rawJson = invokeGeminiStructured(prompt, responseSchema, null);
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyList();
        }
        return parseJsonQuestions(rawJson);
    }

    @SuppressWarnings("unchecked")
    public String invokeGeminiStructured(String prompt, Map<String, Object> responseSchema, String systemInstruction) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured.");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel.trim() + ":generateContent";

        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (responseSchema != null) {
            generationConfig.put("responseSchema", responseSchema);
        }
        generationConfig.put("temperature", 0.3);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            requestBody.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Never send API key in URL query string or logs; pass strictly via header
        headers.set("x-goog-api-key", geminiApiKey.trim());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> contentMap = (Map<String, Object>) candidates.get(0).get("content");
                if (contentMap != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) contentMap.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        }
        return null;
    }

    private List<GeneratedQuestion> parseJsonQuestions(String rawJson) {
        List<GeneratedQuestion> result = new ArrayList<>();
        try {
            String cleaned = stripCodeFences(rawJson);
            List<Map<String, Object>> list = objectMapper.readValue(
                    cleaned, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> item : list) {
                String qText = (String) item.get("question");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> opts = (List<Map<String, Object>>) item.get("options");
                if (qText == null || opts == null || opts.size() != 4) {
                    continue;
                }
                List<GeneratedOption> optList = new ArrayList<>();
                int correctCount = 0;
                for (Map<String, Object> o : opts) {
                    boolean correct = Boolean.TRUE.equals(o.get("correct"));
                    if (correct) {
                        correctCount++;
                    }
                    optList.add(new GeneratedOption(String.valueOf(o.get("text")), correct));
                }
                if (correctCount == 1) {
                    result.add(new GeneratedQuestion(qText, optList));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Gemini quiz JSON");
        }
        return result;
    }

    public static String stripCodeFences(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private static class GeneratedQuestion {
        final String questionText;
        final List<GeneratedOption> options;
        GeneratedQuestion(String questionText, List<GeneratedOption> options) {
            this.questionText = questionText;
            this.options = options;
        }
    }

    private static class GeneratedOption {
        final String optionText;
        final boolean isCorrect;
        GeneratedOption(String optionText, boolean isCorrect) {
            this.optionText = optionText;
            this.isCorrect = isCorrect;
        }
    }
}
