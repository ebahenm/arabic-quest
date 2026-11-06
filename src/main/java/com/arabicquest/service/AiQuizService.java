package com.arabicquest.service;

import com.arabicquest.model.Episode;
import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionOption;
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

    @Transactional
    public Quiz generateQuizForEpisode(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + episodeId));

        Quiz quiz = quizRepository.findByEpisodeId(episodeId)
                .orElseGet(() -> new Quiz(episode));

        quiz.setPassingScorePercentage(100.0);
        if (!quiz.getQuestions().isEmpty()) {
            quiz.getQuestions().clear();
            if (quiz.getId() != null) {
                quiz = quizRepository.saveAndFlush(quiz);
            }
        }

        List<GeneratedQuestion> finalQuestions = new ArrayList<>();
        Set<String> seenQuestions = new HashSet<>();

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                List<GeneratedQuestion> aiList = callGeminiForQuestions(episode.getTitle(), episode.getEpisodeNumber());
                if (aiList != null) {
                    for (GeneratedQuestion gq : aiList) {
                        if (finalQuestions.size() >= TARGET_QUESTION_COUNT) break;
                        if (seenQuestions.add(gq.questionText)) {
                            finalQuestions.add(gq);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Gemini quiz generation failed for episode {}: {}", episodeId, e.getClass().getSimpleName());
            }
        }

        if (finalQuestions.size() < TARGET_QUESTION_COUNT) {
            for (GeneratedQuestion fb : generateFallbackQuestions(episode.getTitle(), episode.getEpisodeNumber())) {
                if (finalQuestions.size() >= TARGET_QUESTION_COUNT) break;
                if (seenQuestions.add(fb.questionText)) {
                    finalQuestions.add(fb);
                }
            }
        }

        Collections.shuffle(finalQuestions, secureRandom);

        int order = 1;
        for (GeneratedQuestion gq : finalQuestions) {
            Question question = new Question(quiz, gq.questionText, order++);
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

    @Transactional
    public List<Map<String, String>> getVocabularyGuide(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));

        String cachedJson = episode.getVocabularyJson();
        if (cachedJson != null && !cachedJson.isBlank()) {
            try {
                List<Map<String, String>> parsed = objectMapper.readValue(
                        cachedJson, new TypeReference<List<Map<String, String>>>() {});
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("Failed to parse cached vocabularyJson for episode {}", episodeId);
            }
        }

        List<Map<String, String>> generated = null;
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                generated = callGeminiForVocabulary(episode.getTitle(), episode.getEpisodeNumber());
            } catch (Exception e) {
                log.warn("Gemini vocabulary generation failed for episode {}: {}", episodeId, e.getClass().getSimpleName());
            }
        }

        if (generated == null || generated.isEmpty()) {
            generated = buildContextualFallbackVocabulary(episode.getTitle(), episode.getEpisodeNumber());
        }

        try {
            episode.setVocabularyJson(objectMapper.writeValueAsString(generated));
            episodeRepository.save(episode);
        } catch (Exception e) {
            log.warn("Could not persist vocabularyJson for episode {}", episodeId);
        }

        return generated;
    }

    private List<GeneratedQuestion> callGeminiForQuestions(String title, int episodeNumber) throws Exception {
        String prompt = "You are an Arabic language & Islamic history professor creating a 20-question comprehension quiz for Episode "
                + episodeNumber + " titled: \"" + title + "\" from the series '100 سؤال في التاريخ' by Muhammad Elhami.\n"
                + "Generate exactly 20 distinct multiple-choice questions testing historical comprehension and key Arabic vocabulary from this topic.\n"
                + "Each question must be in clear Arabic followed by concise English in parentheses, with 4 distinct options (exactly 1 correct and 3 clearly incorrect distractors).";

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

        String rawJson = invokeGeminiStructured(prompt, responseSchema);
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyList();
        }
        return parseJsonQuestions(rawJson);
    }

    private List<Map<String, String>> callGeminiForVocabulary(String title, int episodeNumber) throws Exception {
        String prompt = "Extract 5 essential Arabic vocabulary terms relevant to Episode "
                + episodeNumber + " titled: \"" + title + "\" from '100 سؤال في التاريخ'. "
                + "Include vowelled Arabic (with tashkeel), English transliteration, concise English translation, and a brief root/usage note.";

        Map<String, Object> itemSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "arabic", Map.of("type", "STRING"),
                        "translit", Map.of("type", "STRING"),
                        "english", Map.of("type", "STRING"),
                        "note", Map.of("type", "STRING")
                ),
                "required", List.of("arabic", "translit", "english", "note")
        );

        Map<String, Object> responseSchema = Map.of(
                "type", "ARRAY",
                "items", itemSchema
        );

        String rawJson = invokeGeminiStructured(prompt, responseSchema);
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyList();
        }

        String cleaned = stripCodeFences(rawJson);
        return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, String>>>() {});
    }

    @SuppressWarnings("unchecked")
    private String invokeGeminiStructured(String prompt, Map<String, Object> responseSchema) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel.trim() + ":generateContent";

        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema,
                "temperature", 0.4
        );
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(content),
                "generationConfig", generationConfig
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Never send API key in URL query string; pass strictly via header
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
                if (qText == null || opts == null || opts.size() < 2) {
                    continue;
                }
                List<GeneratedOption> optList = new ArrayList<>();
                boolean hasCorrect = false;
                for (Map<String, Object> o : opts) {
                    boolean correct = Boolean.TRUE.equals(o.get("correct"));
                    if (correct) hasCorrect = true;
                    optList.add(new GeneratedOption(String.valueOf(o.get("text")), correct));
                }
                if (hasCorrect) {
                    result.add(new GeneratedQuestion(qText, optList));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Gemini quiz JSON");
        }
        return result;
    }

    private static String stripCodeFences(String raw) {
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

    private List<GeneratedQuestion> generateFallbackQuestions(String title, int episodeNumber) {
        List<GeneratedQuestion> list = new ArrayList<>();

        list.add(new GeneratedQuestion(
                "ما القضية المحورية التي تعالجها الحلقة " + episodeNumber + " («" + title + "»)؟ (What central issue does this episode address?)",
                List.of(
                        new GeneratedOption("تحرير الإشكاليات التاريخية بمنهج علمي موثق (Examining historical questions with verified scholarly methodology)", true),
                        new GeneratedOption("دراسة العروض والقوافي الشعرية فقط (Studying poetic meter and rhyme exclusively)", false),
                        new GeneratedOption("شرح المعاملات المصرفية المعاصرة (Explaining modern banking transactions)", false),
                        new GeneratedOption("تاريخ الصناعات المعدنية الحديثة (History of modern metallurgical industries)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "كيف يميز المنهج العلمي الإسلامي بين الرواية الصحيحة والرواية الضعيفة؟ (How does Islamic scholarly methodology distinguish authentic from weak reports?)",
                List.of(
                        new GeneratedOption("بفحص السند ونقد المتن ومقارنة الروايات (By examining narrator chains, text criticism, and comparing reports)", true),
                        new GeneratedOption("بقبول كل ما يُنشر دون تمحيص (By accepting everything published uncritically)", false),
                        new GeneratedOption("برفض جميع المصادر المكتوبة مطلقاً (By rejecting all written sources entirely)", false),
                        new GeneratedOption("بالاعتماد على الخيال الأدبي وحده (By relying on literary imagination alone)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما معنى مصطلح «الوعي التاريخي» الذي يؤكد عليه برنامج 100 سؤال في التاريخ؟ (What does 'historical consciousness' mean in the series?)",
                List.of(
                        new GeneratedOption("فهم سنن الماضي لاستيعاب الحاضر والبناء للمستقبل (Understanding patterns of the past to grasp the present and build the future)", true),
                        new GeneratedOption("حفظ أرقام السنوات بمعزل عن سياقها (Memorizing year numbers isolated from context)", false),
                        new GeneratedOption("الانشغال بالجدل العقيم دون عمل (Engaging in fruitless debate without action)", false),
                        new GeneratedOption("تقليد الأمم الأخرى دون تمييز (Imitating other nations uncritically)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما المقصود بكلمة «الشُّبْهَة» عند مناقشة المسائل التاريخية؟ (What does 'Shubhah' mean when discussing historical questions?)",
                List.of(
                        new GeneratedOption("إشكال أو التباس يشتبه بالحق لغياب العلم الكافي (A misconception or ambiguity confused with truth due to incomplete knowledge)", true),
                        new GeneratedOption("الوثيقة الرسمية المختومة (An officially sealed state document)", false),
                        new GeneratedOption("الفهرس الأبجدي للكتاب (The alphabetical index of a book)", false),
                        new GeneratedOption("الخريطة الجغرافية للأقاليم (A geographical map of provinces)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "لماذا تُعد قراءة التاريخ من مصادره الأصيلة ضرورة لطالب المعرفة؟ (Why is studying history from authentic primary sources essential?)",
                List.of(
                        new GeneratedOption("للوقوف على الحقائق الموثقة وتجنب التحريف والاجتزاء (To access verified facts and avoid distortion or cherry-picking)", true),
                        new GeneratedOption("للاستغناء عن التفكير والتحليل العقلي (To dispense with critical thinking and analysis)", false),
                        new GeneratedOption("لأن الكتب الحديثة ممنوعة من الطباعة (Because modern books are banned from printing)", false),
                        new GeneratedOption("لتعلم الرسم والزخرفة فقط (Solely to learn drawing and ornamentation)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما معنى مصطلح «التَّحْقِيق العِلْمِي» في دراسة التاريخ الإسلامي؟ (What does 'At-Tahqiq al-Ilmi' mean in Islamic historical study?)",
                List.of(
                        new GeneratedOption("التثبت من صحة الأخبار والنصوص بالأدلة الموثقة (Establishing the authenticity of reports and texts through verified evidence)", true),
                        new GeneratedOption("كتابة القصص الخيالية للتسلية (Writing fictional stories for entertainment)", false),
                        new GeneratedOption("حذف التواريخ والأسانيد من الكتب (Removing dates and chains from books)", false),
                        new GeneratedOption("الترجمة الحرفية دون مراجعة (Literal translation without verification)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما المقصود بـ«المَصَادِر الأَصِيلَة» في البحث التاريخي؟ (What is meant by 'Al-Masadir al-Asilah' in historical research?)",
                List.of(
                        new GeneratedOption("المراجع الأولية المعاصرة للأحداث أو القريبة منها بالأسانيد (Primary historical sources contemporaneous or close to the events with documented chains)", true),
                        new GeneratedOption("المقالات الصحفية المجهولة المؤلف (Anonymous newspaper articles)", false),
                        new GeneratedOption("الأعمال الدرامية والمسرحية الحديثة (Modern theatrical and dramatic works)", false),
                        new GeneratedOption("الشائعات المتداولة شفوياً بلا سند (Unverified oral rumors)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "لماذا يجب مراعاة «السِّيَاق التَّارِيخِي» عند قراءة الوقائع الماضية؟ (Why must 'As-Siyaq at-Tarikhi' — historical context — be observed when reading past events?)",
                List.of(
                        new GeneratedOption("لفهم الأحداث في ظروفها الزمنية والاجتماعية الصحيحة دون إسقاط مغلوط (To understand events within their true temporal and social conditions without anachronistic distortion)", true),
                        new GeneratedOption("لتبرير الكذب وتزوير الوثائق (To justify fabrication of documents)", false),
                        new GeneratedOption("لإلغاء دراسة الجغرافيا واللغة (To abolish the study of geography and language)", false),
                        new GeneratedOption("للاقتصار على قراءة عنوان الكتاب فقط (To read only a book's title)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما معنى كلمة «الِاسْتِنْبَاط» في منهج العلماء والمؤرخين؟ (What does 'Al-Istinbat' mean in scholarly methodology?)",
                List.of(
                        new GeneratedOption("استخراج الفوائد والسنن والأحكام من النصوص والوقائع بدقة (Extracting sound lessons, laws, and insights carefully from texts and events)", true),
                        new GeneratedOption("نسخ الصفحات بالخط اليدوي فقط (Copying pages by hand without reading)", false),
                        new GeneratedOption("السفر في البحار للتجارة (Sailing the seas for commerce)", false),
                        new GeneratedOption("جمع العملات المعدنية القديمة (Collecting ancient metal coins)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما دور «الإِسْنَاد» في حفظ التاريخ الإسلامي من الضياع والتزوير؟ (What role did 'Al-Isnad' play in preserving Islamic history from forgery?)",
                List.of(
                        new GeneratedOption("توثيق سلسلة الرواة ومعرفة عدالتهم وضبطهم لتمييز الصحيح من المكذوب (Documenting the chain of narrators and testing their integrity and accuracy)", true),
                        new GeneratedOption("إطالة حجم الكتب دون فائدة علمية (Lengthening books without scholarly benefit)", false),
                        new GeneratedOption("منع الطلاب من قراءة كتب التاريخ (Preventing students from reading history books)", false),
                        new GeneratedOption("تزيين أغلفة المخطوطات بالذهب (Decorating manuscript covers with gold)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "بماذا يتميز «علم التراجم والطبقات» عند علماء المسلمين؟ (What distinguishes 'Ilm at-Tarajim wat-Tabaqat' among Muslim scholars?)",
                List.of(
                        new GeneratedOption("تدوين سِيَر آلاف العلماء والرواة والأعلام عبر الأجيال بدقة غير مسبوقة (Documenting biographies of thousands of scholars, narrators, and figures across generations)", true),
                        new GeneratedOption("دراسة طبقات الأرض الجيولوجية فقط (Studying geological rock strata only)", false),
                        new GeneratedOption("بناء الأبراج العالية في المدن (Constructing tall towers in cities)", false),
                        new GeneratedOption("تصنيف النباتات والأعشاب البرية (Classifying wild herbs and plants)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما الفرق بين «التاريخ السياسي» و«التاريخ الحضاري» للأمة الإسلامية؟ (What is the difference between political history and civilizational history?)",
                List.of(
                        new GeneratedOption("التاريخ السياسي يرصد الدول والمعارك، والحضاري يرصد العلم والعمران والقيم والمؤسسات (Political history tracks states/wars, while civilizational history captures science, institutions, and morals)", true),
                        new GeneratedOption("كلاهما يقتصر على أخبار الملوك فقط (Both cover news of kings exclusively)", false),
                        new GeneratedOption("التاريخ الحضاري لا وجود له في التراث (Civilizational history does not exist in heritage)", false),
                        new GeneratedOption("التاريخ السياسي يدرس علم الفلك والنجوم (Political history studies astronomy and stars)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "كيف يتعامل الباحث المنصف مع الروايات التاريخية المتعارضة في ظاهرها؟ (How does a fair researcher handle seemingly conflicting historical reports?)",
                List.of(
                        new GeneratedOption("يجمع الروايات ويفحص أسانيدها ومتونها ويرجح بالأدلة العلمية (Compiles all reports, audits their chains and texts, and weighs them by scholarly evidence)", true),
                        new GeneratedOption("يختار الرواية الأغرب لنشر الإثارة (Picks the strangest rumor for sensationalism)", false),
                        new GeneratedOption("يرفض الحدث التاريخي كله بلا بحث (Rejects the entire historical era without study)", false),
                        new GeneratedOption("يعتمد أول نتيجة غير موثقة يصادفها (Relies on the first unverified claim encountered)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "من العالم المسلم الذي أسس «علم العمران البشري» في مقدمته الشهيرة؟ (Which Muslim scholar founded the science of human society — 'Ilm al-'Umran — in his famous Muqaddimah?)",
                List.of(
                        new GeneratedOption("عبد الرحمن بن خلدون (Ibn Khaldun)", true),
                        new GeneratedOption("أرخميدس اليوناني (Archimedes)", false),
                        new GeneratedOption("ماركو بولو البندقي (Marco Polo)", false),
                        new GeneratedOption("فاسكو دا غاما البرتغالي (Vasco da Gama)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما الغاية العملية الكبرى من دراسة برنامج «100 سؤال في التاريخ» كما يقررها الأستاذ محمد إلهامي؟ (What overarching practical goal does '100 Questions in History' pursue?)",
                List.of(
                        new GeneratedOption("تحصين الوعي وبناء الثقة بالهوية الإسلامية وفهم سنن النهوض والعمل (Fortifying historical awareness, confidence in Islamic identity, and understanding laws of revival)", true),
                        new GeneratedOption("التسلية بالألغاز الترفيهية العابرة فقط (Casual entertainment through trivia puzzles alone)", false),
                        new GeneratedOption("الانقطاع عن الواقع المعاصر وترك العمل (Withdrawing from the modern world and abandoning effort)", false),
                        new GeneratedOption("تقليد مناهج المستشرقين بلا نقد (Imitating Orientalist claims uncritically)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما معنى كلمة «المَتْن» عند علماء الحديث والتاريخ في مقابل «السَّنَد»؟ (What does 'Al-Matn' mean in contrast to 'As-Sanad'?)",
                List.of(
                        new GeneratedOption("نص الخبر أو الكلام المروي نفسه بعد سلسلة الرواة (The actual text/content of the report transmitted at the end of the narrator chain)", true),
                        new GeneratedOption("غلاف المخطوطة الجلدي الخارجي (The outer leather binding of a manuscript)", false),
                        new GeneratedOption("اسم المدينة التي طُبع فيها الكتاب (The city where the book was printed)", false),
                        new GeneratedOption("رقم الصفحة في الفهرس الحديث (The page number in a modern index)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "لماذا يدون المؤرخون غالباً الحروب والأزمات أكثر من سنوات الاستقرار الطويلة؟ (Why do chroniclers often record wars and crises more than long decades of peaceful stability?)",
                List.of(
                        new GeneratedOption("لأن الحروب أحداث استثنائية صارخة، بينما الهدوء والعمران اليومي هو الأصل المستمر (Because wars are exceptional disruptions, whereas peaceful daily life is the ongoing norm)", true),
                        new GeneratedOption("لأنه لم يمر يوم واحد من السلام في تاريخ البشر (Because humanity never experienced a single peaceful day)", false),
                        new GeneratedOption("لأن العلماء منعوا تأليف كتب العلم والأدب (Because scholars banned books on science and literature)", false),
                        new GeneratedOption("لأن الكتابة لم تُعرف إلا في ساحات القتال (Because writing was known only on battlefields)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما الموقف العلمي الصحيح من أخطاء الأفراد أو الحكام في التاريخ الإسلامي؟ (What is the sound scholarly stance regarding mistakes made by individuals or rulers in history?)",
                List.of(
                        new GeneratedOption("تُوزن بميزان الشرع والعدل دون تقديس للأشخاص ودون تشويه لأصل الحضارة الإسلامية (Weigh them fairly by Islamic justice—neither sanctifying fallible rulers nor slandering Islamic civilization)", true),
                        new GeneratedOption("إنكار وقوع أي خطأ بشري بعد عصر النبوة (Denying any human error ever occurred after the Prophet's era)", false),
                        new GeneratedOption("هدم التراث الإسلامي كله بسبب خطأ فردي (Discarding all Islamic heritage over an individual's mistake)", false),
                        new GeneratedOption("اعتماد روايات الخصوم وحدها دون تثبت (Accepting hostile fabrications without verification)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "ما العلاقة بين اللغة العربية وفهم المصادر التاريخية الإسلامية الأصيلة؟ (What is the relationship between the Arabic language and understanding primary Islamic historical sources?)",
                List.of(
                        new GeneratedOption("العربية مفتاح أساسي لفهم القرآن والسنة ودقائق المصطلحات في كتب التراث مباشرة (Arabic is the essential key to directly grasping the Quran, Sunnah, and nuanced heritage terminology)", true),
                        new GeneratedOption("لا علاقة للغة العربية بكتب التاريخ الإسلامي (Arabic has no connection to Islamic history books)", false),
                        new GeneratedOption("كُتبت جميع المصادر الإسلامية الأولى باللغة اللاتينية (All early Islamic sources were written in Latin)", false),
                        new GeneratedOption("تكفي الترجمات الآلية الحرفية لإتقان علوم التراث (Literal machine translation suffices to master classical sciences)", false)
                )
        ));

        list.add(new GeneratedQuestion(
                "في الحلقة " + episodeNumber + " («" + title + "»)، ما المنهج الذي يجمع بين الأصالة والإنصاف عند عرض المسألة؟ (In Episode " + episodeNumber + ", what method combines authenticity and fairness?)",
                List.of(
                        new GeneratedOption("الاستدلال بالوحيين والوقائع الموثقة مع الرد العلمي الهادئ على الشبهات (Grounding analysis in revelation and verified facts while refuting misconceptions calmly and rigorously)", true),
                        new GeneratedOption("الاكتفاء بالعاطفة والخطابة دون أدلة تاريخية (Relying on emotion and rhetoric without historical evidence)", false),
                        new GeneratedOption("ترديد الشبهات دون تقديم أي جواب علمي (Repeating doubts without offering any scholarly answer)", false),
                        new GeneratedOption("الاعتماد على الأساطير غير الموثقة (Relying on unverified legends)", false)
                )
        ));

        return list;
    }

    private List<Map<String, String>> buildContextualFallbackVocabulary(String title, int episodeNumber) {
        List<Map<String, String>> vocab = new ArrayList<>();
        vocab.add(Map.of(
                "arabic", "الحَلْقَة " + episodeNumber,
                "translit", "Al-Halqah " + episodeNumber,
                "english", "Episode " + episodeNumber,
                "note", "Topic: " + title
        ));
        vocab.add(Map.of(
                "arabic", "التَّحْقِيق العِلْمِي",
                "translit", "At-Tahqiq al-'Ilmi",
                "english", "Scholarly Verification",
                "note", "From root ح-ق-ق (h-q-q), establishing truth through evidence."
        ));
        vocab.add(Map.of(
                "arabic", "المَصَادِر الأَصِيلَة",
                "translit", "Al-Masadir al-Asilah",
                "english", "Primary / Authentic Sources",
                "note", "Plural of مَصْدَر (masdar), original historical references."
        ));
        vocab.add(Map.of(
                "arabic", "السِّيَاق التَّارِيخِي",
                "translit", "As-Siyaq at-Tarikhi",
                "english", "Historical Context",
                "note", "Essential for understanding events accurately without distortion."
        ));
        vocab.add(Map.of(
                "arabic", "الاسْتِنْبَاط",
                "translit", "Al-Istinbat",
                "english", "Deduction / Extracting Insight",
                "note", "Drawing sound conclusions and lessons from historical evidence."
        ));
        return vocab;
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
