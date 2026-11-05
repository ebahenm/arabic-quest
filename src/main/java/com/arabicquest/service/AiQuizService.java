package com.arabicquest.service;

import com.arabicquest.model.Episode;
import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionOption;
import com.arabicquest.model.Quiz;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.QuizRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AiQuizService {

    private final QuizRepository quizRepository;
    private final EpisodeRepository episodeRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${GEMINI_API_KEY:${AI_API_KEY:}}")
    private String geminiApiKey;

    public AiQuizService(QuizRepository quizRepository, EpisodeRepository episodeRepository) {
        this.quizRepository = quizRepository;
        this.episodeRepository = episodeRepository;
    }

    @Transactional
    public Quiz generateQuizForEpisode(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + episodeId));

        Quiz quiz = quizRepository.findByEpisodeId(episodeId)
                .orElseGet(() -> new Quiz(episode));

        quiz.setPassingScorePercentage(80.0);
        quiz.getQuestions().clear();

        List<GeneratedQuestion> generatedQuestions = null;

        // Try Gemini API if API key is provided
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                generatedQuestions = callGeminiApiForQuestions(episode.getTitle(), episode.getEpisodeNumber());
            } catch (Exception e) {
                System.err.println("Gemini API call failed, falling back to intelligent generator: " + e.getMessage());
            }
        }

        // Fallback generator if Gemini is not configured or failed
        if (generatedQuestions == null || generatedQuestions.isEmpty()) {
            generatedQuestions = generateFallbackQuestions(episode.getTitle(), episode.getEpisodeNumber());
        }

        int order = 1;
        for (GeneratedQuestion gq : generatedQuestions) {
            Question question = new Question(quiz, gq.questionText, order++);
            for (GeneratedOption go : gq.options) {
                QuestionOption opt = new QuestionOption(question, go.optionText, go.isCorrect);
                question.getOptions().add(opt);
            }
            quiz.getQuestions().add(question);
        }

        return quizRepository.save(quiz);
    }

    private List<GeneratedQuestion> callGeminiApiForQuestions(String title, int episodeNumber) {
        String prompt = "You are an Arabic language teacher. Based on this historical episode title: '" + title + 
                "', generate 3 multiple-choice comprehension questions to test listening comprehension and Arabic vocabulary.\n" +
                "Format strictly as JSON with this structure:\n" +
                "[\n" +
                "  {\n" +
                "    \"question\": \"Question in Arabic with English translation in parentheses\",\n" +
                "    \"options\": [\n" +
                "      {\"text\": \"Correct option in Arabic (English)\", \"correct\": true},\n" +
                "      {\"text\": \"Wrong option 1 in Arabic (English)\", \"correct\": false},\n" +
                "      {\"text\": \"Wrong option 2 in Arabic (English)\", \"correct\": false},\n" +
                "      {\"text\": \"Wrong option 3 in Arabic (English)\", \"correct\": false}\n" +
                "    ]\n" +
                "  }\n" +
                "]\n" +
                "Return only raw valid JSON without markdown formatting or code fences.";

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiApiKey;

        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> requestBody = Map.of("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            List candidates = (List) response.getBody().get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map first = (Map) candidates.get(0);
                Map contentMap = (Map) first.get("content");
                List parts = (List) contentMap.get("parts");
                Map part0 = (Map) parts.get(0);
                String rawJson = (String) part0.get("text");
                return parseJsonQuestions(rawJson);
            }
        }
        return Collections.emptyList();
    }

    private List<GeneratedQuestion> parseJsonQuestions(String rawJson) {
        List<GeneratedQuestion> result = new ArrayList<>();
        try {
            // Remove markdown code fences if present
            String cleaned = rawJson.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> list = mapper.readValue(cleaned, List.class);

            for (Map<String, Object> item : list) {
                String qText = (String) item.get("question");
                List<Map<String, Object>> opts = (List<Map<String, Object>>) item.get("options");
                List<GeneratedOption> optList = new ArrayList<>();
                for (Map<String, Object> o : opts) {
                    optList.add(new GeneratedOption((String) o.get("text"), Boolean.TRUE.equals(o.get("correct"))));
                }
                result.add(new GeneratedQuestion(qText, optList));
            }
        } catch (Exception e) {
            System.err.println("Failed to parse Gemini JSON: " + e.getMessage());
        }
        return result;
    }

    private List<GeneratedQuestion> generateFallbackQuestions(String title, int episodeNumber) {
        List<GeneratedQuestion> list = new ArrayList<>();

        // Question 1: Topic comprehension
        list.add(new GeneratedQuestion(
                "ما هو الموضوع المحوري الذي تدور حوله هذه الحلقة (" + title + ")؟",
                List.of(
                        new GeneratedOption("فهم الأحداث التاريخية وتحليل أسبابها (Understanding historical events and their causes)", true),
                        new GeneratedOption("شعر الغزل الجاهلي (Pre-Islamic love poetry)", false),
                        new GeneratedOption("قواعد النحو العربي المتقدم (Advanced Arabic grammar rules)", false),
                        new GeneratedOption("صناعة الأسلحة الحديثة (Modern weapons manufacturing)", false)
                )
        ));

        // Question 2: Vocabulary comprehension
        list.add(new GeneratedQuestion(
                "ما المعنى الأقرب لمصطلح 'التدوين التاريخي' المستخدم في السلسلة؟",
                List.of(
                        new GeneratedOption("كتابة الأخبار وتسجيل الروايات الموثقة (Recording and documenting verified reports)", true),
                        new GeneratedOption("رسم الخرائط الجغرافية (Drawing geographic maps)", false),
                        new GeneratedOption("تأليف الروايات الخيالية (Inventing fictional stories)", false),
                        new GeneratedOption("التجارة بين المدن (Commerce between cities)", false)
                )
        ));

        // Question 3: Critical thinking & listening check
        list.add(new GeneratedQuestion(
                "ما هي الفائدة الأساسية التي يركز عليها المحاضر عند دراسة هذا الباب من التاريخ؟",
                List.of(
                        new GeneratedOption("استخلاص الدروس والعِبر لبناء الوعي المعاصر (Extracting lessons to build modern awareness)", true),
                        new GeneratedOption("حفظ أسماء الملوك دون فهم السياق (Memorizing kings' names without context)", false),
                        new GeneratedOption("الجدال العقيم دون فائدة علمية (Futile arguments without scholarly benefit)", false),
                        new GeneratedOption("التعصب للآراء القديمة (Blind fanaticism toward old views)", false)
                )
        ));

        return list;
    }

    public List<Map<String, String>> getVocabularyGuide(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));

        return List.of(
                Map.of("arabic", "التَّاريخ", "translit", "At-Tareekh", "english", "History / Chronicle", "note", "Root word related to date and timing."),
                Map.of("arabic", "العِبْرَة", "translit", "Al-'Ibrah", "english", "Moral lesson / Insight", "note", "Plural is 'Ibar (عِبَر), learning from past outcomes."),
                Map.of("arabic", "الإِسْنَاد", "translit", "Al-Isnad", "english", "Chain of transmission / Documentation", "note", "Methodology used to verify historical authentic reports."),
                Map.of("arabic", "الحَضَارَة", "translit", "Al-Hadarah", "english", "Civilization / Culture", "note", "Refers to intellectual, scientific and societal development."),
                Map.of("arabic", "الرِّوَايَة", "translit", "Ar-Riwayah", "english", "Narration / Historical account", "note", "The telling of an event handed down by observers.")
        );
    }

    private static class GeneratedQuestion {
        String questionText;
        List<GeneratedOption> options;
        GeneratedQuestion(String questionText, List<GeneratedOption> options) {
            this.questionText = questionText;
            this.options = options;
        }
    }

    private static class GeneratedOption {
        String optionText;
        boolean isCorrect;
        GeneratedOption(String optionText, boolean isCorrect) {
            this.optionText = optionText;
            this.isCorrect = isCorrect;
        }
    }
}
