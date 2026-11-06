package com.arabicquest.config;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final Set<String> LEGACY_PLACEHOLDER_VIDEO_IDS = Set.of(
            "p1qG6FqfJ8w", "XhksJMfDJtv", "V_M8Z4WqCjk", "7hYh4R9yHfk",
            "2b9N_R6yFp8", "gqX9_K6yFn1", "k8Nm_W4yTp2", "v3Rz_T8yLq7",
            "m5Jx_P2yRq4", "p9Vw_R3yTn6", "q2Bn_L7yXm9", "w4Zk_M9yVp1"
    );

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public CommandLineRunner initData(
            UserRepository userRepository,
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            @Value("${ADMIN_USERNAME:admin}") String adminUsername,
            @Value("${ADMIN_PASSWORD:}") String adminPassword,
            @Value("${FORCE_ADMIN_ENV_SYNC:false}") boolean forceAdminEnvSync) {
        return args -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    tx.executeWithoutResult(status -> {
                        syncAdminAccount(userRepository, passwordEncoder, adminUsername, adminPassword, forceAdminEnvSync);
                        seedOrUpgradeCurriculum(playlistRepository, episodeRepository, quizRepository);
                    });
                    return;
                } catch (Exception ex) {
                    if (attempt < maxAttempts) {
                        log.warn("DataInitializer attempt {}/{} encountered transient error ({}); retrying in 3s...",
                                attempt, maxAttempts, ex.getMessage());
                        try {
                            Thread.sleep(3000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        log.error("DataInitializer failed after {} attempts; continuing server startup without aborting container.",
                                maxAttempts, ex);
                    }
                }
            }
        };
    }

    private void syncAdminAccount(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            String configuredUsername,
            String configuredPassword,
            boolean forceAdminEnvSync) {

        String cleanAdminUser = (configuredUsername == null || configuredUsername.isBlank())
                ? "admin"
                : configuredUsername.trim();

        User existingAdmin = userRepository.findByRole(Role.ROLE_ADMIN).stream().findFirst()
                .or(() -> userRepository.findAllByOrderByIdAsc().stream()
                        .filter(u -> u.getUsername() != null && u.getUsername().equalsIgnoreCase(cleanAdminUser))
                        .findFirst())
                .orElse(null);

        if (existingAdmin != null && existingAdmin.isCredentialsCustomized() && !forceAdminEnvSync) {
            log.info("Administrator '{}' has customized login credentials via Admin Command Center; preserving database credentials.",
                    existingAdmin.getUsername());
            return;
        }

        if (configuredPassword != null && !configuredPassword.isBlank()) {
            if (existingAdmin == null) {
                User admin = new User(
                        cleanAdminUser,
                        passwordEncoder.encode(configuredPassword),
                        "Administrator",
                        Role.ROLE_ADMIN
                );
                userRepository.save(admin);
                log.info("Created administrator account '{}' from ADMIN_PASSWORD environment variable.", cleanAdminUser);
            } else {
                boolean canUseTargetUsername = existingAdmin.getUsername().equalsIgnoreCase(cleanAdminUser)
                        || !userRepository.existsByUsernameIgnoreCaseAndIdNot(cleanAdminUser, existingAdmin.getId());
                String targetUsername = canUseTargetUsername ? cleanAdminUser : existingAdmin.getUsername();
                boolean usernameChanged = !existingAdmin.getUsername().equals(targetUsername);
                boolean passwordChanged = !passwordEncoder.matches(configuredPassword, existingAdmin.getPassword());
                if (usernameChanged || passwordChanged) {
                    existingAdmin.setUsername(targetUsername);
                    existingAdmin.setPassword(passwordEncoder.encode(configuredPassword));
                    existingAdmin.incrementTokenVersion();
                    userRepository.save(existingAdmin);
                    log.info("Synchronized administrator credentials for '{}' from environment configuration.", targetUsername);
                }
            }
        } else if (existingAdmin == null) {
            String bootstrapPassword = generateRandomPassword(16);
            User admin = new User(
                    cleanAdminUser,
                    passwordEncoder.encode(bootstrapPassword),
                    "Administrator",
                    Role.ROLE_ADMIN
            );
            userRepository.save(admin);
            log.warn("================================================================================");
            log.warn("BOOTSTRAP ADMIN CREATED -> username: {} | one-time password: {}", cleanAdminUser, bootstrapPassword);
            log.warn("Set ADMIN_USERNAME and ADMIN_PASSWORD environment variables to configure a permanent password.");
            log.warn("================================================================================");
        }
    }

    private String generateRandomPassword(int length) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void seedOrUpgradeCurriculum(
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository) {

        Playlist playlist = playlistRepository.findByYoutubePlaylistId("PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB")
                .or(() -> playlistRepository.findFirstByOrderByIdAsc())
                .orElseGet(() -> {
                    Playlist p = new Playlist(
                            "100 سؤال في التاريخ | One Hundred Questions in History",
                            "PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB",
                            "Master Arabic listening comprehension through authentic historical episodes by Tareekhuna (الأستاذ محمد إلهامي)."
                    );
                    p.setArabicRewardRate(new BigDecimal("15.00"));
                    p.setEnglishRewardRate(new BigDecimal("10.00"));
                    p.setCompletionBonus(new BigDecimal("100.00"));
                    p.setMinEpisodesForBonus(15);
                    return playlistRepository.save(p);
                });

        if (playlist.getMinEpisodesForBonus() < 15) {
            playlist.setMinEpisodesForBonus(15);
            playlist = playlistRepository.save(playlist);
        }

        List<EpisodeSeed> seeds = buildAuthenticCurriculumSeeds();
        int upgradedOrCreated = 0;

        for (int idx = 0; idx < seeds.size(); idx++) {
            EpisodeSeed seed = seeds.get(idx);
            List<QuestionData> fullTwentyQuestions = buildFullTwentyQuestionsForEpisode(idx, seeds);

            Episode existing = episodeRepository
                    .findByPlaylistIdAndEpisodeNumber(playlist.getId(), seed.episodeNumber)
                    .orElse(null);

            String vocabJson = serializeVocab(seed.vocab);

            if (existing == null) {
                Episode created = new Episode(playlist, seed.episodeNumber, seed.title, seed.videoId, seed.durationSeconds);
                created.setVocabularyJson(vocabJson);
                created = episodeRepository.save(created);
                saveShuffledQuiz(quizRepository, created, fullTwentyQuestions);
                upgradedOrCreated++;
            } else {
                boolean wasFakeVideo = LEGACY_PLACEHOLDER_VIDEO_IDS.contains(existing.getYoutubeVideoId())
                        || (existing.getTitle() != null && existing.getTitle().startsWith("Episode "));
                boolean missingVocab = existing.getVocabularyJson() == null || existing.getVocabularyJson().isBlank();

                Quiz existingQuiz = quizRepository.findByEpisodeId(existing.getId()).orElse(null);
                boolean needsQuizUpgrade = wasFakeVideo
                        || existingQuiz == null
                        || existingQuiz.getQuestions().size() < 20
                        || existingQuiz.getPassingScorePercentage() == null
                        || existingQuiz.getPassingScorePercentage() < 100.0;

                if (wasFakeVideo || missingVocab || needsQuizUpgrade) {
                    if (wasFakeVideo || missingVocab) {
                        if (wasFakeVideo) {
                            existing.setYoutubeVideoId(seed.videoId);
                            existing.setTitle(seed.title);
                            existing.setDurationSeconds(seed.durationSeconds);
                        }
                        existing.setVocabularyJson(vocabJson);
                        existing = episodeRepository.save(existing);
                    }

                    if (needsQuizUpgrade) {
                        saveShuffledQuiz(quizRepository, existing, fullTwentyQuestions);
                    }
                    upgradedOrCreated++;
                }
            }
        }

        if (upgradedOrCreated > 0) {
            log.info("Initialized/upgraded {} authentic '100 سؤال في التاريخ' episodes with shuffled 20-question (100% pass) quizzes.", upgradedOrCreated);
        }
    }

    private void saveShuffledQuiz(QuizRepository quizRepository, Episode episode, List<QuestionData> questionsData) {
        Quiz quiz = quizRepository.findByEpisodeId(episode.getId()).orElseGet(() -> new Quiz(episode));
        quiz.setPassingScorePercentage(100.0);
        if (!quiz.getQuestions().isEmpty()) {
            quiz.getQuestions().clear();
            if (quiz.getId() != null) {
                quiz = quizRepository.saveAndFlush(quiz);
            }
        }

        List<QuestionData> orderedQuestions = new ArrayList<>(questionsData);
        Collections.shuffle(orderedQuestions, secureRandom);

        int qOrder = 1;
        for (QuestionData qd : orderedQuestions) {
            Question q = new Question(quiz, qd.text, qOrder++);
            List<OptionData> shuffled = new ArrayList<>(qd.options);
            Collections.shuffle(shuffled, secureRandom);
            for (OptionData od : shuffled) {
                QuestionOption opt = new QuestionOption(q, od.text, od.isCorrect);
                q.getOptions().add(opt);
            }
            quiz.getQuestions().add(q);
        }
        quizRepository.save(quiz);
    }

    private String serializeVocab(List<Map<String, String>> vocab) {
        try {
            return objectMapper.writeValueAsString(vocab);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<EpisodeSeed> buildAuthenticCurriculumSeeds() {
        List<EpisodeSeed> list = new ArrayList<>();

        // EPISODE 01 — EXrpLeyeSeU
        list.add(new EpisodeSeed(
                1,
                "هل تاريخنا مزور؟ | 100 سؤال في التاريخ | الحلقة 01",
                "EXrpLeyeSeU",
                4150,
                List.of(
                        vocab("التَّزْوِير", "At-Tazwir", "Forgery / Falsification", "Distorting historical facts or fabricating records."),
                        vocab("اسْتِشْرَاف المُسْتَقْبَل", "Istishraf al-Mustaqbal", "Anticipating the Future", "Looking ahead by understanding historical laws."),
                        vocab("الِانْتِمَاء", "Al-Intima'", "Belonging / Allegiance", "Identity rooted in the Islamic Ummah across borders."),
                        vocab("الدُّوَل القُطْرِيَّة", "Ad-Duwal al-Qutriyyah", "Modern Territorial Nation-States", "Region-bound states formed in the modern era."),
                        vocab("مُكَلَّف بِالعَمَل", "Mukallaf bil-'Amal", "Duty-Bound to Act", "A Muslim is responsible for righteous effort rather than mere prediction.")
                ),
                List.of(
                        q("ما السؤال الأول الذي افتتحت به الحلقة الأولى من برنامج «100 سؤال في التاريخ»؟ (What opening question launched Episode 1?)",
                                correct("كيف نثق بالتاريخ وواقعنا يُزوَّر أمام أعيننا اليوم؟ (How can we trust history when our present is distorted before our eyes?)"),
                                wrong("كيف بُنيت الأهرامات المصرية القديمة؟ (How were the ancient Egyptian pyramids built?)"),
                                wrong("متى اكتُشف النفط في الجزيرة العربية؟ (When was oil discovered in Arabia?)"),
                                wrong("ما هي أوزان الشعر الجاهلي؟ (What are the meters of pre-Islamic poetry?)")),
                        q("ماذا قرر الأستاذ محمد إلهامي بشأن مسؤولية المسلم تجاه المستقبل؟ (What did Muhammad Elhami emphasize about a Muslim's responsibility toward the future?)",
                                correct("المسلم مكلَّف بالعمل أكثر مما هو مكلَّف بالاستشراف (A Muslim is duty-bound to work rather than merely predict the future)"),
                                wrong("يكتفي المسلم بتوقع الأحداث دون سعي (One should merely predict events without effort)"),
                                wrong("لا علاقة للتاريخ بالحاضر أو المستقبل (History has no connection to present or future)"),
                                wrong("دراسة المستقبل تغني عن العمل الشرعي (Studying the future replaces practical action)")),
                        q("في سؤال «نحن لمن ننتمي؟»، ما الانتماء الأصيل الذي قررته الحلقة للمسلم؟ (To what primary identity did Episode 1 affirm a Muslim belongs?)",
                                correct("الانتماء لأمة الإسلام الواحدة فوق حدود الدول القطرية الحديثة (Belonging to the Islamic Ummah beyond modern nation-state borders)"),
                                wrong("الانتماء الحصري للحدود الاستعمارية الحديثة فقط (Exclusive allegiance to colonial borders alone)"),
                                wrong("عدم الانتماء لأي مجتمع أو أمة (Belonging to no community at all)"),
                                wrong("الانتماء للتحالفات الاقتصادية فقط (Allegiance solely to economic blocs)")),
                        q("لماذا نثق بأن التاريخ الإسلامي في أصوله الكبرى لم يضِع بالتزوير؟ (Why do we trust that Islamic history in its core foundations was not lost to forgery?)",
                                correct("لوجود علم الإسناد وتعدد الرواة والمصادر المستقلة عن السلطة (Due to chains of transmission and independent scholars/sources)"),
                                wrong("لأن كتاباً واحداً فقط كُتب في كل العصور (Because only one single book was ever written)"),
                                wrong("لأن الملوك كتبوا كل كتب التاريخ بأنفسهم (Because kings personally wrote every history book)"),
                                wrong("لعدم وجود أي تدوين مكتوب (Because nothing was ever written down)")),
                        q("ما معنى كلمة «التَّزْوِير» في عنوان الحلقة «هل تاريخنا مزوَّر»؟ (What does 'Tazwir' mean in the episode title?)",
                                correct("التحريف وتزييف الحقائق (Falsification and distortion of facts)"),
                                wrong("الترجمة بين اللغات (Translation between languages)"),
                                wrong("الزيارة والرحلة العلمية (Visiting and scholarly travel)"),
                                wrong("الترتيب الزمني للأحداث (Chronological ordering of events)"))
                )
        ));

        // EPISODE 02 — 4eLlSGg2ToY
        list.add(new EpisodeSeed(
                2,
                "هل زَوَّرَ المستشرقون تاريخنا؟ | 100 سؤال في التاريخ | الحلقة 02",
                "4eLlSGg2ToY",
                4680,
                List.of(
                        vocab("المُسْتَشْرِقُون", "Al-Mustashriqun", "Orientalists", "Western non-Muslim scholars who studied the Islamic world and its sciences."),
                        vocab("المُنْتَصِر", "Al-Muntasir", "The Victor", "Discussed in the maxim 'History is written by the victor—so long as they remain victorious.'"),
                        vocab("المَعَاجِم والفَهَارِس", "Al-Ma'ajim wal-Faharis", "Lexicons & Indexes", "Technical compilation works where Orientalist diligence was useful."),
                        vocab("الرِّوَايَة المَكْذُوبَة", "Ar-Riwayah al-Makdhubah", "Fabricated Report", "False historical narration identified through source comparison."),
                        vocab("الِاسْتِغْرَاب", "Al-Istighrab", "Occidentalism", "Studying Western civilization from an independent Islamic scholarly lens.")
                ),
                List.of(
                        q("ما الخلاصة الدقيقة التي ذكرها المحاضر حول مقولة «التاريخ يكتبه المنتصر»؟ (What nuanced conclusion was given regarding 'History is written by the victor'?)",
                                correct("التاريخ يكتبه المنتصر ما دام منتصراً، ثم تنكشف الحقائق بزوال سطوته (The victor writes it only while victorious; truth surfaces once their dominance fades)"),
                                wrong("المنتصر يمحو كل الحقائق إلى الأبد بلا استثناء (The victor erases all truth forever without exception)"),
                                wrong("المهزوم هو الوحيد الذي يكتب التاريخ دائماً (Only the defeated ever write history)"),
                                wrong("لم يكتب أحد من المؤرخين عن الدول السابقة (No historian ever wrote about past states)")),
                        q("من هم «المستشرقون» كما عرّفتهم الحلقة الثانية؟ (Who are the 'Orientalists' as defined in Episode 2?)",
                                correct("جماعة غربية من غير المسلمين درسوا العالم الإسلامي وعلومه (Western non-Muslim scholars who studied the Islamic world and its sciences)"),
                                wrong("رحالة مسلمون سافروا إلى الصين والهند (Muslim travelers who journeyed to China and India)"),
                                wrong("قضاة الأندلس في العصر الأموي (Judges of Al-Andalus in the Umayyad era)"),
                                wrong("تجار الحرير في سمرقند وبخارى (Silk merchants of Samarkand and Bukhara)")),
                        q("متى بدأ الاستشراق كحركة علمية منظمة في الغرب بحسب الحلقة؟ (When did Orientalism emerge as an organized academic movement?)",
                                correct("في القرن الرابع عشر الميلادي مع وجود جذور أقدم منذ فتح الشام (In the 14th century CE, with earlier roots dating back to the conquest of Bilad al-Sham)"),
                                wrong("في أواخر القرن العشرين فقط (Only in the late 20th century)"),
                                wrong("قبل بعثة النبي ﷺ بخمسة قرون (Five centuries before the Prophet's mission)"),
                                wrong("بعد الحرب العالمية الثانية مباشرة (Immediately after World War II)")),
                        q("في أي مجال أجاد كثير من المستشرقين ويمكن الاستفادة من جهدهم فيه؟ (In which area did Orientalists excel through painstaking compilation?)",
                                correct("الأعمال غير الثقافية كالفهارس والمعاجم وتحقيق المخطوطات التي تحتاج لتتبع وجمع (Compilation works like indexes, concordances, and lexicons requiring diligent cataloging)"),
                                wrong("العقيدة الإسلامية وأصول الفقه والتفسير (Islamic creed, legal theory, and Quranic exegesis)"),
                                wrong("إصدار الفتاوى الشرعية للمسلمين (Issuing Islamic legal rulings)"),
                                wrong("قيادة الجيوش الإسلامية (Commanding Muslim armies)")),
                        q("كيف نصل إلى الحقيقة التاريخية مع وجود الصحيح والضعيف في الروايات؟ (How do historians reach historical truth despite weak reports?)",
                                correct("عبر الوفرة العلمية والمقارنة النقدية بين الروايات والأسانيد (Through scholarly abundance and critical comparison across narrations and chains)"),
                                wrong("برفض جميع كتب التراث دفعة واحدة (By discarding all heritage books at once)"),
                                wrong("بالاعتماد على أول رواية نصادفها (By relying on whichever report we see first)"),
                                wrong("بأخذ التاريخ من الروايات المسرحية (By taking history from theatrical plays)"))
                )
        ));

        // EPISODE 03 — 3PMWns3ikyE
        list.add(new EpisodeSeed(
                3,
                "كيف يكشف التاريخ الكذَّابين؟ | 100 سؤال في التاريخ | الحلقة 03",
                "3PMWns3ikyE",
                3060,
                List.of(
                        vocab("الأَسَانِيد", "Al-Asanid", "Chains of Transmission", "Plural of Isnad; the documented sequence of narrators reporting an event."),
                        vocab("التَّرَاجِم والخِطَط", "At-Tarajim wal-Khitat", "Biographies & Topographical Histories", "Distinctive genres pioneered and expanded by Muslim historians."),
                        vocab("الشُّبْهَة", "Ash-Shubhah", "Specious Doubt / Misconception", "A confusing claim that troubles a believer seeking clarity."),
                        vocab("الإِسْفَنْجَة", "Al-Isfanjah", "The Sponge", "Ibn Taymiyyah's metaphor warning not to let the heart soak up every doubt."),
                        vocab("الزُّجَاجَة المُصْمَتَة", "Az-Zujajah al-Musmatah", "Solid Clear Glass", "Metaphor for a firm, clear heart that sees doubts clearly while repelling them.")
                ),
                List.of(
                        q("بماذا يبدأ القارئ غير المتخصص الذي يستصعب كتب التراث المليئة بالأسانيد؟ (Where should a general reader start if classical books full of chains feel complex?)",
                                correct("يبدأ بكتب التاريخ المعاصرة الموثوقة لتكوين ثقافة تاريخية عامة متدرجة (Start with reliable contemporary history books to build general historical literacy)"),
                                wrong("يترك قراءة التاريخ نهائياً مدى الحياة (Abandon reading history permanently)"),
                                wrong("يقتصر على قراءة كتب المستشرقين وحدها (Read exclusively Orientalist writings)"),
                                wrong("يحفظ آلاف الأسانيد قبل فهم أي حدث (Memorize thousands of chains before reading any event)")),
                        q("ما الإضافة العلمية البارزة التي قدمها علماء المسلمين لعلم التاريخ؟ (What major scholarly contribution did Muslim scholars make to historiography?)",
                                correct("علم العمران عند ابن خلدون وموسوعات التراجم والأعلام والخطط وتأريخ المدن (Ibn Khaldun's social theory plus vast biographical dictionaries and urban histories)"),
                                wrong("لم يضيفوا أي شيء بل اكتفوا بنسخ كتب اليونان (They added nothing and merely copied Greek books)"),
                                wrong("كتبوا الأساطير الخرافية بلا تواريخ (They wrote myths without dates)"),
                                wrong("منعوا تدوين سير العلماء والبلدان (They forbade recording biographies and cities)")),
                        q("كيف فرّقت الحلقة بين صاحب «الشبهة المعرفية» وصاحب «الانحراف الفكري»؟ (How did Episode 3 distinguish a genuine seeker with a doubt from ideological deviation?)",
                                correct("صاحب الشبهة لديه اعتزاز وانتماء للإسلام ويسأل ليطمئن، بخلاف ضعيف الثقة المنكر لأصوله (A genuine seeker holds pride and belonging to Islam and asks for clarity, unlike one lacking trust in Islamic foundations)"),
                                wrong("كل من يطرح سؤالاً تاريخياً فهو منحرف (Anyone asking a historical question is deviant)"),
                                wrong("لا فرق بين السائل للمعرفة والمشكك المتعنت (There is no difference between learning and cynicism)"),
                                wrong("السؤال في التاريخ محرم مطلقاً (Asking historical questions is forbidden)")),
                        q("ما التشبيه البليغ الذي استشهدت به الحلقة لكيفية تعامل القلب مع الشبهات؟ (What famous metaphor was cited for how the heart should handle doubts?)",
                                correct("ألّا يكون كالإسفنجة تتشرب الشبهات، بل كالزجاجة المصمتة يراها بصفائه ويدفعها بصلابته (Not like a sponge soaking up doubts, but like solid clear glass seeing clearly and repelling them firmly)"),
                                wrong("أن يكون كالورقة في مهب الريح (Like a leaf blown by every wind)"),
                                wrong("أن يغلق عينيه ولا يتعلم أبداً (Closing one's eyes and never learning)"),
                                wrong("أن يجمع الشبهات كل يوم بلا علم (Collecting doubts daily without foundational knowledge)")),
                        q("ما معنى مصطلح «علم التراجم» في التراث الإسلامي؟ (What does 'Ilm at-Tarajim' refer to in Islamic scholarship?)",
                                correct("تدوين سِيَر العلماء والرواة والأعلام وأحوالهم وتواريخهم (Biographical documentation of scholars, narrators, and notable figures)"),
                                wrong("الترجمة الفورية في المؤتمرات الحديثة (Simultaneous interpretation at modern conferences)"),
                                wrong("علم الحساب والجبر فقط (Arithmetic and algebra exclusively)"),
                                wrong("فن بناء الجسور والقناطر (The art of building bridges and aqueducts)"))
                )
        ));

        // EPISODE 04 — gfBAAF2UW4w
        list.add(new EpisodeSeed(
                4,
                "هل تاريخنا الإسلامي مليء بالقتل والدماء؟ | 100 سؤال في التاريخ | الحلقة 04",
                "gfBAAF2UW4w",
                3540,
                List.of(
                        vocab("الحَضَارَة", "Al-Hadarah", "Civilization", "Reflects scientific, cultural, moral, and institutional flourishing."),
                        vocab("التَّارِيخ السِّيَاسِي", "At-Tarikh as-Siyasi", "Political History", "Focuses on rulers, wars, and major turning points across states."),
                        vocab("العُلُوم الشَّرْعِيَّة", "Al-'Ulum ash-Shar'iyyah", "Revealed / Islamic Sciences", "Quranic exegesis, Hadith, jurisprudence, and theology."),
                        vocab("العُلُوم التَّطْبِيقِيَّة", "Al-'Ulum at-Tatbiqiyyah", "Applied Sciences", "Medicine, astronomy, optics, engineering, and chemistry."),
                        vocab("عَيْن البَاحِث", "'Ayn al-Bahith", "The Researcher's Eye", "Reading history objectively in full proportion rather than cherry-picking conflicts.")
                ),
                List.of(
                        q("كيف فرّقت الحلقة الرابعة بين «التاريخ» و«الحضارة»؟ (How did Episode 4 distinguish between 'History' and 'Civilization'?)",
                                correct("التاريخ غالباً يرصد الجانب السياسي والعسكري، بينما الحضارة تعبّر عن البناء العلمي والعمراني والأخلاقي المشرق (Chronicles mostly record political/military events, while civilization reflects cultural, scientific, and moral flourishing)"),
                                wrong("لا يوجد أي فرق بين الكلمتين إطلاقاً (There is zero difference between the two terms)"),
                                wrong("الحضارة تعني الحروب والمعارك فقط (Civilization means wars and battles only)"),
                                wrong("التاريخ يختص بعلم الفلك وحده (History deals exclusively with astronomy)")),
                        q("كيف نرد علمياً على دعوى أن التاريخ الإسلامي ليس سوى قتل ودماء ومعارك؟ (How do we respond scholarly to the claim that Islamic history is only bloodshed?)",
                                correct("كل أمة لها تاريخ سياسي وتاريخ حضاري، والمؤرخون يدونون الأحداث الاستثنائية كالحروب أكثر من قرون الاستقرار الطويلة (Every nation has political and civilizational history, and chroniclers record exceptional wars rather than quiet decades of peace)"),
                                wrong("ننكر وقوع أي معركة في التاريخ كله (Deny that any battle ever took place in history)"),
                                wrong("نحذف كتب التاريخ السياسي كلها (Burn or delete all political history books)"),
                                wrong("ندّعي أن الأمم الأخرى لم تخض أي حرب في تاريخها (Claim no other nation ever fought a war)")),
                        q("ماذا أوضحت الحلقة بشأن علاقة المسلمين بالعلوم التطبيقية والمنهج العلمي؟ (What did the episode clarify about Muslims and applied scientific methodology?)",
                                correct("كان المسلمون سبّاقين في تأسيس منهجية البحث العلمي التجريبي إلى جانب العلوم الشرعية (Muslims pioneered empirical scientific research methodology alongside Islamic sciences)"),
                                wrong("حرّم العلماء دراسة الطب والفلك والرياضيات (Scholars prohibited studying medicine, astronomy, and math)"),
                                wrong("اقتصرت الدراسة على الشعر والأدب وحدهما (Study was restricted solely to poetry and literature)"),
                                wrong("بدأت العلوم التطبيقية في القرن العشرين فقط (Applied sciences began only in the 20th century)")),
                        q("ما الإضافة العظمى التي قدمتها الحضارة الإسلامية للحركة العلمية الإنسانية؟ (What supreme contribution did Islamic civilization bring to human science?)",
                                correct("الروح الإيمانية والأخلاقية التي وجهت العلم لخدمة الإنسان والعدل لا للإفساد والتدمير (A moral and faith-grounded compass directing science toward human welfare and justice)"),
                                wrong("احتكار المعرفة ومنع غيرهم من التعلم (Monopolizing knowledge and barring others from learning)"),
                                wrong("استخدام العلم لإبادة الشعوب المستضعفة (Using science to annihilate weaker peoples)"),
                                wrong("فصل الأخلاق تماماً عن الطب والعلم (Completely divorcing ethics from medicine and science)")),
                        q("بأي عين ينبغي لطالب الحقيقة أن يقرأ أحداث التاريخ؟ (With what perspective should a seeker of truth read history?)",
                                correct("بعين الباحث المنصف الشمولي لا بعين الناقد المتصيد للأخطاء (With the fair, holistic eye of a researcher rather than a fault-hunting cynic)"),
                                wrong("بعين الانتقاء لأسوأ الوقائع فقط (By cherry-picking only the worst incidents)"),
                                wrong("بعين الخيال الروائي غير الموثق (Through unverified fictional drama)"),
                                wrong("بإسقاط قوانين الإعلام المعاصر على الماضي دون وعي (Projecting modern media bias blindly onto the past)"))
                )
        ));

        // EPISODE 05 — gXPGtbuP_aI
        list.add(new EpisodeSeed(
                5,
                "من الذي اكتشف الأمريكيتين؟! | 100 سؤال في التاريخ | الحلقة 05",
                "gXPGtbuP_aI",
                3540,
                List.of(
                        vocab("المَنْهَج التَّجْرِيبِي", "Al-Manhaj at-Tajribi", "The Empirical Method", "Building scientific theory on observation and repeatable experiment."),
                        vocab("البَصَرِيَّات", "Al-Basariyyat", "Optics", "Science revolutionized by Ibn al-Haytham through experiment."),
                        vocab("الجَبْر والكِيمْيَاء", "Al-Jabr wal-Kimiya'", "Algebra & Chemistry", "Foundational sciences systematized by Muslim scholars."),
                        vocab("المِلَاحَة البَحْرِيَّة", "Al-Milahah al-Bahriyyah", "Maritime Navigation", "Oceanic cartography and instruments used before Columbus."),
                        vocab("قِصَّة الحَضَارَة", "Qissat al-Hadarah", "The Story of Civilization", "Will Durant's encyclopedic work testifying to Muslim scientific contributions.")
                ),
                List.of(
                        q("بماذا ردّت الحلقة الخامسة على دعوى أن المسلمين كانوا «مجرد ناقلين» لعلوم اليونان؟ (How did Episode 5 refute the claim that Muslims were 'merely translators' of Greek science?)",
                                correct("المسلمون ابتكروا المنهج التجريبي وأسسوا علوماً جديدة كالجبر والكيمياء والبصريات والصيدلة (Muslims pioneered the empirical method and founded sciences like algebra, chemistry, optics, and pharmacology)"),
                                wrong("اعترف المحاضر بأن المسلمين لم يخترعوا أي شيء (The speaker agreed Muslims invented nothing)"),
                                wrong("لم يترجم المسلمون أي كتاب علمي قط (Muslims never translated any scientific book)"),
                                wrong("اهتم المسلمون بالفلسفة النظرية وتركوا التجربة (Muslims only did abstract philosophy and avoided experiments)")),
                        q("من المؤرخ الغربي صاحب موسوعة «قصة الحضارة» الذي استشهدت الحلقة بشهادته لإضافات المسلمين؟ (Which Western historian's 'Story of Civilization' was cited witnessing to Muslim scientific achievements?)",
                                correct("ويل ديورانت (Will Durant)"),
                                wrong("نابليون بونابرت (Napoleon Bonaparte)"),
                                wrong("كريستوفر كولومبوس (Christopher Columbus)"),
                                wrong("تشارلز داروين (Charles Darwin)")),
                        q("ماذا تناولت الحلقة حول حقيقة الوصول إلى الأمريكيتين والرحلات البحرية؟ (What did the episode discuss regarding reaching the Americas and oceanic navigation?)",
                                correct("وجود رحلات وخرائط وملاحة إسلامية وأندلسية سبقت رحلة كولومبوس ومهّدت لعلوم الملاحة الأطلسية (Evidence of Muslim/Andalusian Atlantic voyages, maps, and navigation tools preceding Columbus)"),
                                wrong("أن الأمريكيتين اكتُشفتا في القرن العشرين (That the Americas were discovered in the 20th century)"),
                                wrong("أن المسلمين لم يركبوا البحر أبداً (That Muslims never sailed the seas)"),
                                wrong("أن البوصلة والأسطرلاب اختراع أمريكي حديث (That the compass and astrolabe are modern American inventions)")),
                        q("ماذا فعل الباحثون المعاصرون بالآلات العلمية الدقيقة التي صممها علماء المسلمين؟ (What did modern researchers do with the precision instruments designed by Muslim scientists?)",
                                correct("أعادوا إنتاج وتحقيق وتشغيل تلك الآلات المبتكرة لإثبات دقتها الهندسية (Reconstructed and tested those historical Muslim machines, proving their engineering precision)"),
                                wrong("اعتبروها مجرد رسوم خيالية غير قابلة للتنفيذ (Dismissed them as unbuildable fantasy sketches)"),
                                wrong("لم يعثروا على أي مخطوطة هندسية إسلامية (Found zero Islamic engineering manuscripts)"),
                                wrong("نسبوها جميعاً إلى العصر الحجري (Attributed them all to the Stone Age)")),
                        q("ما معنى مصطلح «المنهج التجريبي» في البحث العلمي؟ (What does 'Al-Manhaj at-Tajribi' mean in scientific inquiry?)",
                                correct("اعتماد الملاحظة والتجربة العملية لاختبار الفرضيات وبناء النظريات (Relying on observation and practical experimentation to test hypotheses)"),
                                wrong("الاكتفاء بالجدل اللفظي دون فحص الواقع (Relying solely on verbal debate without testing reality)"),
                                wrong("نقل الأخبار دون تمحيص أو تجربة (Copying claims without testing or verification)"),
                                wrong("تخمين النتائج بالظن والتنجيم (Guessing outcomes via astrology)"))
                )
        ));

        // EPISODE 06 — mXkVqjjq4PA
        list.add(new EpisodeSeed(
                6,
                "هل أجبر المسلمون الأمم على تعلم العربية بالإكراه؟ | 100 سؤال في التاريخ | الحلقة 06",
                "mXkVqjjq4PA",
                3600,
                List.of(
                        vocab("الإِكْرَاه", "Al-Ikrah", "Compulsion / Coercion", "Forcing someone against their will—contrasted with voluntary adoption of Arabic."),
                        vocab("العَصْر الذَّهَبِي", "Al-'Asr adh-Dhahabi", "The Golden Age", "Defined in Islamic terms primarily by justice, faith, and human dignity."),
                        vocab("الخِلَافَة الرَّاشِدَة", "Al-Khilafah ar-Rashidah", "The Rightly Guided Caliphate", "Identified by the speaker as the true moral Golden Age of Islam."),
                        vocab("الشَّمَال الإِفْرِيقِي", "Ash-Shamal al-Ifriqi", "North Africa", "Region where Arabic spread deeply through Quranic devotion and culture."),
                        vocab("التَّرَابُط والتَّمَاسُك", "At-Tarabut wat-Tamasuk", "Cohesion & Solidarity", "Shared Arabic language strengthens unity across Muslim peoples.")
                ),
                List.of(
                        q("ما هو «العصر الذهبي» الحقيقي للحضارة الإسلامية كما قرره الأستاذ محمد إلهامي في الحلقة السادسة؟ (Which era did Muhammad Elhami identify as the true Golden Age of Islamic civilization?)",
                                correct("عصر الخلافة الراشدة لتحقق العدل والكرامة والقيم الإسلامية في أبهى صورها (The Rightly Guided Caliphate, where justice, faith, and human dignity reached their peak)"),
                                wrong("عصر الاستعمار الأوروبي الحديث (The modern European colonial era)"),
                                wrong("العصر الجاهلي قبل الإسلام (The pre-Islamic Jahiliyyah period)"),
                                wrong("فترة سقوط الأندلس في القرن الخامس عشر (The fall of Granada in the 15th century)")),
                        q("هل أجبر المسلمون الشعوب المفتوحة على التحدث باللغة العربية بالقوة والإكراه؟ (Did Muslims force conquered peoples to speak Arabic by coercion?)",
                                correct("لا، بل أقبلت الشعوب على العربية طواعيةً حباً للقرآن والعلم، وبقيت لغات كالفارسية والأمازيغية والتركية حية (No; peoples embraced Arabic voluntarily through love of the Quran and scholarship, while local languages endured)"),
                                wrong("نعم، عاقبوا بالسجن كل من نطق بكلمة غير عربية (Yes, they imprisoned anyone who spoke a non-Arabic word)"),
                                wrong("أحرقوا جميع المعاجم غير العربية (They burned all non-Arabic dictionaries)"),
                                wrong("لم تنتشر اللغة العربية خارج مكة والمدينة قط (Arabic never spread outside Mecca and Medina)")),
                        q("ما الفرق الجوهري بين وعد الله للأمة بالنصر وبين واجب الفرد المسلم؟ (What distinction did the episode draw between Allah's promise of victory to the Ummah and individual duty?)",
                                correct("الأمة موعودة بالنهوض والنصر، لكن الأفراد مكلّفون بالعمل والسعي والجهاد (The Ummah is promised revival, while individuals remain accountable for striving and action)"),
                                wrong("الوعد بالنصر يعني الجلوس وترك السعي والعمل (The promise of victory means sitting idle without effort)"),
                                wrong("لا أمل في نهوض الأمة مرة أخرى أبداً (There is zero hope of future revival)"),
                                wrong("العمل مطلوب من الحكام وحدهم دون العلماء والأفراد (Effort is required only from rulers, not individuals)")),
                        q("لماذا أكد المحاضر أهمية اللغة العربية للعالم الإسلامي المعاصر؟ (Why did the speaker highlight the importance of Arabic across the Muslim world today?)",
                                correct("لزيادة قوة ترابط المسلمين وتماسكهم وصلتهم المباشرة بالقرآن والتراث العلمي (To strengthen Muslim unity, cohesion, and direct connection to the Quran and scholarly heritage)"),
                                wrong("لإلغاء التجارة مع الدول الآسيوية (To halt trade with Asian nations)"),
                                wrong("لمنع تعلم اللغات الأجنبية في الجامعات (To ban learning foreign languages in universities)"),
                                wrong("لأنها لغة لا قواعد لها (Because it is a language without grammar)")),
                        q("ماذا تعني عبارة «أخلاقيات القتال في الإسلام» التي أشارت إليها الحلقة؟ (What did the episode note about Islamic ethics even in warfare?)",
                                correct("التزام المسلمين بضوابط أخلاقية صارمة تحرم الغدر وقتل المدنيين وتدمير العمران (Strict moral rules forbidding treachery, harming non-combatants, and wanton destruction)"),
                                wrong("إباحة كل الوسائل دون قيد أخلاقي (Permitting any means without moral restraint)"),
                                wrong("محاكاة الحروب الإبادية الحديثة (Imitating modern wars of annihilation)"),
                                wrong("تدمير المزارع والمدن بعد المعارك (Destroying farms and cities after battles)"))
                )
        ));

        // EPISODE 07 — GnTfCFairH8
        list.add(new EpisodeSeed(
                7,
                "كيف تعامل المسلمون مع إرث حضارات الأمم الأخرى | 100 سؤال في التاريخ | الحلقة 07",
                "GnTfCFairH8",
                3345,
                List.of(
                        vocab("إِرْث الحَضَارَات", "Irth al-Hadarat", "Heritage of Civilizations", "Legacy left by earlier Persian, Roman, Egyptian, and Mesopotamian societies."),
                        vocab("التَّأْثِير والتَّأَثُّر", "At-Ta'thir wat-Ta'aththur", "Mutual Interaction", "Natural cultural exchange evaluated through Islamic principles."),
                        vocab("النَّظَر والتَّقْيِيم", "An-Nazar wat-Taqyim", "Critical Examination & Evaluation", "Accepting what is beneficial and rejecting what contradicts faith/morals."),
                        vocab("الإِصْلَاح والتَّغْيِير", "Al-Islah wat-Taghyir", "Reform & Transformation", "Societal renewal requiring deep grasp of political history."),
                        vocab("التَّثْقِيف والتَّرْبِيَة", "At-Tathqif wat-Tarbiyah", "Education & Moral Cultivation", "Nurturing awareness where civilizational history shines.")
                ),
                List.of(
                        q("كيف تعامل المسلمون مع إرث الحضارات السابقة كالفارسية والرومانية والمصرية؟ (How did Muslims engage with the heritage of earlier civilizations?)",
                                correct("شكلاً بالاحترام وعدم التدمير، ومضموناً بالنظر والتقييم: فما كان نافعاً قُبل وما خالف الحق رُدّ (With respect in form, and critical evaluation in substance: accepting beneficial knowledge and rejecting falsehood)"),
                                wrong("دمروا كل الآثار والعلوم الإدارية والطبية السابقة (Destroyed all monuments, medicine, and administrative tools)"),
                                wrong("قلدوا كل عقائد الأمم السابقة بلا تمييز (Blindly copied all pagan beliefs without filtering)"),
                                wrong("انعزلوا في الصحراء ورفضوا دخول المدن (Isolated in the desert and refused to enter cities)")),
                        q("ما القاعدة الاجتماعية التي ذكرتها الحلقة عند تواصل أمتين معاً؟ (What historical principle occurs whenever two civilizations interact?)",
                                correct("لا تتواصل أمة مع أمة إلا ويحصل نوع من التأثير والتأثر وفق قوة كل منهما ووعيها (Every contact between nations involves mutual influence shaped by each side's strength and awareness)"),
                                wrong("لا يتأثر أي شعب بغيره أبداً (No society ever influences another)"),
                                wrong("الأمة القوية تذوب دائماً في الأمة الضعيفة بلا شروط (Strong nations always dissolve unconditionally)"),
                                wrong("التواصل بين الشعوب مستحيل تاريخياً (Contact between peoples is historically impossible)")),
                        q("أيهما أهم بحسب تفصيل الحلقة السابعة: التاريخ السياسي أم التاريخ الحضاري؟ (Which is more important according to Episode 7: political history or civilizational history?)",
                                correct("التاريخ السياسي أهم لمن يعمل في الإصلاح والتغيير، والتاريخ الحضاري هام لمن يعمل في الدعوة والتربية والتثقيف (Political history is vital for reformers/leaders, while civilizational history is vital for educators and callers)"),
                                wrong("التاريخ السياسي لا فائدة منه مطلقاً (Political history has zero benefit whatsoever)"),
                                wrong("التاريخ الحضاري مضيعة للوقت (Civilizational history is a waste of time)"),
                                wrong("كلاهما خاص بالمتاحف فقط (Both belong solely in museums)")),
                        q("لماذا لم يهدم الصحابة الفاتحون المنشآت العمرانية والآثار القديمة في البلاد المفتوحة؟ (Why didn't the Companions demolish ancient monuments and infrastructure in opened lands?)",
                                correct("لأن منهج الإسلام يحفظ العمران النافع ويركز على تحرير الإنسان وهدايته لا تخريب الأرض (Because Islam preserves beneficial structures and focuses on guiding people rather than vandalizing lands)"),
                                wrong("لأنهم لم يدخلوا مصر ولا الشام ولا العراق (Because they never entered Egypt, Syria, or Iraq)"),
                                wrong("لعدم امتلاكهم أدوات البناء (Because they lacked construction tools)"),
                                wrong("لأنهم عبدوا تلك الآثار القديمة (Because they worshipped those ancient monuments)")),
                        q("ما معنى كلمة «الإِرْث» في قولنا «إرث الحضارات»؟ (What does the Arabic word 'Irth' mean in 'Irth al-Hadarat'?)",
                                correct("الميراث والنتائج العلمي والعمراني المتبقي عن الأمم السابقة (The legacy and accumulated achievements inherited from earlier peoples)"),
                                wrong("الحرب البحرية الطويلة (A prolonged naval war)"),
                                wrong("الضريبة السنوية على التجار (An annual tax on merchants)"),
                                wrong("السفر في فصل الشتاء (Traveling during winter)"))
                )
        ));

        // EPISODE 08 — bX-be9CtE_k
        list.add(new EpisodeSeed(
                8,
                "متى انتقلت الحضارة الإسلامية إلى الغرب؟ | 100 سؤال في التاريخ | الحلقة 08",
                "bX-be9CtE_k",
                3240,
                List.of(
                        vocab("الِازْدِهَار والضَّعْف", "Al-Izdihar wad-Da'f", "Flourishing & Decline", "Historical cycles of civilizational strength and regression."),
                        vocab("المَعَابِر الحَضَارِيَّة", "Al-Ma'abir al-Hadariyyah", "Civilizational Bridges", "Major geographical corridors transferring science to Europe."),
                        vocab("الأَنْدَلُس وصِقِلِّيَة", "Al-Andalus wa Siqilliyyah", "Andalusia & Sicily", "Crucial centers where Arabic sciences were translated into Latin."),
                        vocab("ذُرْوَة الانْكِسَار", "Dhurwat al-Inkisar", "Peak of Fracture", "Marked politically by the abolition of the Caliphate in the 20th century."),
                        vocab("مَسَار النُّهُوض", "Masar an-Nuhud", "Trajectory of Revival", "The ongoing awakening and recovery of the Ummah since the late 20th century.")
                ),
                List.of(
                        q("ما المعابر الثلاثة الكبرى التي انتقلت عبرها علوم الحضارة الإسلامية إلى الغرب؟ (What were the three major bridges through which Islamic civilization transferred science to Europe?)",
                                correct("الشام إبان الحروب الصليبية، والأندلس وصقلية، والاحتكاك مع الدولة العثمانية (Bilad al-Sham during the Crusades, Al-Andalus & Sicily, and contact with the Ottoman state)"),
                                wrong("أستراليا واليابان وأمريكا الجنوبية (Australia, Japan, and South America)"),
                                wrong("سيبيريا واسكندنافيا وأيسلندا (Siberia, Scandinavia, and Iceland)"),
                                wrong("الصحراء الكبرى وحدها في القرن العشرين (The Sahara Desert alone in the 20th century)")),
                        q("ماذا حددت الحلقة الثامنة باعتباره «ذروة الانكسار السياسي» و«قاع الضعف» في العصر الحديث؟ (What did Episode 8 identify as the political fracture peak and the lowest point before revival?)",
                                correct("ذروة الانكسار سقوط الخلافة، والقاع كان في ستينيات القرن العشرين ثم بدأ مسار النهوض واستعادة الوعي (The peak of fracture was the fall of the Caliphate, bottoming out in the 1960s before revival began)"),
                                wrong("أن القاع كان في القرن الأول الهجري (That the lowest point was in the 1st century AH)"),
                                wrong("أن الحضارة الإسلامية انتهت في العصر الأموي (That Islamic civilization ended in the Umayyad era)"),
                                wrong("أنه لم يحدث أي تراجع في القرنين الأخيرين (That zero decline occurred in the past two centuries)")),
                        q("أي جزيرة متوسطية ذُكرت إلى جانب الأندلس كمعبر رئيسي لترجمة العلوم العربية إلى أوروبا؟ (Which Mediterranean island alongside Al-Andalus was a premier bridge translating Arabic science into Europe?)",
                                correct("صِقِلِّيَة (Sicily)"),
                                wrong("آيسلندا (Iceland)"),
                                wrong("مدغشقر (Madagascar)"),
                                wrong("تسمانيا (Tasmania)")),
                        q("هل التراجع الحضاري للأمة الإسلامية حدث فجأة في يوم واحد أم عبر مراحل؟ (Did civilizational decline happen overnight in a single day or gradually across stages?)",
                                correct("حدث عبر مراحل تراكمية طويلة، وكذلك النهوض يسير في مسار متدرج متصاعد (It unfolded gradually over cumulative stages, just as revival progresses steadily)"),
                                wrong("حدث في ساعة واحدة بلا مقدمات (It happened in a single hour with zero precursors)"),
                                wrong("سببه الوحيد تغير المناخ والأمطار (Its sole cause was rainfall and weather)"),
                                wrong("توقفت الأمة عن إنجاب العلماء منذ القرن الثاني الهجري (No scholars appeared after the 2nd century AH)")),
                        q("ما معنى كلمة «مَعْبَر» (جمعها مَعَابِر) في سياق انتقال الحضارة؟ (What does 'Ma'bar' / 'Ma'abir' mean in the context of civilizational transfer?)",
                                correct("جسر أو طريق تواصل تنتقل عبره العلوم والمعارف بين الأمم (A bridge or corridor of contact transferring sciences between nations)"),
                                wrong("سجن حربي محصن (A fortified military prison)"),
                                wrong("عملة نقدية ذهبية (A gold monetary coin)"),
                                wrong("معاهدة وقف القتال فقط (A temporary ceasefire treaty only)"))
                )
        ));

        // EPISODE 09 — ntgiq-JXYeU
        list.add(new EpisodeSeed(
                9,
                "كيف نتوثق من أحداث السيرة النبوية؟ | 100 سؤال في التاريخ | الحلقة 09",
                "ntgiq-JXYeU",
                2495,
                List.of(
                        vocab("السِّيرَة النَّبَوِيَّة", "As-Sirah an-Nabawiyyah", "Prophetic Biography", "The documented life, character, and mission of Prophet Muhammad ﷺ."),
                        vocab("الصَّحَابَة والتَّابِعُون", "As-Sahabah wat-Tabi'un", "Companions & Successors", "First two generations who transmitted the Quran, Hadith, and Sirah."),
                        vocab("دَرَجَات الصِّحَّة", "Darajat as-Sihhah", "Grades of Authenticity", "Rigorous classification into Sahih (authentic), Hasan (good), and Da'if (weak)."),
                        vocab("إِثْبَات النُّبُوَّة", "Ithbat an-Nubuwwah", "Proving Prophethood", "Rational and historical proofs establishing the truth of Muhammad's ﷺ message."),
                        vocab("البَعْثَة النَّبَوِيَّة", "Al-Bi'thah an-Nabawiyyah", "The Prophetic Commissioning", "The beginning of divine revelation to Prophet Muhammad ﷺ at age forty.")
                ),
                List.of(
                        q("لماذا تُعد سيرة النبي ﷺ أوثق سيرة منقولة لإنسان في التاريخ البشري؟ (Why is Prophet Muhammad's ﷺ biography the most rigorously documented life in human history?)",
                                correct("لأن تدوينها بدأ في زمنه، وأهم مصادرها القرآن والحديث بالأسانيد مع معرفة درجة الصحة والحسن والضعف لكل رواية (Writing began in his lifetime, anchored by Quran and graded Hadith chains distinguishing authentic from weak reports)"),
                                wrong("لأنها كُتبت لأول مرة بعد ألف سنة من وفاته (Because it was first written a thousand years after his death)"),
                                wrong("لعدم وجود أسانيد أو أسماء للرواة فيها (Because it contains no narrator names or chains)"),
                                wrong("لأن المستشرقين هم الذين جمعوها في القرن التاسع عشر (Because Orientalists compiled it in the 19th century)")),
                        q("ما أهم مصدرين أصليين يُعتمد عليهما في التوثق من أحداث السيرة النبوية؟ (What are the two foundational sources for authenticating events of the Prophetic Sirah?)",
                                correct("القرآن الكريم والسنة النبوية الصحيحة (The Noble Quran and authentic Prophetic Sunnah)"),
                                wrong("القصص الشعبية المتأخرة والأشعار المجهولة (Late folk tales and anonymous poems)"),
                                wrong("حوليات الإمبراطورية الرومانية وحدها (Roman imperial annals alone)"),
                                wrong("كتب الفلسفة اليونانية القديمة (Ancient Greek philosophy texts)")),
                        q("كيف تفند الحلقة محاولات تفسير نبوة محمد ﷺ بعوامل قبلية أو اقتصادية أو اجتماعية؟ (How does Episode 9 refute claims attributing the Prophet's ﷺ mission to tribal or material motives?)",
                                correct("تنهار تلك الدعاوى بأدنى نظر في صدقه وأمانته وزهده قبل البعثة وبعدها ورفضه الملك والمال (Such claims collapse upon examining his lifelong truthfulness, asceticism, and rejection of wealth/kingship offered by Quraysh)"),
                                wrong("بتجاهل سيرته قبل سن الأربعين (By ignoring his life before age forty)"),
                                wrong("بالقول إنه كان يملك جيوشاً وخزائن ذهب قبل البعثة (By claiming he owned vast armies and gold before revelation)"),
                                wrong("بعدم مناقشة غير المسلمين عقلياً (By refusing rational dialogue with non-Muslims)")),
                        q("متى بدأت كتابة وتدوين وثائق وأحاديث السيرة النبوية؟ (When did writing down Prophetic Hadith and Sirah documents begin?)",
                                correct("بدأت في حياة النبي ﷺ وعصر الصحابة والتابعين بعناية فائقة (It began during the Prophet's ﷺ own lifetime and continued rigorously under the Companions and Tabi'un)"),
                                wrong("في العصر العباسي المتأخر فقط (Only in the late Abbasid era)"),
                                wrong("بعد ظهور الطباعة الحديثة في أوروبا (Only after modern printing appeared in Europe)"),
                                wrong("في القرن الخامس الهجري لأول مرة (For the first time in the 5th century AH)")),
                        q("ما ميزة المنهج العلمي الإسلامي في التعامل مع روايات السيرة المتنوعة؟ (What distinguishes Islamic scholarly methodology regarding diverse Sirah narrations?)",
                                correct("إمكانية محاكمة وفحص كل رواية علمياً ومنهجياً لمعرفة صحيحها من ضعيفها (Every report can be audited methodologically to distinguish authentic from weak)"),
                                wrong("قبول كل ما كُتب في أي كتاب بلا فحص (Accepting everything in any book without scrutiny)"),
                                wrong("إخفاء أسماء الرواة عن القراء (Hiding narrator identities from readers)"),
                                wrong("الاعتماد على الذوق الشخصي وحده (Relying on personal whim alone)"))
                )
        ));

        // EPISODE 10 — pUGO616wjf8
        list.add(new EpisodeSeed(
                10,
                "هل ورد ذكر النبي ﷺ في التوراة والإنجيل؟ | 100 سؤال في التاريخ | الحلقة 10",
                "pUGO616wjf8",
                3585,
                List.of(
                        vocab("دَلَائِل النُّبُوَّة", "Dala'il an-Nubuwwah", "Proofs of Prophethood", "Miraculous knowledge, prophecies, and signs proving divine revelation."),
                        vocab("البِشَارَات", "Al-Bisharat", "Glad Tidings / Foretellings", "Biblical prophecies heralding the coming of the final Prophet ﷺ."),
                        vocab("التَّوْرَاة والإِنْجِيل", "At-Tawrah wal-Injil", "The Torah & The Gospel", "Scriptures revealed before the Quran containing descriptions of the Prophet ﷺ."),
                        vocab("العَبْقَرِيَّة البَشَرِيَّة", "Al-'Abqariyyah al-Bashariyyah", "Human Genius", "Shown to be insufficient to explain unseen divine knowledge in Quran and Sunnah."),
                        vocab("الوَحْي الإِلٰهِي", "Al-Wahy al-Ilahi", "Divine Revelation", "Knowledge revealed by Allah transcending human conjecture.")
                ),
                List.of(
                        q("لماذا لا يمكن تفسير ما جاء في القرآن والسنة بمجرد «العبقرية والذكاء البشري»؟ (Why can't the contents of the Quran and Sunnah be explained away as mere 'human genius'?)",
                                correct("لامتلائهما بالعلوم والغيبيات والتشريعات المحكمة التي يستحيل صدورها بالذكاء البشري دون وحي إلهي (Because they contain unseen truths, prophecies, and comprehensive legislation impossible through mere human intellect without revelation)"),
                                wrong("لأن الذكاء البشري لم يكن موجوداً في التاريخ القديم (Because human intelligence did not exist in antiquity)"),
                                wrong("لأن العرب كانوا يملكون جامعات ومختبرات فضائية (Because ancient Arabia had modern space laboratories)"),
                                wrong("لأن القرآن كتاب في الهندسة الميكانيكية فقط (Because the Quran is solely a mechanical engineering manual)")),
                        q("ماذا قررت الحلقة العاشرة بشأن ذكر النبي محمد ﷺ في التوراة والإنجيل؟ (What did Episode 10 conclude regarding the mention of Prophet Muhammad ﷺ in the Torah and Gospel?)",
                                correct("ثبت ذكره ﷺ وبشاراته في التوراة والإنجيل، والقرآن الكريم والشواهد التاريخية يؤكدان ذلك (His foretelling in the Torah and Gospel is established, affirmed by both the Quran and historical testimonies)"),
                                wrong("لم يُذكر أي نبي في الكتب السابقة مطلقاً (No prophet was ever mentioned in earlier scriptures)"),
                                wrong("لم يكن أهل الكتاب ينتظرون نبياً في الجزيرة العربية (People of the Book never awaited a prophet in Arabia)"),
                                wrong("البشارات كُتبت لأول مرة في القرن التاسع عشر (Prophecies were written for the first time in the 19th century)")),
                        q("كيف استشهد علماء الإسلام تاريخياً بعلماء أهل الكتاب الذين أسلموا في عصر النبوة؟ (How did Muslim scholars cite learned People of the Book who embraced Islam in the Prophet's ﷺ era?)",
                                correct("عرفوا صفات النبي ﷺ ومخرجَه من كتبهم كعبد الله بن سلام وسلمان الفارسي وغيرهم (Scholars like 'Abdullah ibn Salam recognized the Prophet's ﷺ exact descriptions and migration place from their scriptures)"),
                                wrong("لم يسلم أي عالم من أهل الكتاب في التاريخ (Zero scholars of the People of the Book ever embraced Islam)"),
                                wrong("أسلموا دون أن يقرؤوا أي كتاب سابق (They had never read any scripture before)"),
                                wrong("كانوا يسكنون في أقصى غرب أوروبا فقط (They lived exclusively in western Europe)")),
                        q("ما معنى مصطلح «دلائل النبوة» في المؤلفات الإسلامية؟ (What does the scholarly genre 'Dala'il an-Nubuwwah' study?)",
                                correct("البراهين العقلية والحسية والغيبية والأخلاقية الدالة على صدق رسالة النبي ﷺ (Rational, miraculous, prophetic, and moral proofs establishing the truth of the Prophet's ﷺ mission)"),
                                wrong("أسماء القبائل العربية وأنسابها فقط (Names and lineages of Arab tribes only)"),
                                wrong("قوانين الملاحة في البحر الأحمر (Navigation rules in the Red Sea)"),
                                wrong("أسعار السلع في أسواق مكة (Commodity prices in Meccan markets)")),
                        q("ما الفرق بين «الوحي الإلهي» و«الاجتهاد العبقري» كما وضحته الحلقة؟ (What distinguishes Divine Revelation from human genius according to the episode?)",
                                correct("الوحي علم يقيني معصوم من عند الله يحيط بالغيب والتشريع الكامل، أما العبقرية فجهد بشري محدود يخطئ ويصيب (Revelation is infallible knowledge from Allah encompassing the unseen, whereas human genius is bounded and fallible)"),
                                wrong("لا فرق بينهما في المصدر والنتيجة (There is no difference in source or outcome)"),
                                wrong("العبقرية البشرية تعلم الغيب المستقبل بدقة تامة (Human genius knows the unseen future with 100% certainty)"),
                                wrong("الوحي يختص بالشعر العربي فقط (Revelation concerns Arabic poetry alone)"))
                )
        ));

        // EPISODE 11 — 5NAlRisTpWk
        list.add(new EpisodeSeed(
                11,
                "بم تميزت شخصية محمد ﷺ ليكون النبي المصطفى والرسول الخاتم؟ | 100 سؤال في التاريخ | الحلقة 11",
                "5NAlRisTpWk",
                4850,
                List.of(
                        vocab("الِاصْطِفَاء الإِلٰهِي", "Al-Istifa' al-Ilahi", "Divine Selection", "Allah chooses His messengers through infinite wisdom, not human elitism."),
                        vocab("الخَاتَم", "Al-Khatam", "The Seal (Final Messenger)", "Prophet Muhammad ﷺ as the culmination of prophethood for all humanity."),
                        vocab("رَعْي الغَنَم", "Ra'y al-Ghanam", "Shepherding Flock", "Prophetic training cultivating patience, mercy, vigilance, and leadership."),
                        vocab("المَنْفَعَة العَاجِلَة", "Al-Manfa'ah al-'Ajilah", "Immediate Worldly Gain", "Status, wealth, and custom that kept many leaders from accepting truth."),
                        vocab("مُؤَهِّلَات الزَّعَامَة", "Mu'ahhilat az-Za'amah", "Qualities of Leadership", "Integrity, courage, wisdom, and noble character evident throughout his life.")
                ),
                List.of(
                        q("ما الأسباب الرئيسية التي منعت كثيراً من أحبار وزعماء أهل الكتاب وغيرهم من الإيمان بالنبي ﷺ رغم معرفتهم بصفته؟ (What primary barriers kept many leaders from believing despite recognizing the Prophet's ﷺ signs?)",
                                correct("المصلحة الدنيوية والمنفعة المادية العاجلة والشهوة وسلطان العادات والتقاليد (Worldly self-interest, attachment to status/wealth, desires, and blind adherence to custom)"),
                                wrong("عدم وصول أي خبر عن بعثته إليهم إطلاقاً (Never hearing any news of his mission at all)"),
                                wrong("لأنه دعاهم بغير اللغة العربية في مكة (Because he preached in a non-Arabic language in Mecca)"),
                                wrong("لأنه طلب منهم أموالاً طائلة للدخول في الإسلام (Because he demanded wealth to enter Islam)")),
                        q("كيف أجابت الحلقة الحادية عشرة عن سؤال: لماذا اختار الله نبياً رعى الغنم ومن بيئة عربية؟ (How did Episode 11 answer why Allah chose a shepherd from Arabia to bear the final message?)",
                                correct("عقولنا ليست حاكمة على اختيار الله ﴿وَرَبُّكَ يَخْلُقُ مَا يَشَاءُ وَيَخْتَارُ﴾، وكل تفصيلة في حياته ﷺ كانت إعداداً ربانياً لمؤهلات القيادة والرسالة (Human bias cannot dictate Allah's choice; every detail of his life and environment was divine preparation for universal leadership)"),
                                wrong("الرسالة لا تصلح إلا لملوك الإمبراطوريات الكبرى (Prophethood is suitable only for emperors of superpowers)"),
                                wrong("رعي الغنم لم يمارسه أي نبي سابق (No earlier prophet ever tended sheep)"),
                                wrong("الاختيار كان بالانتخاب القبلي في دار الندوة (Selection happened by tribal vote in Mecca)")),
                        q("ما الحكمة التربوية والقيادية في عمل الأنبياء عليهم السلام برعي الغنم؟ (What leadership wisdom lies in prophets tending sheep?)",
                                correct("التدرب على الصبر والرحمة بالضعيف واليقظة وحسن الرعاية وجمع المتفرق (Cultivating patience, compassion for the weak, vigilance, and uniting the flock)"),
                                wrong("جمع الثروات الذهبية الطائلة بسرعة (Accumulating massive gold fortunes quickly)"),
                                wrong("الابتعاد الكلي عن تحمل المسؤولية (Avoiding responsibility altogether)"),
                                wrong("تعلم الفنون القتالية البحرية (Learning naval combat tactics)")),
                        q("بماذا عُرف النبي محمد ﷺ في مجتمع مكة طوال أربعين عاماً قبل البعثة؟ (By what title and character was Muhammad ﷺ renowned in Mecca for forty years before revelation?)",
                                correct("بالصادق الأمين، وبكمال المروءة ونصرة المظلوم والترفع عن رذائل الجاهلية (As As-Sadiq Al-Amin—the Truthful and Trustworthy—upholding justice and shunning Jahili vices)"),
                                wrong("بجمع الضرائب من القوافل (Collecting taxes from caravans)"),
                                wrong("بكتابة القصائد الهجائية في الأسواق (Composing satirical poetry in markets)"),
                                wrong("بالعزلة التامة عن نصرة الضعفاء (Total indifference to helping the oppressed)")),
                        q("ما معنى قوله تعالى الذي استشهدت به الحلقة: ﴿مَا كَانَ لَهُمُ الْخِيَرَةُ﴾؟ (What does the Quranic phrase cited in the episode 'Ma kana lahumu al-khiyarah' mean?)",
                                correct("ليس للبشر أن يقترحوا أو يفرضوا على الله من يصطفيه لحمل رسالته (Humans do not dictate whom Allah chooses and commissions as His Messenger)"),
                                wrong("ليس للبشر حرية في البيع والشراء (Humans have no choice in commerce)"),
                                wrong("لا يجوز السفر بين البلدان (Traveling between lands is prohibited)"),
                                wrong("لا فرق بين الصدق والكذب (There is no difference between truth and falsehood)"))
                )
        ));

        // EPISODE 12 — gMPuTxVW9bs
        list.add(new EpisodeSeed(
                12,
                "كيف للنبي أن يتزوج 11 امرأة إحداهنّ من القاصرات؟ | 100 سؤال في التاريخ | الحلقة 12",
                "gMPuTxVW9bs",
                3488,
                List.of(
                        vocab("حِكَم تَشْرِيعِيَّة", "Hikam Tashri'iyyah", "Legislative Wisdoms", "Legal and educational purposes transmitting family jurisprudence to the Ummah."),
                        vocab("فِقْه السَّيِّدَة عَائِشَة", "Fiqh as-Sayyidah 'A'ishah", "Scholarship of Lady 'A'ishah", "Her extraordinary intellect transmitted thousands of Prophetic teachings."),
                        vocab("الهِجْرَة النَّبَوِيَّة", "Al-Hijrah an-Nabawiyyah", "The Prophetic Migration", "Foundational turning point marking the establishment of the Islamic state."),
                        vocab("التَّأْرِيخ الهِجْرِي", "At-Ta'rikh al-Hijri", "The Hijri Calendar", "Instituted under Caliph 'Umar ibn al-Khattab with Companion consensus."),
                        vocab("قِيَام الدَّوْلَة", "Qiyam ad-Dawlah", "Establishment of the State", "Why the Hijrah—rather than birth or battle—was chosen to anchor Muslim chronology.")
                ),
                List.of(
                        q("ما الحِكَم الأساسية في تعدد زواج النبي ﷺ بعد سن الخمسين كما أوضحت الحلقة الثانية عشرة؟ (What primary wisdoms underlay the Prophet's ﷺ marriages after age fifty?)",
                                correct("حِكَم تشريعية وتعليمية وسياسية واجتماعية لنقل فقه الأسرة وتأليف القبائل، بعد أن عاش شبابه كله مع السيدة خديجة وحدها (Legislative, educational, and social/political wisdoms—having spent his entire youth married solely to Khadijah)"),
                                wrong("أنه تزوجهن جميعاً في سن العشرين بمكة (That he married them all at age twenty in Mecca)"),
                                wrong("أنه لم ينقل عن أمهات المؤمنين أي حديث شرعي (That zero legal teachings were transmitted through the Mothers of the Believers)"),
                                wrong("تقليد أباطرة الروم والفرس في قصورهم (Imitating Roman and Persian palace luxury)")),
                        q("ماذا أبرزت الحلقة عن الدور العلمي للسيدة عائشة رضي الله عنها في الإسلام؟ (What scholarly role of Lady 'A'ishah — may Allah be pleased with her — did the episode highlight?)",
                                correct("كانت في غاية الذكاء والنبوغ والفقه، ونقلت للأمة علماً غزيراً من حياة النبي ﷺ الداخلية وتشريعاته (Possessing extraordinary intellect and juristic depth, she transmitted vast knowledge of Prophetic Sunnah and family law)"),
                                wrong("لم تشتغل بالعلم ولا برواية الحديث قط (She never engaged in scholarship or Hadith transmission)"),
                                wrong("عاشت بعد النبي ﷺ سنة واحدة فقط (She lived only one year after the Prophet ﷺ)"),
                                wrong("لم يأخذ عنها كبار الصحابة والتابعين العلم (Senior Companions never learned jurisprudence from her)")),
                        q("لماذا اختار المسلمون «الهجرة النبوية» لتكون بداية التقويم والتأريخ الإسلامي؟ (Why was the Prophetic Hijrah chosen as the starting point of the Islamic calendar?)",
                                correct("لأن الهجرة هي اللحظة الفاصلة التي قامت بها الدولة الإسلامية والمجتمع المستقل في المدينة (Because the Hijrah marked the decisive birth of the independent Islamic state and community in Medina)"),
                                wrong("لأنهم نسوا عام مولد النبي ﷺ وعام البعثة (Because they forgot the year of his birth and revelation)"),
                                wrong("لأن التقويم الروماني فرض ذلك عليهم (Because the Roman calendar imposed it on them)"),
                                wrong("لأنه لم تقع أي أحداث قبل الهجرة (Because nothing occurred prior to the Hijrah)")),
                        q("من الخليفة الراشد الذي جمع الصحابة وأقرّ اعتماد الهجرة بدايةً للتأريخ الهجري؟ (Which Rightly Guided Caliph consulted the Companions and instituted the Hijri calendar?)",
                                correct("عمر بن الخطاب رضي الله عنه بموافقة الصحابة (Caliph 'Umar ibn al-Khattab with the consensus of the Companions)"),
                                wrong("هارون الرشيد في بغداد (Harun al-Rashid in Baghdad)"),
                                wrong("عبد الرحمن الداخل في قرطبة ('Abd al-Rahman I in Cordoba)"),
                                wrong("صلاح الدين الأيوبي في دمشق (Salah al-Din in Damascus)")),
                        q("كم عاماً عاش النبي ﷺ في ريعان شبابه مكتفياً بزوجة واحدة هي السيدة خديجة رضي الله عنها (وكانت أكبر منه سناً)؟ (How long did the Prophet ﷺ live in his prime married solely to Khadijah, who was older than him?)",
                                correct("نحو خمسة وعشرين عاماً حتى توفيت رضي الله عنها وهو في الخمسين من عمره (Around 25 years—from age 25 until her passing when he was 50)"),
                                wrong("عامين اثنين فقط (Only two years)"),
                                wrong("ستة أشهر في المدينة المنورة (Six months in Medina)"),
                                wrong("لم يتزوج في مكة إطلاقاً (He never married in Mecca at all)"))
                )
        ));

        // EPISODE 13 — G5ySBASZFy4
        list.add(new EpisodeSeed(
                13,
                "هل يليق بنبيّ الرحمة أن يقاتل لنشر الإسلام؟ | 100 سؤال في التاريخ | الحلقة 13",
                "G5ySBASZFy4",
                2920,
                List.of(
                        vocab("نَبِيُّ الرَّحْمَة", "Nabiyy ar-Rahmah", "Prophet of Mercy", "Sent as a mercy to all worlds—fighting aggression to protect freedom of faith."),
                        vocab("اعْتِنَاق الدِّين", "I'tinaq ad-Din", "Embracing the Faith", "Entering Islam by heartfelt conviction—never by compulsion."),
                        vocab("تَوَسُّع الدَّوْلَة", "Tawassu' ad-Dawlah", "State Expansion", "Removing tyrannical regimes blocking peoples from hearing the message freely."),
                        vocab("العَهْد المَكِّي", "Al-'Ahd al-Makki", "The Meccan Period", "Thirteen years in Mecca enduring persecution without taking up arms."),
                        vocab("حُرِّيَّة الِاعْتِقَاد", "Hurriyyat al-I'tiqad", "Freedom of Belief", "Guaranteed by ﴿لَا إِكْرَاهَ فِي الدِّينِ﴾—no peoples were forced to convert.")
                ),
                List.of(
                        q("ما التفريق الجوهري الذي قررته الحلقة الثالثة عشرة للرد على شبهة «انتشار الإسلام بالسيف»؟ (What crucial distinction did Episode 13 establish to refute 'Islam spread by the sword'?)",
                                correct("الإسلام كدين لم ينتشر بالسيف ولم تُجبر الشعوب على اعتناقه، أما الدولة فتوسعت عسكرياً لإزالة الطغيان كما تتوسع الدول في التاريخ (Islam as a faith never spread by coercion, whereas the Islamic state expanded geopolitically while leaving peoples free in their religion)"),
                                wrong("أن المسلمين أجبروا كل أهل مصر والشام على الإسلام في يوم واحد (That Muslims forced everyone in Egypt and Syria to convert in one day)"),
                                wrong("أنه لم تقع أي معركة في عصر الدولة الإسلامية إطلاقاً (That zero battles ever occurred in Islamic state history)"),
                                wrong("أن إندونيسيا وماليزيا دخلتا الإسلام عبر الجيوش العسكرية (That Indonesia and Malaysia converted via military invasion)")),
                        q("كيف كان موقف النبي ﷺ وأصحابه من استخدام السلاح طوال العهد المكي (13 سنة)؟ (What was the Prophet's ﷺ stance on using weapons throughout the 13-year Meccan period?)",
                                correct("لم يستخدم النبي ﷺ السلاح والقوة مطلقاً طوال وجوده في مكة رغم الاضطهاد الشديد (He never used weapons or force in Mecca despite severe persecution)"),
                                wrong("شنّ حروباً عسكرية داخل مكة منذ السنة الأولى (Waged armed battles inside Mecca from year one)"),
                                wrong("استعان بجيوش الروم لقتال قريش (Hired Roman armies to fight Quraysh)"),
                                wrong("غادر مكة في اليوم الأول من البعثة (Left Mecca on the very first day of revelation)")),
                        q("ما الدليل التاريخي الواقعي على أن الشعوب المفتوحة لم تُكره على ترك أديانها؟ (What concrete historical fact proves peoples in opened lands were not forced to abandon their religions?)",
                                correct("بقاء الطوائف غير المسلمة وكنائسها ومعابدها آمنة قروناً طويلة في قلب العالم الإسلامي (Non-Muslim communities and their churches endured safely for fourteen centuries in the heart of Muslim lands)"),
                                wrong("اختفاء كل غير المسلمين من الشرق الأوسط في القرن الأول الهجري (All non-Muslims vanished from the Middle East in the 1st century AH)"),
                                wrong("عدم دخول المسلمين إلى أي مدينة خارج الجزيرة العربية (Muslims never entered any city outside Arabia)"),
                                wrong("إلغاء العقود والعهود مع أهل الذمة (Abolishing all covenants of protection)")),
                        q("كيف دخل الإسلام إلى أكبر تجمع سكاني للمسلمين اليوم في جنوب شرق آسيا (إندونيسيا وماليزيا)؟ (How did Islam reach Southeast Asia, home to the largest Muslim population today?)",
                                correct("عبر أخلاق التجار المسلمين والدعوة السلمية القدوة دون أن يطأها جيش واحد (Through the integrity of Muslim merchants and peaceful preaching without a single army)"),
                                wrong("عبر حملة عسكرية بحرية كبرى في العصر الأموي (Through a massive Umayyad naval invasion)"),
                                wrong("بقرار من الاستعمار البرتغالي والهولندي (By decree of Portuguese and Dutch colonists)"),
                                wrong("في أواخر القرن العشرين فقط (Only in the late 20th century)")),
                        q("لماذا شُرع الجهاد الدفاعي والوقائي بعد قيام الدولة في المدينة المنورة؟ (Why was defense and armed struggle legislated after the state formed in Medina?)",
                                correct("لردّ العدوان وحماية المستضعفين وكسر استبداد الإمبراطوريات التي تمنع الناس من سماع الحق بحرية (To repel aggression, protect the oppressed, and remove tyrannical barriers blocking free choice)"),
                                wrong("لنهب ثروات الشعوب واستعبادها (To plunder wealth and enslave nations)"),
                                wrong("لإكراه الناس على النطق بالشهادتين تحت التهديد (To force people to pronounce the Shahadah at sword-point)"),
                                wrong("للتوسع الجغرافي بلا هدف أخلاقي أو رسالي (For aimless expansion devoid of moral purpose)"))
                )
        ));

        // EPISODE 14 — rberOveHrUM
        list.add(new EpisodeSeed(
                14,
                "هل السيرة النبوية سيرة غزوات وحروب فقط؟ | 100 سؤال في التاريخ | الحلقة 14",
                "rberOveHrUM",
                3116,
                List.of(
                        vocab("المَغَازِي", "Al-Maghazi", "Expeditions / Classical Sirah Title", "Early term used for Prophetic political & biographical chronicles."),
                        vocab("العُمْرَان والنُّظُم", "Al-'Umran wan-Nuzum", "Civic Building & Institutions", "Administrative, educational, economic, and social systems established in Medina."),
                        vocab("مَرَاحِل السِّيرَة", "Marahil as-Sirah", "Stages of the Sirah", "Structured into three clear phases to facilitate holistic understanding."),
                        vocab("التَّبَحُّر والتَّخَصُّص", "At-Tabahhur wat-Takhassus", "Deep Mastery & Specialization", "Graduated levels of Sirah reading: introductory, specialized, and encyclopedic."),
                        vocab("المُؤَاخَاة والوَثِيقَة", "Al-Mu'akhah wal-Wathiqah", "Brotherhood Pact & Medina Charter", "Landmarks of civilizational and constitutional institution-building in the Sirah.")
                ),
                List.of(
                        q("لماذا أطلق كثير من العلماء المتقدمين اسم «المغازي» على كتب السيرة النبوية؟ (Why did early Muslim scholars often title books of Prophetic biography 'Al-Maghazi'?)",
                                correct("لأنها رصدت التاريخ السياسي والتحولات الكبرى، بينما صُنفت الجوانب الحضارية والنظم والأخلاق في كتب السنة والشمائل والأحكام (Because Maghazi tracked major political turning points, while civic, legal, and moral institutions were richly compiled across Hadith, Shama'il, and Fiqh works)"),
                                wrong("لأن حياة النبي ﷺ لم يكن فيها تعليم ولا عمران ولا قضاء ولا دستور (Because the Prophet's ﷺ life had zero education, governance, or constitution)"),
                                wrong("لأنهم كانوا يقاتلون طوال ساعات السنة بلا توقف (Because battles raged nonstop every day of the year)"),
                                wrong("لأن كلمة المغازي تعني التجارة والزراعة في اللغة (Because Maghazi literally means agriculture)")),
                        q("إلى كم مرحلة كبرى قسّمت الحلقة الرابعة عشرة السيرة النبوية ليسهل استيعابها؟ (Into how many overarching stages did Episode 14 divide the Prophetic Sirah for clear comprehension?)",
                                correct("ثلاث مراحل رئيسية متكاملة (Three main interconnected stages)"),
                                wrong("عشرين مرحلة منفصلة (Twenty disconnected stages)"),
                                wrong("مرحلة واحدة فقط بلا تدرج (One single stage without progression)"),
                                wrong("عشر مراحل بعد وفاة النبي ﷺ (Ten stages after the Prophet's ﷺ passing)")),
                        q("ما أبرز الشواهد الحضارية والعمرانية في سيرة النبي ﷺ بالمدينة المنورة؟ (What are prominent examples of civilizational and institutional building in Medina?)",
                                correct("بناء المسجد جامعةً، والمؤاخاة، ووثيقة المدينة الدستورية، وتنظيم السوق والقضاء والتعليم (Building the Mosque as a learning center, the Brotherhood pact, the Charter of Medina, ethical market regulation, and literacy)"),
                                wrong("بناء القصور الملكية الفاخرة والأسوار العازلة (Constructing royal palaces and segregating walls)"),
                                wrong("إغلاق الأسواق ومنع التجارة والزراعة (Shutting down markets and banning agriculture)"),
                                wrong("إلغاء الكتابة والقراءة بين السكان (Banning reading and writing among residents)")),
                        q("كيف وجّه الأستاذ محمد إلهامي القارئ لاختيار كتب ومراجع دراسة السيرة النبوية؟ (How did Muhammad Elhami guide learners in choosing books to study the Sirah?)",
                                correct("التدرج عبر ثلاثة مستويات: كتب للقراءة الأولية، ثم كتب للتخصص، ثم موسوعات للتبحر والتوسع (Progressing through three tiers: introductory reading, specialized study, and comprehensive encyclopedic mastery)"),
                                wrong("البدء بأضخم الموسوعات ذات العشرين مجلداً للمبتدئ مباشرة (Starting beginners directly on 20-volume encyclopedias)"),
                                wrong("الاكتفاء بمقاطع الفيديو القصيرة وترك الكتب (Watching short clips only and abandoning books)"),
                                wrong("قراءة كتاب واحد ومنع قراءة ما سواه (Reading only one book and banning all others)")),
                        q("ما معنى كلمة «التَّبَحُّر» في العلم كما وردت في محاور الحلقة؟ (What does the Arabic term 'At-Tabahhur' mean regarding scholarship?)",
                                correct("التوسع العميق والتمكن الواسع في العلم كالبحر في سعته (Deep, expansive mastery in a field of knowledge, vast like the sea)"),
                                wrong("ركوب السفن لصيد الأسماك فقط (Sailing boats solely to catch fish)"),
                                wrong("القراءة السطحية السريعة بلا فهم (Superficial speed-reading without comprehension)"),
                                wrong("نسيان المعلومات بعد الامتحان (Forgetting material right after an exam)"))
                )
        ));

        // EPISODE 15 — BDgk-IRFi3c
        list.add(new EpisodeSeed(
                15,
                "كيف نلخص فترة الخلافة الراشدة؟ وما هي أهميتها التاريخية؟ | 100 سؤال في التاريخ | الحلقة 15",
                "BDgk-IRFi3c",
                3567,
                List.of(
                        vocab("الخِلَافَة الرَّاشِدَة", "Al-Khilafah ar-Rashidah", "The Rightly Guided Caliphate", "30-year model of prophetic governance after the Prophet's ﷺ passing."),
                        vocab("الفِقْه السِّيَاسِي", "Al-Fiqh as-Siyasi", "Islamic Political Jurisprudence", "Constitutional and governance principles learned directly from the Rightly Guided Caliphs."),
                        vocab("الشُّورَى واخْتِيَار الخَلِيفَة", "Ash-Shura wakhtiyar al-Khalifah", "Consultation & Choosing the Leader", "Institutionalized famously by 'Umar ibn al-Khattab through the six-member council."),
                        vocab("كَرَامَة الإِنْسَان", "Karamat al-Insan", "Human Dignity", "Guaranteed security where every person obtained their right without oppression."),
                        vocab("القُدْوَة والنَّمُوذَج", "Al-Qudwah wan-Namudhaj", "The Exemplar & Benchmark", "Enduring reference model for righteous Muslim governance without prophetic revelation.")
                ),
                List.of(
                        q("ما الأهمية التاريخية والدينية لفترة «الخلافة الراشدة» كما لخصتها الحلقة الخامسة عشرة؟ (What is the historical and religious significance of the Rightly Guided Caliphate according to Episode 15?)",
                                correct("هي أعدل وأزهر فترات التاريخ ونموذج تطبيقي قدوة لحكم المسلمين بعد النبي ﷺ دون وحي جديد (It is the most just and radiant period in history and the enduring benchmark for Muslim governance after the Prophet ﷺ)"),
                                wrong("هي فترة هامشية لا علاقة لها بالفقه السياسي (It is a marginal period unrelated to political jurisprudence)"),
                                wrong("كانت حكماً وراثياً ملكياً مستبداً منذ يومها الأول (It was an absolute hereditary monarchy from day one)"),
                                wrong("استمرت خمسمائة عام في مدينة بغداد (It lasted five hundred years in Baghdad)")),
                        q("من أين استقت الأمة الإسلامية أصول «الفقه السياسي» ونظام الحكم الشرعي؟ (From whom did the Muslim Ummah derive the foundational principles of its political jurisprudence?)",
                                correct("من ممارسات واجتهادات وإجماع الخلفاء الراشدين والصحابة رضي الله عنهم (From the precedents, consultations, and consensus of the Rightly Guided Caliphs and Companions)"),
                                wrong("من دساتير الإمبراطورية الرومانية والبيزنطية (From Roman and Byzantine imperial constitutions)"),
                                wrong("من الفلسفة السياسية اليونانية وحدها (From Greek political philosophy alone)"),
                                wrong("لم يعرف المسلمون أي فقه سياسي في تاريخهم (Muslims never developed political jurisprudence)")),
                        q("ما الدور المؤسسي الذي أبرزه المحاضر للخليفة عمر بن الخطاب رضي الله عنه في نظام الحكم؟ (What institutional contribution of Caliph 'Umar ibn al-Khattab did the speaker highlight?)",
                                correct("تأسيس نظام الشورى لاختيار الخليفة وتنظيم الدواوين والمحاسبة القضائية للولاة (Institutionalizing consultative selection of the Caliph, public registries, and holding governors accountable)"),
                                wrong("توريث الخلافة لابنه عبد الله بن عمر من بعده (Bequeathing the Caliphate hereditarily to his son 'Abdullah)"),
                                wrong("إلغاء القضاء وبيت المال (Abolishing the judiciary and public treasury)"),
                                wrong("منع الرعية من سؤال الحاكم أو محاسبته (Forbidding citizens from questioning or auditing rulers)")),
                        q("كيف عاش الإنسان (مسلماً وغير مسلم) في ظل دولة الخلافة الراشدة بحسب خلاصة الحلقة؟ (How did people live under the Rightly Guided Caliphate according to the episode summary?)",
                                correct("عاش بأمنٍ يأخذ حقه ويشعر بكرامته لا يُضطهد ولا يُظلم، ويقاضي حتى الخليفة أمام القاضي (In security and human dignity, receiving full rights free from oppression—even able to summon the Caliph before an independent judge)"),
                                wrong("في خوف دائم ومصادرة للأموال والحقوق (In constant fear and confiscation of property)"),
                                wrong("في طبقية تمنع الفقراء من دخول المحاكم (Under a caste system barring the poor from courts)"),
                                wrong("تحت حكم العسكر بلا قضاء مستقل (Under martial rule without independent judges)")),
                        q("لماذا تُعد الخلافة الراشدة حجةً عملية خالدة في التاريخ الإسلامي؟ (Why does the Rightly Guided Caliphate serve as a lasting practical proof in Islamic history?)",
                                correct("لأنها أثبتت واقعياً إمكانية إقامة العدل النبوي والشورى بأيدي بشر غير أنبياء بعد انقطاع الوحي (Because it proved in real history that non-prophet human leaders can uphold prophetic justice and consultation after revelation ceased)"),
                                wrong("لأن الخلفاء الأربعة كانوا أنبياء يوحى إليهم (Because the four Caliphs were prophets receiving divine revelation)"),
                                wrong("لأنها لم تواجه أي تحديات داخلية أو خارجية (Because it faced zero internal or external challenges)"),
                                wrong("لأن مساحتها اقتصرت على قرية صغيرة واحدة (Because its territory was confined to a single small village)"))
                )
        ));

        return list;
    }

    private List<QuestionData> buildFullTwentyQuestionsForEpisode(int seedIdx, List<EpisodeSeed> allSeeds) {
        EpisodeSeed seed = allSeeds.get(seedIdx);
        List<QuestionData> twenty = new ArrayList<>(20);

        // 1..5: Core episode historical questions
        twenty.addAll(seed.questions);

        // 6..10: Additional episode-specific historical & analytical comprehension questions
        twenty.addAll(buildExtraEpisodeQuestions(seed.episodeNumber));

        // 11..15: Arabic -> English Study Lexicon comprehension questions (5 unique episode terms)
        int totalSeeds = allSeeds.size();
        for (int vIdx = 0; vIdx < seed.vocab.size(); vIdx++) {
            Map<String, String> v = seed.vocab.get(vIdx);
            Map<String, String> d1 = allSeeds.get((seedIdx + 3) % totalSeeds).vocab.get((vIdx + 1) % 5);
            Map<String, String> d2 = allSeeds.get((seedIdx + 7) % totalSeeds).vocab.get((vIdx + 2) % 5);
            Map<String, String> d3 = allSeeds.get((seedIdx + 11) % totalSeeds).vocab.get((vIdx + 3) % 5);

            twenty.add(q(
                    "ما المعنى الدقيق للمصطلح العربي «" + v.get("arabic") + "» (" + v.get("translit")
                            + ") الوارد في مفردات الحلقة " + seed.episodeNumber + "؟ (What is the accurate meaning of '"
                            + v.get("translit") + "' from this episode's vocabulary guide?)",
                    correct(v.get("english") + " — " + v.get("note")),
                    wrong(d1.get("english") + " — " + d1.get("note")),
                    wrong(d2.get("english") + " — " + d2.get("note")),
                    wrong(d3.get("english") + " — " + d3.get("note"))
            ));
        }

        // 16..20: Concept/Definition -> Vocalized Arabic term questions (5 unique episode terms)
        for (int vIdx = 0; vIdx < seed.vocab.size(); vIdx++) {
            Map<String, String> v = seed.vocab.get(vIdx);
            Map<String, String> d1 = allSeeds.get((seedIdx + 4) % totalSeeds).vocab.get((vIdx + 2) % 5);
            Map<String, String> d2 = allSeeds.get((seedIdx + 8) % totalSeeds).vocab.get((vIdx + 4) % 5);
            Map<String, String> d3 = allSeeds.get((seedIdx + 12) % totalSeeds).vocab.get((vIdx + 1) % 5);

            twenty.add(q(
                    "أي مصطلح عربي في دليل مفردات الحلقة " + seed.episodeNumber + " يدل على هذا المفهوم: «"
                            + v.get("english") + ": " + v.get("note") + "»؟ (Which Arabic term from Episode "
                            + seed.episodeNumber + " expresses this concept?)",
                    correct(v.get("arabic") + " (" + v.get("translit") + ")"),
                    wrong(d1.get("arabic") + " (" + d1.get("translit") + ")"),
                    wrong(d2.get("arabic") + " (" + d2.get("translit") + ")"),
                    wrong(d3.get("arabic") + " (" + d3.get("translit") + ")")
            ));
        }

        return twenty.subList(0, Math.min(20, twenty.size()));
    }

    private List<QuestionData> buildExtraEpisodeQuestions(int episodeNumber) {
        return switch (episodeNumber) {
            case 1 -> List.of(
                    q("من هو الباحث والمؤرخ مقدم سلسلة «100 سؤال في التاريخ»؟ (Who is the historian presenting the '100 Questions in History' series?)",
                            correct("الأستاذ محمد إلهامي (Ustadh Muhammad Elhami)"),
                            wrong("طه حسين (Taha Hussein)"),
                            wrong("جرجي زيدان (Jurji Zaydan)"),
                            wrong("برنارد لويس (Bernard Lewis)")),
                    q("لماذا لا يلزم من تزوير الإعلام لواقع اليوم ضياع حقائق التاريخ الإسلامي الموثقة؟ (Why doesn't modern media distortion mean Islamic history was lost?)",
                            correct("لأن علماء المسلمين دوّنوا التاريخ بأسانيد مستقلة عن سلطة الحكام وتعددت مصادرهم (Because Muslim scholars recorded history via independent chains outside state monopoly)"),
                            wrong("لأن الإعلام المعاصر لم يبدأ إلا بالأمس (Because modern media started only yesterday)"),
                            wrong("لأن المؤرخين القدماء لم يكتبوا عن السياسة قط (Because early historians never wrote about politics)"),
                            wrong("لأن كتب التاريخ محفوظة باللغات الأوروبية فقط (Because history books survived only in European languages)")),
                    q("ما خطورة الاكتفاء بـ«استشراف المستقبل» مع القعود عن العمل الشرعي والعلمي؟ (What is the danger of obsessing over predicting the future while neglecting practical work?)",
                            correct("يصرف المسلم عن واجب السعي والبناء الذي سيُسأل عنه أمام الله (It distracts a Muslim from the duty of striving and building for which they are accountable)"),
                            wrong("يؤدي إلى إتقان اللغة العربية بسرعة مفرطة (It causes people to master Arabic too fast)"),
                            wrong("يزيد من عدد المكتبات العامة في المدن (It increases public libraries in cities)"),
                            wrong("يجعل الإنسان يحفظ القرآن في أسبوع واحد (It makes one memorize the Quran in one week)")),
                    q("كيف نظر الإسلام إلى تعدد الشعوب والأوطان ضمن رابطة الأمة الواحدة؟ (How does Islam view diverse peoples within the single Ummah?)",
                            correct("التنوع للتعارف والتكامل تحت مظلة الأخوة الإسلامية الواحدة لا للتفرق القطري الضيق (Diversity serves mutual acquaintance and unity under Islamic brotherhood, not divisive nationalism)"),
                            wrong("تقديس الحدود الاستعمارية فوق رابطة العقيدة (Placing colonial borders above the bond of faith)"),
                            wrong("منع السفر والتجارة بين الأقاليم الإسلامية (Forbidding travel and trade across Muslim regions)"),
                            wrong("إلغاء أسماء القبائل والمدن من اللغة (Erasing city and tribal names from language)")),
                    q("ما الثمرة الأولى لدراسة التاريخ بوعي سليم كما افتتحت بها الحلقة الأولى؟ (What primary fruit comes from studying history with sound awareness in Episode 1?)",
                            correct("تحصين الهوية وفهم السنن الإلهية في نهوض الأمم وسقوطها للعمل على بصيرة (Safeguarding identity and grasping divine laws of civilizational rise and fall to act with insight)"),
                            wrong("جمع الحكايات الغريبة للتسلية الليلية فقط (Collecting strange tales solely for bedtime amusement)"),
                            wrong("الهروب من مسؤوليات الحاضر إلى الماضي (Escaping present responsibilities into the past)"),
                            wrong("حفظ تواريخ الوفيات دون فهم الأحداث (Memorizing death dates without understanding events)"))
            );
            case 2 -> List.of(
                    q("لماذا ينكشف تزوير المنتصر الظالم للتاريخ بعد زوال دولته وسطوته؟ (Why does a tyrant victor's historical distortion unravel once their rule ends?)",
                            correct("لبقاء الشهادات المستقلة ووثائق المعاصرين وآثار الواقع التي تفضح الكذب (Because independent testimonies, contemporary records, and material realities survive to expose falsehood)"),
                            wrong("لأن الكتب تتحول تلقائياً إلى رماد بعد مائة عام (Because books turn to ash after 100 years)"),
                            wrong("لأن اللغات القديمة تختفي كل جيل (Because languages vanish every generation)"),
                            wrong("لأن المنتصر يكتب اعترافاته دائماً في الصفحة الأولى (Because victors always confess on page one)")),
                    q("ما الدافع السياسي والاستعماري الذي صاحب كثيراً من الدراسات الاستشراقية؟ (What political motive accompanied much of Western Orientalist scholarship?)",
                            correct("فهم مجتمعات المسلمين وثقافتهم لتمكين الهيمنة الغربية والتأثير الفكري فيها (Studying Muslim societies and texts to facilitate Western colonial dominance and ideological influence)"),
                            wrong("الرغبة في نشر اللغة العربية لغةً رسمية في أوروبا (Making Arabic the official language of Europe)"),
                            wrong("الدفاع عن الخلافة الإسلامية ضد خصومها (Defending the Islamic Caliphate against its rivals)"),
                            wrong("تعليم الخط العربي الكوفي في المدارس الابتدائية فقط (Teaching Kufic calligraphy in grade schools only)")),
                    q("لماذا لا يؤخذ تفسير الإسلام وعقيدته وفلسفة تاريخه من كتب المستشرقين؟ (Why shouldn't Islamic creed and philosophy of history be taken from Orientalists?)",
                            correct("لأنهم ينطلقون من خلفية فكرية وعقدية مغايرة لا تؤمن بالوحي أصلاً فتسقط تصوراتها على تراثنا (Because they start from a non-believing worldview that denies revelation and projects foreign biases)"),
                            wrong("لأن المستشرقين لم يتعلموا القراءة والكتابة (Because Orientalists could not read or write)"),
                            wrong("لأن كتبهم كُتبت كلها باللغة الصينية القديمة (Because all their books were written in ancient Chinese)"),
                            wrong("لأنهم لم يزوروا أي مكتبة في حياتهم (Because they never visited any library)")),
                    q("ما أشهر عمل ببليوغرافي ومعجمي للمستشرقين يُستفاد منه تقنياً في فهرسة الحديث؟ (What type of technical Orientalist reference work did Muslim researchers benefit from cautiously?)",
                            correct("المعاجم المفهرسة لألفاظ الحديث وفهارس المخطوطات مع وجوب مراجعتها وتدقيقها (Concordances of Hadith wording and manuscript catalogs, while verifying accuracy)"),
                            wrong("كتب الفتاوى الفقهية المعتمدة في المحاكم الشرعية (Binding Islamic legal fatwa manuals)"),
                            wrong("كتب التجويد والقراءات القرآنية المتواترة (Mutawatir Quranic recitation manuals)"),
                            wrong("متون العقيدة الإسلامية الأصيلة (Foundational texts of Islamic creed)")),
                    q("ما المشروع العلمي المقابل للاستشراق الذي دعا إليه مفكرو الأمة؟ (What scholarly counterpart to Orientalism is needed from Muslim scholars?)",
                            correct("تأسيس علم «استغراب» يدرس الحضارة الغربية بميزان علمي إسلامي مستقل (Developing independent scholarship studying Western civilization critically from an Islamic lens)"),
                            wrong("إغلاق أقسام التاريخ واللغات في الجامعات الإسلامية (Closing history and language departments in Muslim universities)"),
                            wrong("ترجمة كتب المستشرقين دون تعليق أو تمحيص (Translating Orientalist books without critical review)"),
                            wrong("منع دراسة التاريخ الأوروبي الحديث نهائياً (Banning any study of modern European history)"))
            );
            case 3 -> List.of(
                    q("كيف يفضح علم الإسناد والتواريخ الكذّاب إذا ادعى سماع خبر من شيخ لم يدركه؟ (How do Isnad and biographical dates expose a fabricator claiming to hear from a scholar?)",
                            correct("بمراجعة تواريخ الولادة والوفاة والبلدان في كتب التراجم فيتبين انقطاع اللقاء واستحالته (Checking birth/death dates and travels in biographical dictionaries proves they never met)"),
                            wrong("بسؤال المنجمين والعرافين عن صدقه (By consulting astrologers about the narrator)"),
                            wrong("بإجراء قرعة عشوائية بين الروايات (By drawing random lots among reports)"),
                            wrong("بقياس طول الجملة وعدد حروفها فقط (By counting the letters in the sentence)")),
                    q("ما اسم الكتاب التأسيسي الذي وضع فيه ابن خلدون قوانين الاجتماع البشري ونقد الأخبار؟ (What famous work did Ibn Khaldun write laying down laws of social history and source criticism?)",
                            correct("مقدمة ابن خلدون (Muqaddimat Ibn Khaldun)"),
                            wrong("ألف ليلة وليلة (One Thousand and One Nights)"),
                            wrong("كليلة ودمنة (Kalilah wa Dimnah)"),
                            wrong("طوق الحمامة لابن حزم (The Ring of the Dove)")),
                    q("ماذا نعني بكتب «الخِطَط» في التراث التاريخي الإسلامي (كخطط المقريزي)؟ (What do 'Khitat' books — such as Al-Maqrizi's Khitat — document in Islamic historiography?)",
                            correct("التاريخ العمراني والجغرافي للمدن وأحيائها ومساجدها ومدارسها وأسواقها (Detailed topographical, architectural, and institutional histories of cities, quarters, mosques, and schools)"),
                            wrong("الخطط العسكرية السرية في المعارك البحرية فقط (Secret naval battle blueprints only)"),
                            wrong("جداول حساب المثلثات الفلكية (Trigonometric astronomical tables)"),
                            wrong("وصفات تحضير الأدوية العشبية (Herbal medicine recipes)")),
                    q("من الإمام العالم صاحب تشبيه القلب أمام الشبهات بـ«الإسفنجة» أو «الزجاجة المصمتة»؟ (Which renowned scholar authored the metaphor of the sponge versus solid clear glass regarding doubts?)",
                            correct("شيخ الإسلام ابن تيمية في وصيته لتلميذه ابن القيم (Ibn Taymiyyah, recorded by his student Ibn al-Qayyim)"),
                            wrong("الجاحظ في كتاب الحيوان (Al-Jahiz in Kitab al-Hayawan)"),
                            wrong("سيبويه في الكتاب النحوي (Sibawayh in his Grammar)"),
                            wrong("ابن بطوطة في رحلته (Ibn Battuta in his Rihlah)")),
                    q("لماذا يُنصح المبتدئ بالتدرج في القراءة التاريخية بدلاً من هجومه على الشبهات بلا تأصيل؟ (Why must a beginner build foundational historical literacy before wading into polemical doubts?)",
                            correct("لأن الأصول المحكمة تمنحه الميزان العلمي الذي يردّ به المتشابهات والافتراءات بيسر (Because solid foundations provide the scholarly scale to weigh and refute distortions easily)"),
                            wrong("لأن كتب التراث مكتوبة بلغة غير مفهومة للعرب (Because classical books are not written in Arabic)"),
                            wrong("لأن القراءة المتدرجة تستغرق خمسين عاماً قبل قراءة أي كتاب (Because graded reading forbids books for 50 years)"),
                            wrong("لأن التاريخ لا قواعد له ولا منهج (Because history has neither rules nor methodology)"))
            );
            case 4 -> List.of(
                    q("لماذا يقع القارئ المعاصر في وهم كثرة الدماء حين يطالع كتب الحوليات التاريخية؟ (Why do uncritical modern readers misperceive annals as full of conflict?)",
                            correct("لأن المؤرخ يسجل في الحوليات الحدث الاستثنائي الصاخب، أما عقود السلم والتعليم والتجارة فجريانها المعتاد لا يُفرد بالذكر كل سنة (Annals log unusual disruptions, whereas decades of quiet learning, trade, and peace were routine)"),
                            wrong("لأن المسلمين كانوا في حرب أهلية في كل قرية كل يوم (Because every village fought daily without pause)"),
                            wrong("لأنه لم تُبنَ مدرسة ولا مستشفى واحد في الحضارة الإسلامية (Because zero schools or hospitals were ever built)"),
                            wrong("لأن المؤرخين كانوا جنوداً لا يعرفون القلم (Because historians were soldiers unfamiliar with scholarship)")),
                    q("إذا قارنا عدد ضحايا الحروب في التاريخ الإسلامي كله بالحربين العالميتين في القرن العشرين، فماذا يتبين؟ (What emerges when comparing casualties across Islamic history with 20th-century secular world wars?)",
                            correct("أن حروب القرن العشرين العلمانية حصدت في بضع سنوات عشرات الملايين بما يفوق قرون التاريخ القديم مجتمعة (Secular 20th-century wars killed tens of millions in a few years—vastly exceeding centuries of pre-modern conflicts)"),
                            wrong("أن الحرب العالمية الأولى لم يُقتل فيها أحد (That nobody died in World War I)"),
                            wrong("أن الأسلحة النووية استُخدمت في العصر العباسي (That nuclear weapons existed in the Abbasid era)"),
                            wrong("أنه لا فرق في حجم التدمير الشامل للمدنيين (That civilian mass destruction was identical)")),
                    q("ما المؤسسة الوقفية الرائدة التي ابتكرتها الحضارة الإسلامية لرعاية المرضى مجاناً؟ (What endowed institution did Islamic civilization pioneer to provide free medical care and training?)",
                            correct("البيمارستانات (المستشفيات الوقفية التعليمية المجانية للغني والفقير) (Endowed Bimaristans—teaching hospitals treating rich and poor free of charge)"),
                            wrong("المسارح الرومانية للمصارعة (Roman gladiatorial amphitheaters)"),
                            wrong("البنوك الربوية التجارية (Usurious commercial banks)"),
                            wrong("المحاكم التفتيشية السرية (Secret inquisitorial tribunals)")),
                    q("كيف جمعت الحضارة الإسلامية بين «العلوم الشرعية» و«العلوم التطبيقية» دون صراع؟ (How did Islamic civilization harmonize revealed sciences with empirical natural sciences?)",
                            correct("جعل الإسلام النظر في الكون وتسخيره عبادةً لله، فنبغ العلماء في الفقه والطب والفلك معاً دون خصومة بين الدين والعلم (Islam made studying nature an act of worship, so scholars excelled in both revealed and natural sciences without conflict)"),
                            wrong("أحرق العلماء جميع كتب الطب والهندسة (Scholars burned all medical and engineering works)"),
                            wrong("منعت الدولة الأطباء من دخول المساجد (Physicians were barred from mosques)"),
                            wrong("فُصل العلم عن الأخلاق كما في المادية الحديثة (Science was stripped of all moral restraint)")),
                    q("ماذا يعني التحلي بـ«الإنصاف العلمي» عند تقييم أي حقبة تاريخية؟ (What does scholarly fairness require when evaluating any historical era?)",
                            correct("رؤية الصورة الكاملة بنسبها الحقيقية بين السياسة والعمران والعلم والقضاء والأخلاق (Viewing the whole picture in true proportion across governance, justice, science, and society)"),
                            wrong("تضخيم خطأ واحد وإهدار قرون من العدل والعمران (Magnifying one incident to erase centuries of achievement)"),
                            wrong("تزوير النصوص لإرضاء الأهواء المعاصرة (Altering texts to please modern trends)"),
                            wrong("الاكتفاء بقراءة عناوين المقالات السريعة (Reading sensational headlines only)"))
            );
            case 5 -> List.of(
                    q("من العالم المسلم الرائد الذي قلب نظريات الإبصار اليونانية وأسس علم البصريات التجريبي؟ (Which Muslim scientist overturned Greek vision theories and founded experimental optics?)",
                            correct("الحسن بن الهيثم في كتابه «المناظر» (Ibn al-Haytham in Kitab al-Manazir)"),
                            wrong("بطليموس الإسكندري (Ptolemy of Alexandria)"),
                            wrong("أرسطو اليوناني (Aristotle)"),
                            wrong("جاليليو جاليلي (Galileo Galilei)")),
                    q("ما العيب الجوهري في العلم اليوناني القديم الذي صحّحه علماء الحضارة الإسلامية؟ (What fundamental limitation of ancient Greek thought did Muslim scientists correct?)",
                            correct("كان اليونان يعتمدون الجدل الفلسفي النظري، فنقل المسلمون العلم إلى المختبر والتجربة والملاحظة الدقيقة (Greek thought leaned on abstract speculation, whereas Muslim scientists established laboratory experimentation and observation)"),
                            wrong("كان اليونان يفرطون في التجارب المعملية الكيميائية (Greeks relied excessively on chemical laboratories)"),
                            wrong("رفض المسلمون استخدام الأرقام والحساب في العلوم (Muslims rejected mathematics in science)"),
                            wrong("لم يعرف المسلمون علم الفلك والمراصد (Muslims built zero astronomical observatories)")),
                    q("من العالم المسلم الذي وضع علم «الجبر» وحمل اسمه مصطلح الخوارزميات (Algorithms)؟ (Which Muslim mathematician founded Algebra and gave his name to 'Algorithms'?)",
                            correct("محمد بن موسى الخوارزمي (Muhammad ibn Musa al-Khwarizmi)"),
                            wrong("إقليدس اليوناني (Euclid)"),
                            wrong("فيثاغورس (Pythagoras)"),
                            wrong("إسحاق نيوتن (Isaac Newton)")),
                    q("بماذا تفوقت خرائط الجغرافيين والملاحين المسلمين (كالإدريسي وبيري ريس) في عصرهم؟ (How did Muslim geographers and cartographers like Al-Idrisi and Piri Reis excel?)",
                            correct("برسم خرائط عالمية دقيقة للبحار والسواحل اعتمدت على القياس الفلكي والرحلات البحرية الموثقة (Producing remarkably accurate world and coastal maps grounded in astronomical measurement and voyages)"),
                            wrong("باعتقادهم أن الأرض مسطحة محمولة على سلحفاة (Believing the Earth sat on a giant turtle)"),
                            wrong("باقتصارهم على رسم الصحراء العربية دون البحار (Mapping only desert dunes and ignoring oceans)"),
                            wrong("بنقلهم الخرائط عن كولومبوس في القرن العاشر الهجري فقط (Merely copying Columbus without original navigation)")),
                    q("ما الدلالة التاريخية لإعادة العلماء المعاصرين تصنيع آلات الجزري وبني موسى وتقي الدين؟ (What did modern reconstructions of Al-Jazari's and Banu Musa's machines prove?)",
                            correct("أثبتت أنها تصاميم هندسية وميكانيكية دقيقة قابلة للتشغيل الفعلي سبقت عصر النهضة الأوروبية بقرون (They proved those manuscripts contained working precision mechanical engineering centuries ahead of Renaissance Europe)"),
                            wrong("أثبتت أنها زخارف ورقية لا تعمل ميكانيكياً (They showed the drawings were non-functional decorations)"),
                            wrong("أثبتت أنها صُنعت في المصانع الأوروبية الحديثة (They showed Europe built them first)"),
                            wrong("أثبتت خلو التراث الإسلامي من الهندسة التطبيقية (They showed Islam lacked applied engineering)"))
            );
            case 6 -> List.of(
                    q("ما الدليل الواقعي الحي على أن المسلمين لم يمحوا لغات الشعوب المفتوحة بالإكراه؟ (What living linguistic reality proves Muslims never forcibly erased local languages?)",
                            correct("بقاء الفارسية والتركية والكردية والأمازيغية والأوردية وغيرها حية يكتبها أهلها بالحرف العربي ويعتزون بالإسلام (Persian, Turkish, Kurdish, Amazigh, Urdu, and others thrived alongside Arabic for centuries)"),
                            wrong("عدم وجود أي مسلم اليوم يتحدث بغير العربية (Zero Muslims today speak any language besides Arabic)"),
                            wrong("صدور مراسيم أموية وعباسية بإعدام من يتكلم الفارسية (Caliphal decrees executing non-Arabic speakers)"),
                            wrong("تحريم ترجمة معاني القرآن الكريم لغير العرب (Forbidding explaining Quranic meanings to non-Arabs)")),
                    q("لماذا تعربت شعوب الشام ومصر والمغرب طواعية عبر الأجيال؟ (Why did peoples of the Levant, Egypt, and North Africa voluntarily adopt Arabic over generations?)",
                            correct("شغفاً بلغة القرآن الكريم والعلم الشرعي والأدب والإدارة في ظل عدل الإسلام وانفتاحه (Out of devotion to the Quran, scholarship, literature, and shared civic life under Islam)"),
                            wrong("بسبب غرامات مالية فُرضت على كل كلمة قبطية أو سريانية (Due to fines on every Coptic or Syriac word)"),
                            wrong("لأن تلك الشعوب لم تكن لها أي لغة قبل الفتح الإسلامي (Because those lands had no prior language at all)"),
                            wrong("بأمر من الإمبراطورية البيزنطية قبل رحيلها (By order of the departing Byzantine Empire)")),
                    q("بماذا يختلف مفهوم «العصر الذهبي» في الرؤية الإسلامية عنه في الرؤية المادية الغربية؟ (How does the Islamic criterion for a 'Golden Age' differ from purely materialistic metrics?)",
                            correct("يُقاس أولاً بتحقق العدل وكرامة الإنسان واستقامة الإيمان والأخلاق كما في الخلافة الراشدة، لا بمجرد الترف المادي (It is measured first by justice, human dignity, faith, and moral integrity—epitomized by the Rashidun Caliphate—not palace luxury alone)"),
                            wrong("يُقاس بكثرة الذهب والقصور والغناء وحدها (It is measured solely by gold, palaces, and entertainment)"),
                            wrong("يُقاس بعدد الحروب التوسعية دون النظر للعدل (It is measured purely by territorial conquest without justice)"),
                            wrong("يرفض الإسلام الازدهار العلمي والعمراني في كل العصور (Islam rejects scientific and civic prosperity)")),
                    q("كيف جمع كثير من كبار علماء التراث (كسيبويه والطبري والبيروني) بين أصولهم غير العربية وخدمة العربية؟ (How did non-Arab Muslim scholars like Sibawayh, Al-Tabari, and Al-Biruni relate to Arabic?)",
                            correct("أحبوا العربية حبّاً عظيماً لخدمة الوحي فنبغوا فيها وألفوا أمهات كتب النحو والتفسير والعلم بها طواعية (They embraced Arabic wholeheartedly in service of revelation and authored foundational classics of Arabic grammar and science)"),
                            wrong("أُجبروا في السجون على تأليف الكتب بالعربية (They were forced in prison to write in Arabic)"),
                            wrong("كتبوا جميع مؤلفاتهم باللغة اليونانية فقط (They wrote all their works exclusively in Greek)"),
                            wrong("دعوا إلى هجر اللغة العربية وترك تعلمها (They campaigned to abandon learning Arabic)")),
                    q("ما أثر إحياء اللغة العربية الفصحى بين شباب الأمة اليوم كما أبرزت الحلقة السادسة؟ (What impact does reviving fluent Arabic among Muslim youth have today?)",
                            correct("يوحّد الفهم الثقافي ويربط الجيل مباشرةً بالقرآن والسنة وميراث أربعة عشر قرناً من العلم (It unites cultural understanding and connects youth directly to the Quran, Sunnah, and 14 centuries of scholarship)"),
                            wrong("يعزل الشباب عن العلوم الحديثة والتقنية (It isolates youth from modern technology)"),
                            wrong("يمنع التواصل بين المشرق والمغرب (It blocks communication between East and West)"),
                            wrong("لا أثر له في الهوية أو النهوض الحضاري (It has zero effect on identity or revival)"))
            );
            case 7 -> List.of(
                    q("ما الميزان الشرعي والعقلي الذي طبّقه المسلمون عند ترجمة علوم الأمم السابقة؟ (What filter did Muslim scholars apply when translating works from earlier civilizations?)",
                            correct("قَبول العلوم النافعة كالطب والحساب والإدارة وتنقيتها مما يخالف التوحيد والأخلاق (Accepting beneficial sciences like medicine, math, and administration while rejecting pagan superstitions)"),
                            wrong("قبول العقائد الوثنية والخرافات الفلكية دون تمحيص (Adopting pagan myths and superstitions uncritically)"),
                            wrong("رفض الحساب والطب بحجة أنهما من علوم غير المسلمين (Rejecting math and medicine simply because non-Muslims studied them)"),
                            wrong("إحراق جميع الكتب الطبية والزراعية فور فتح المدن (Burning medical and agricultural books upon entering cities)")),
                    q("كيف تعامل عمر بن الخطاب رضي الله عنه مع «تدوين الدواوين» الإدارية المقتبسة تنظيمياً؟ (How did Caliph 'Umar ibn al-Khattab handle administrative registries — Dawawin — adapted for governance?)",
                            correct("اقتبس أداة التنظيم الإداري النافعة لضبط العطاء والجند والخراج بما يخدم العدل الإسلامي (He adopted beneficial administrative record-keeping tools to organize stipends and public finance justly)"),
                            wrong("حرم استخدام الدفاتر والسجلات في الدولة الإسلامية (He banned written registries in the Islamic state)"),
                            wrong("نقل القوانين الطبقية الساسانية الظالمة كما هي (He copied unjust Sasanian caste laws verbatim)"),
                            wrong("سلّم إدارة بيت المال لحكام الفرس القدامى دون رقابة (He handed the treasury over without oversight)")),
                    q("لماذا يحتاج العامل في «الإصلاح السياسي والنهوض» إلى دراسة التاريخ السياسي بعمق؟ (Why does someone working for societal reform especially need deep study of political history?)",
                            correct("ليدرك سنن التدافع وتوازن القوى وأسباب قيام الدول وسقوطها فلا يكرر الأخطاء (To grasp laws of power dynamics, statecraft, and causes of rise and fall without repeating past blunders)"),
                            wrong("ليحفظ أسماء الخيول والسيوف القديمة فقط (Merely to memorize names of ancient swords and horses)"),
                            wrong("ليستغني عن الأخلاق والعدل في السياسة (To abandon ethics and justice in politics)"),
                            wrong("لأن التاريخ الحضاري محرم على المصلحين (Because civilizational history is forbidden to reformers)")),
                    q("ولماذا يحتاج المربي والداعية إلى استحضار «التاريخ الحضاري» في خطابه التثقيفي؟ (Why does an educator and caller need civilizational history in nurturing youth?)",
                            correct("ليغرس الثقة والاعتزاز بنماذج العلم والوقف والرحمة والعمران التي أبدعتها الحضارة الإسلامية (To inspire confidence through Islam's radiant achievements in scholarship, endowments, mercy, and civic life)"),
                            wrong("لإخفاء التاريخ السياسي وإنكار وجود الدول (To deny that Islamic states ever existed)"),
                            wrong("لتحويل الدروس التربوية إلى جدل حزبي عقيم (To turn education into sterile partisan bickering)"),
                            wrong("لتقليد الفن الروماني الوثني في المساجد (To imitate pagan statuary inside mosques)")),
                    q("متى يكون التأثر بالأمم الأخرى علامة صحة ومتى يكون علامة ضعف وتبعية؟ (When is borrowing from other nations healthy versus a sign of weak dependency?)",
                            correct("يكون صحياً حين تنتقي الأمة المستقلة النافع بعقل ناقد، وضعفاً حين تقلد الغالب في هويته وقيمه بلا تمييز (Healthy when an independent nation selectively adapts useful tools; weak when blindly aping a dominant power's values)"),
                            wrong("كل استفادة تقنية من أي أمة تُعد كفراً وتبعية (Any technical borrowing is always slavish dependency)"),
                            wrong("التقليد الأعمى للغالب هو طريق النهوض الوحيد (Blind imitation of the victor is the sole path to revival)"),
                            wrong("الأمم لا تتبادل المنافع المادية في التاريخ أبداً (Nations never exchange practical tools in history)"))
            );
            case 8 -> List.of(
                    q("أي مدينة أندلسية اشتهرت بمدرسة المترجمين التي نقلت مئات الكتب العربية إلى اللاتينية؟ (Which Andalusian city became famous as a translation hub rendering hundreds of Arabic works into Latin?)",
                            correct("طُلَيْطِلَة (Toledo) إلى جانب حواضر قرطبة وإشبيلية (Toledo, alongside centers like Cordoba and Seville)"),
                            wrong("ستوكهولم في السويد (Stockholm in Sweden)"),
                            wrong("دبلن في أيرلندا (Dublin in Ireland)"),
                            wrong("موسكو في روسيا (Moscow in Russia)")),
                    q("كيف أسهم احتكاك الأوروبيين بالمسلمين زمن الحروب الصليبية في الشام ومصر في يقظة أوروبا؟ (How did European contact with the Muslim East during the Crusades impact Europe?)",
                            correct("شاهد الأوروبيون تفوق المسلمين العلمي والطبي والعمراني والأخلاقي فنقلوا كثيراً من العلوم والصناعات (Europeans witnessed superior Muslim medicine, science, ethics, and institutions and carried knowledge back to Europe)"),
                            wrong("علم الأوروبيون المسلمين مبادئ الطب والجبر لأول مرة (Crusaders taught Muslims medicine and algebra for the first time)"),
                            wrong("انقطعت التجارة والمعرفة تماماً لخمسة قرون (All trade and knowledge transfer stopped completely)"),
                            wrong("ترجم المسلمون كتب الطب من اللغة الإنجليزية الحديثة (Muslims translated medical books from modern English)")),
                    q("من الملك النورماني في صقلية الذي قرّب العالم المسلم الشريف الإدريسي لرسم خريطة العالم؟ (Which ruler of Sicily commissioned the Muslim geographer Al-Idrisi to create his famous world map and geography?)",
                            correct("روجر الثاني في صقلية (Roger II of Sicily — commissioning Nuzhat al-Mushtaq)"),
                            wrong("شارلمان ملك الفرنجة في باريس (Charlemagne in Paris)"),
                            wrong("هنري الثامن ملك إنجلترا (Henry VIII of England)"),
                            wrong("إيفان الرهيب في روسيا (Ivan the Terrible)")),
                    q("لماذا اعتبر المحاضر ستينيات القرن العشرين «قاع المنحنى» الذي تلاه بدء مسار الصحوة والنهوض؟ (Why did Episode 8 view the 1960s as the trough followed by a trajectory of Islamic awakening?)",
                            correct("لأنه بعد أقصى تغريب وانكسار عسكري وفكري، عادت الأمة تدريجياً لهويتها وعلمها وحيويتها الشبابية (After peak secularization and defeat, broad grassroots return to Islamic identity, scholarship, and resilience began)"),
                            wrong("لأن العالم الإسلامي اختفى جغرافياً في الستينيات (Because the Muslim world vanished geographically in the 1960s)"),
                            wrong("لأن الخلافة الراشدة تأسست في عام 1965م (Because the Rashidun Caliphate began in 1965)"),
                            wrong("لأن الاستعمار الأوروبي بدأ لأول مرة سنة 1990م (Because European colonialism first began in 1990)")),
                    q("ما الدرس العملي الذي تمنحه معرفة «دورات الازدهار والضعف» لشباب الأمة اليوم؟ (What practical lesson does understanding historical cycles of flourishing and decline give youth today?)",
                            correct("اليقين بأن الضعف طارئ يزول بالأخذ بأسباب العلم والإيمان والعمل الدؤوب دون يأس ولا استعجال (Confidence that decline is reversible through sustained knowledge, faith, and disciplined effort—without despair or haste)"),
                            wrong("الاستسلام لليأس بزعم أن التاريخ انتهى إلى الأبد (Surrendering to despair claiming history has ended)"),
                            wrong("انتظار معجزة خارقة دون بذل أي جهد علمي أو عملي (Waiting passively without scholarly or practical effort)"),
                            wrong("القطيعة التامة مع التاريخ الإسلامي ولغته (Severing all ties with Islamic history and Arabic)"))
            );
            case 9 -> List.of(
                    q("بماذا تمتاز روايات السيرة النبوية عن تواريخ ملوك وأمم العصور القديمة؟ (How do narrations of the Prophetic Sirah stand apart from ancient royal chronicles?)",
                            correct("باتصال الأسانيد ونقد الرجال معرفةً بعدالة كل راوٍ وضبطه ومولده ووفاته (Unbroken chains backed by biographical scrutiny of every narrator's honesty, precision, and dates)"),
                            wrong("بأن مؤلفيها مجهولو الأسماء والأزمان (Their authors are anonymous and undated)"),
                            wrong("بأنها نُقلت شفهياً فقط دون كتابة لألف عام (They remained purely oral without writing for 1,000 years)"),
                            wrong("بأنها خلت من ذكر التواريخ والأماكن والأسماء (They omit dates, places, and names)")),
                    q("كيف تثبت «وثيقة المدينة» والرسائل النبوية للملوك التدوين المبكر في العهد النبوي نفسه؟ (What do the Charter of Medina and the Prophet's ﷺ letters to rulers prove about early documentation?)",
                            correct("تثبت أن الكتابة والتوثيق السياسي والتشريعي باشره النبي ﷺ وكتّابه في حياته الشريفة (They prove official constitutional and diplomatic writing took place directly during the Prophet's ﷺ lifetime)"),
                            wrong("تثبت أن العرب لم يعرفوا القلم إلا في القرن الرابع الهجري (They suggest Arabs learned writing only in the 4th century AH)"),
                            wrong("تثبت أن الرسائل كُتبت باللغة اللاتينية في مكة (They show Meccan letters were written in Latin)"),
                            wrong("تثبت عدم وجود دولة في المدينة المنورة (They show no governance existed in Medina)")),
                    q("لماذا يفشل التفسير المادي والاقتصادي في تعليل دعوة النبي محمد ﷺ وصبر أصحابه؟ (Why do materialistic theories fail to explain the Prophet's ﷺ mission and his Companions' steadfastness?)",
                            correct("لأنه ﷺ عُرض عليه الملك والمال والسيادة بمكة فرفضها، وتوفي ودرعه مرهونة، وبذل أصحابه أموالهم في سبيل الله (He refused Quraysh's offers of wealth and kingship, lived simply until passing away, and Companions sacrificed their fortunes for faith)"),
                            wrong("لأن مكة كانت أغنى إمبراطورية زراعية في العالم (Because Mecca was the world's richest farming empire)"),
                            wrong("لأن الصحابة اشترطوا رواتب ذهبية قبل إسلامهم في مكة (Because early Meccan converts received gold salaries)"),
                            wrong("لأن قريشاً رحبت بالدعوة منذ اليوم الأول بالهدايا (Because Quraysh welcomed the message with gifts on day one)")),
                    q("ما دور القرآن الكريم بوصفه المصدر الأول الأوثق لأحداث السيرة النبوية؟ (Why is the Noble Quran the supreme primary source anchoring key events of the Sirah?)",
                            correct("لأنه وحي متواتر محفوظ بحفظ الله نزل مواكباً لوقائع البعثة والهجرة وبدر وأحد والأحزاب والحديبية والفتح (Transmitted by mass tawatur unchanged since revelation alongside the Hijrah, Badr, Uhud, Ahzab, Hudaybiyyah, and Opening of Mecca)"),
                            wrong("لأن القرآن لا يشير إلى أي غزوة أو حدث في عصر النبوة (Because the Quran never mentions any event of the Prophet's life)"),
                            wrong("لأن القرآن جُمع لأول مرة في العصر الأموي المتأخر (Because the Quran was first compiled centuries later)"),
                            wrong("لأنه يرتب الأحداث حسب التقويم الميلادي الحديث (Because it lists events by Gregorian dates)")),
                    q("ما واجب القارئ عند مصادفة رواية غريبة في كتب الأخبار تخالف الثابت في الصحيحين والقرآن؟ (What should a student do upon encountering an isolated report contradicting authentic Sunnah and Quran?)",
                            correct("يرجع إلى تحقيق علماء الحديث والسيرة لبيان درجتها من الصحة أو الضعف ولا يبني حكماً على رواية واهية (Check Hadith and Sirah scholars' grading rather than building conclusions on weak or fabricated reports)"),
                            wrong("يجعل الرواية الضعيفة أصلاً ويردّ بها الآيات المحكمة (Use the weak tale to override authentic scripture)"),
                            wrong("ينشرها دون تثبت لأن كل مطبوع صحيح (Spread it unverified assuming everything printed is sahih)"),
                            wrong("يترك دراسة السيرة النبوية بالكامل (Abandon studying the Prophetic biography altogether)"))
            );
            case 10 -> List.of(
                    q("ما الشاهد القرآني الصريح على وجود صفة النبي ﷺ ومبشراته لدى أهل الكتاب؟ (What explicit Quranic testimony affirms the Prophet ﷺ was described in the Torah and Gospel?)",
                            correct("قوله تعالى: ﴿الَّذِينَ يَتَّبِعُونَ الرَّسُولَ النَّبِيَّ الْأُمِّيَّ الَّذِي يَجِدُونَهُ مَكْتُوبًا عِندَهُمْ فِي التَّوْرَاةِ وَالْإِنجِيلِ﴾ (Surah Al-A'raf 7:157 affirming they find him written with them in the Torah and Gospel)"),
                            wrong("عدم ورود لفظ التوراة أو الإنجيل في القرآن الكريم (Neither Torah nor Gospel is ever mentioned in the Quran)"),
                            wrong("النهي عن محاورة أهل الكتاب بالحجة والبرهان (A prohibition against presenting proofs to People of the Book)"),
                            wrong("القول بأن موسى وعيسى عليهما السلام بُعثا بعد محمد ﷺ (Claiming Moses and Jesus came after Muhammad ﷺ)")),
                    q("لماذا كانت قبائل اليهود قد استوطنت يثرب (المدينة المنورة) قبل البعثة النبوية؟ (Why had Jewish tribes settled specifically in Yathrib — Medina — prior to the Prophet's ﷺ mission?)",
                            correct("لأنهم كانوا يجدون في كتبهم أن مهاجَر النبي الخاتم أرض ذات نخل بين حرّتين فانتظروا ظهوره (Because their scriptures described the final Prophet's migration land as an oasis of palm groves between two volcanic plains)"),
                            wrong("لأن يثرب كانت عاصمة الإمبراطورية الرومانية (Because Yathrib was the capital of Rome)"),
                            wrong("لاشتغالهم بالملاحة البحرية في المحيط الأطلسي (To sail across the Atlantic Ocean)"),
                            wrong("لعدم وجود أي نخيل أو آبار مياه في يثرب (Because Yathrib had neither palms nor water)")),
                    q("ما قصة الصحابي الجليل سلمان الفارسي رضي الله عنه في دلالتها على البشارات النبوية؟ (How does Salman al-Farisi's journey illustrate the pre-Islamic prophecies of the final Messenger?)",
                            correct("أخبره الرهبان الأحناف بقدوم نبي على دين إبراهيم يهاجر لأرض نخل، وذكروا له علامات النبوة الثلاث فتحققت كلها (Monks foretold a prophet reviving Abraham's faith migrating to palm-groves with three exact signs: accepting gifts, refusing charity, and the seal)"),
                            wrong("التقى سلمان بالنبي ﷺ في بلاد فارس قبل الهجرة (Salman met the Prophet ﷺ inside Persia)"),
                            wrong("لم يسمع سلمان عن النبوة إلا بعد فتح المدائن (Salman never heard of prophethood until Ctesiphon opened)"),
                            wrong("كان سلمان تاجراً من قريش لم يغادر مكة (Salman was a Meccan merchant who never traveled)")),
                    q("كيف تبطل دقة الأخبار الغيبية والتشريعية للنبي الأمي ﷺ دعوى «التأليف البشري الذكي»؟ (Why do fulfilled prophecies and comprehensive law from an unlettered Prophet ﷺ refute 'mere human authorship'?)",
                            correct("لأن الذكاء البشري مهما بلغ لا يحيط بالغيب المستقبل المتحقِّق ولا يضع تشريعاً معجزاً خالياً من التناقض في بيئة أمية (No human intellect can know verified future unseen events or produce inimitable, contradiction-free revelation)"),
                            wrong("لأن العرب في مكة كانوا يمتلكون مراكز أبحاث قانونية دولية (Because Mecca housed international law universities)"),
                            wrong("لأن النبي ﷺ درس في جامعات أثينا والإسكندرية عشرين عاماً (Because he studied 20 years in Athens and Alexandria)"),
                            wrong("لأن الوحي لم يتضمن أي تشريع أسري أو مالي (Because revelation contained no family or financial law)")),
                    q("من هم أبرز علماء الإسلام الذين صنفوا مؤلفات مستقلة في بشارات الكتب السابقة ودلائل النبوة؟ (Which classical Muslim scholars authored famous works on biblical foretellings and proofs of prophethood?)",
                            correct("أئمة كابن قتيبة والبيهقي والقرطبي وابن تيمية وابن القيم ورحمت الله الهندي في «إظهار الحق» (Scholars like Ibn Qutaybah, Al-Bayhaqi, Ibn Taymiyyah, Ibn al-Qayyim, and Rahmatullah al-Kairanawi)"),
                            wrong("شعراء الجاهلية أصحاب المعلقات السبع (Pre-Islamic poets of the Mu'allaqat)"),
                            wrong("فلاسفة اليونان قبل الميلاد (Pre-Christian Greek philosophers)"),
                            wrong("لم يؤلف أي عالم مسلم كتاباً في دلائل النبوة (Zero Muslim scholars wrote on proofs of prophethood)"))
            );
            case 11 -> List.of(
                    q("بماذا أجاب هرقل عظيم الروم أبا سفيان حين علم أن محمداً ﷺ لم يُجرب عليه كذب قط قبل البعثة؟ (What did Byzantine Emperor Heraclius conclude when Abu Sufyan testified Muhammad ﷺ never lied?)",
                            correct("قال هرقل: ما كان ليذر الكذب على الناس ويكذب على الله! فشهد بصدقه عقلاً (Heraclius reasoned: He would never refrain from lying to people only to fabricate lies about God!)"),
                            wrong("زعم هرقل أن الصدق دليل على طلب الملك الدنيوي (Heraclius claimed honesty disproves prophethood)"),
                            wrong("رفض هرقل الاستماع لأسئلة أبي سفيان تماماً (Heraclius refused to ask Abu Sufyan anything)"),
                            wrong("كان أبو سفيان مسلماً مهاجراً حين سأله هرقل (Abu Sufyan was already a Muslim emigrant during that questioning)")),
                    q("ما دلالة مشاركة النبي ﷺ قبل البعثة في «حلف الفضول» ووضع الحجر الأسود بحكمة؟ (What did his pre-revelation participation in Hilf al-Fudul and arbitrating the Black Stone demonstrate?)",
                            correct("تدل على رسوخ مؤهلات العدل ونصرة المظلوم والحكمة القيادية الجامعة للقلوب قبل الوحي (They demonstrated innate justice, defense of the wronged, and unifying leadership wisdom even before revelation)"),
                            wrong("تدل على سعيه لتولي رئاسة تجارة الأصنام (They showed an interest in idol commerce)"),
                            wrong("وقعت هاتان الحادثتان بعد فتح مكة في السنة الثامنة للهجرة (Both occurred after the Year 8 AH Opening of Mecca)"),
                            wrong("شارك فيهما في مدينة الطائف لا في مكة (Both took place in Ta'if rather than Mecca)")),
                    q("لماذا كان كون النبي ﷺ «أمّياً» في بيئة عربية محفوظة النسب والبيان حجةً قاطعة على ربانية القرآن؟ (Why was the Prophet's ﷺ unlettered state in an oral Arab environment decisive proof of the Quran's divine origin?)",
                            correct("لانتفاء أي شبهة تلمذة على كتب الأولين مع تحدي أفصح العرب في أوج بيانهم فعجزوا عن الإتيان بسورة مثله (It ruled out copying earlier books while challenging history's most eloquent Arabs—who could not match even its shortest surah)"),
                            wrong("لأن العرب لم يكونوا يهتمون بالفصاحة والبيان والشعر (Because Arabs cared nothing for eloquence or poetry)"),
                            wrong("لأن الأمية تعني عدم القدرة على النطق بالكلام (Because Ummi meant inability to speak aloud)"),
                            wrong("لأن مكة كانت مليئة بمكتبات الفلسفة الرومانية (Because Mecca was filled with Roman libraries)")),
                    q("ماذا عنى النبي ﷺ بقوله: «ما بعث الله نبياً إلا رعى الغنم»؟ (What universal prophetic pattern did the Hadith 'Allah sent no prophet who did not shepherd sheep' establish?)",
                            correct("أن الله ربّى أنبياءه جميعاً في مدرسة الرعاية العملية للصبر والرفق بالرعية قبل سياسة الأمم (Allah trained every prophet through practical shepherding to nurture patience and gentle care before leading humanity)"),
                            wrong("أن التجارة والزراعة والحدادة محرمة على المؤمنين (That trade, farming, and crafts are forbidden)"),
                            wrong("أن النبوة خاصة بسكان البادية دون المدن والحواضر (That prophets never lived in towns or cities)"),
                            wrong("أن موسى وداود عليهما السلام لم يرعيا الغنم قط (That Moses and David never tended sheep)")),
                    q("لماذا أعرض أبو جهل وأشراف قريش عن الإيمان مع اعترافهم في خلواتهم بصدق النبي ﷺ؟ (Why did Quraysh elites like Abu Jahl reject Islam despite privately acknowledging the Prophet's ﷺ truthfulness?)",
                            correct("بسبب الكبر والتنافس القبلي وخوفهم على زعامتهم ومنافعهم الدنيوية العاجلة (Out of pride, tribal rivalry, and fear of losing their entrenched status and worldly privileges)"),
                            wrong("لأنهم شكوا في نسبه الشريف وأمانته في مكة (Because they doubted his lineage or honesty)"),
                            wrong("لأن القرآن نزل بلغة أعجمية لم يفهموا معانيها (Because the Quran was revealed in a foreign tongue)"),
                            wrong("لأن النبي ﷺ طالبهم بدفع أموال شخصية له (Because he asked them for personal tribute)"))
            );
            case 12 -> List.of(
                    q("كم كان عمر النبي ﷺ حين تزوج السيدة خديجة رضي الله عنها وكم كان عمرها على المشهور؟ (How old was the Prophet ﷺ when marrying Lady Khadijah, who remained his sole wife until her death?)",
                            correct("تزوجها في الخامسة والعشرين من عمره وبقي معها وحدها نحو خمسٍ وعشرين سنة حتى وفاتها وهو في الخمسين (He married her at 25 and remained married exclusively to her for ~25 years until he was 50)"),
                            wrong("تزوجها في الستين من عمره في المدينة المنورة (He married her at age 60 in Medina)"),
                            wrong("تزوج معها عشر نساء في مكة قبل الهجرة (He married ten wives alongside her in Mecca)"),
                            wrong("استمر زواجهما عاماً واحداً فقط قبل البعثة (Their marriage lasted only one single year)")),
                    q("لماذا يُعد إسقاط قوانين وعادات القرن الحادي والعشرين على أعراف الزواج قبل أربعة عشر قرناً مغالطةً تاريخية؟ (Why is judging 7th-century marriage age norms everywhere by 21st-century statutes a historical fallacy?)",
                            correct("لأن سن البلوغ والزواج المبكر كان عرفاً بشرياً عاماً في الشرق والغرب بلا نكير حتى العصر الحديث، ولم يعترض به أعدى خصومه في قريش (Marriage upon maturity was universal across pre-modern East and West; even his fiercest foes in 7th-century Arabia never saw anything unusual in it)"),
                            wrong("لأن أوروبا في العصور الوسطى منعت الزواج قبل سن الثلاثين (Because medieval Europe banned marriage under age 30)"),
                            wrong("لأن قريشاً اعترضت على هذا الزواج في دار الندوة (Because Quraysh objected to the marriage in Mecca)"),
                            wrong("لأن التاريخ لا يدرس اختلاف البيئات والأزمنة (Because historians ignore historical context)")),
                    q("كم حديثاً شريفاً تقريباً روت أم المؤمنين السيدة عائشة رضي الله عنها فكانت من أكثر الصحابة روايةً وفقهاً؟ (Roughly how vast was Lady 'A'ishah's scholarly transmission of Prophetic Hadith and jurisprudence?)",
                            correct("روت أكثر من ألفي حديث شريف وكانت مرجعاً لكبار الصحابة في الفقه والتفسير والطب والشعر (She transmitted over 2,200 Hadiths and served as a juristic authority consulted by senior Companions)"),
                            wrong("روت ثلاثة أحاديث قصيرة فقط طوال حياتها (She narrated only three short Hadiths)"),
                            wrong("لم يكن الصحابة يسألونها في مسائل الفقه والمواريث (Companions never consulted her on inheritance or law)"),
                            wrong("توفيت قبل الهجرة إلى المدينة المنورة (She passed away prior to the Hijrah)")),
                    q("أي زيجات نبوية كريمة كان من آثارها عتق مئات الأسرى وإسلام قبائل بأكملها؟ (How did marriages such as that to Juwayriyah bint al-Harith impact Arabian tribes socially and politically?)",
                            correct("أثمرت مصاهرة القبائل تأليف القلوب وعتق الأسرى كزواجه بجويرية بنت الحارث الذي أسلمت ببركته بنو المصطلق (Marriage alliances healed enmities, freed captives en masse, and brought entire tribes like Banu al-Mustaliq peacefully into Islam)"),
                            wrong("أدت إلى قطيعة القبائل العربية ومنع دخولها في الإسلام (They alienated tribes from Islam)"),
                            wrong("كانت مقتصرة على بنات ملوك الفرس والروم فقط (They involved only Persian and Byzantine princesses)"),
                            wrong("لم يكن لها أي أثر اجتماعي أو تشريعي (They had zero social or legal effect)")),
                    q("ما البدائل التاريخية التي ناقشها الصحابة قبل الاستقرار على «الهجرة» مبدأً للتقويم الإسلامي؟ (What events did the Companions consider before unanimously choosing the Hijrah to start the Islamic calendar?)",
                            correct("ناقشوا المولد والبعثة والهجرة والوفاة، فاختاروا الهجرة لأنها فرّقت بين الحق والباطل وأقامت كيان الأمة المستقل (They weighed the Birth, Revelation, Hijrah, and Passing—choosing the Hijrah as the birth of the independent Muslim community and state)"),
                            wrong("ناقشوا اعتماد سنة بناء الأهرامات أو تأسيس روما (They debated using Rome's founding date)"),
                            wrong("لم يتشاور عمر بن الخطاب مع أي صحابي في الأمر (Caliph 'Umar consulted nobody)"),
                            wrong("اختاروا يوم معركة اليرموك بدايةً للسنة الهجرية (They chose the Battle of Yarmuk as Year 1)"))
            );
            case 13 -> List.of(
                    q("متى أُذن للمسلمين في القتال لأول مرة وبأي تعليل قرآني صريح؟ (When was permission to fight first granted to Muslims, and for what explicit Quranic reason?)",
                            correct("بعد الهجرة للمدينة لرد الظلم وحماية حرية التدين: ﴿أُذِنَ لِلَّذِينَ يُقَاتَلُونَ بِأَنَّهُمْ ظُلِمُوا﴾ ودفع العدوان عن دور العبادة (After the Hijrah, strictly because they were wronged and expelled from their homes unjustly — Surah Al-Hajj 22:39-40)"),
                            wrong("في السنة الأولى من البعثة بمكة المكرمة (In the very first year of revelation in Mecca)"),
                            wrong("لمصادرة أموال القبائل المسالمة المجاورة (To seize wealth from peaceful neighbors)"),
                            wrong("بعد وفاة الخلفاء الراشدين الأربعة فقط (Only after the four Rightly Guided Caliphs)")),
                    q("ما الوصية العسكرية الأخلاقية الشهيرة التي كان يوصي بها النبي ﷺ وأبو بكر الصديق قادة الجيوش؟ (What ethical rules of engagement did the Prophet ﷺ and Abu Bakr command Muslim armies to uphold?)",
                            correct("لا تخونوا ولا تغدروا ولا تمثّلوا ولا تقتلوا طفلاً ولا شيخاً كبيراً ولا امرأة ولا تقطعوا شجراً مثمراً ولا تتعرضوا للرهبان في صوامعهم (Forbidding treachery, mutilation, and harming children, women, elderly, monks in monasteries, or fruitful trees)"),
                            wrong("إباحة إحراق المدن وقتل كل من فيها بلا استثناء (Permitting burning cities and harming civilians)"),
                            wrong("إجبار الرهبان والفلاحين على القتال في الصفوف الأمامية (Forcing monks and farmers into combat)"),
                            wrong("هدم الكنائس والأديرة فور دخول أي مدينة (Demolishing churches upon entering any city)")),
                    q("كم استغرق تحول أغلبية سكان مصر والشام تدريجياً وطواعيةً إلى الإسلام بعد الفتح بحسب الدراسات التاريخية؟ (Over how long did the majority of populations in lands like Egypt and Syria voluntarily embrace Islam after the conquest?)",
                            correct("استغرق قروناً متدرجة من التعايش السلمي والاقناع الفكري دون إكراه، مما يدحض خرافة السيف (It unfolded gradually over centuries of peaceful coexistence, decisively refuting coercion myths)"),
                            wrong("تحول جميع السكان بالإجبار في أسبوع واحد سنة 20 هـ (Everyone was forced to convert in a single week in 20 AH)"),
                            wrong("لم يدخل أحد من أهل مصر أو الشام في الإسلام قط (Nobody in Egypt or Syria ever became Muslim)"),
                            wrong("طرد المسلمون كل السكان الأصليين إلى أوروبا (Muslims expelled native populations to Europe)")),
                    q("ماذا نصّت «العهدة العمرية» لأهل إيلياء (القدس) حين فتحها عمر بن الخطاب رضي الله عنه؟ (What did Caliph 'Umar's Covenant of Jerusalem — Al-'Uhdah al-'Umariyyah — guarantee its Christian inhabitants?)",
                            correct("الأمان لأنفسهم وأموالهم وكنائسهم وصلبانهم، وأنه لا يُكرهون على دينهم ولا يُضارّ أحد منهم (Full security for their lives, property, and churches, strictly guaranteeing none would be coerced in religion)"),
                            wrong("مصادرة جميع الكنائس وتحويلها إلى مخازن عسكرية (Confiscating all churches as military depots)"),
                            wrong("فرض اللغة العربية بالقوة خلال ثلاثة أيام (Enforcing fluent Arabic within three days)"),
                            wrong("منع أهل القدس من ممارسة شعائرهم الدينية (Banning residents from practicing their worship)")),
                    q("كيف يجتمع وصف النبي ﷺ بأنه «رحمة للعالمين» مع قيادته للغزوات الدفاعية العادلة؟ (How does the Prophet's ﷺ title 'Mercy to the Worlds' harmonize with leading defensive expeditions?)",
                            correct("من تمام الرحمة بالبشرية كفُّ يد المعتدين الظالمين وحماية المستضعفين وإقامة العدل، كما يُستأصل الداء حفظاً للجسم (Restraining violent oppressors and protecting the weak is itself essential mercy and justice for humanity)"),
                            wrong("الرحمة تعني ترك الظالمين يقتلون المستضعفين دون دفاع (Mercy means letting aggressors slaughter the defenseless)"),
                            wrong("ألغت آيات القتال وصف الرحمة من الشريعة الإسلامية (Defense abolished mercy in Islamic ethics)"),
                            wrong("لم يشارك النبي ﷺ في أي غزوة بنفسه قط (The Prophet ﷺ never attended any expedition)"))
            );
            case 14 -> List.of(
                    q("ما أول عمل مؤسسي وحضاري بادر إليه النبي ﷺ فور وصوله إلى المدينة المنورة؟ (What were the very first civic institutions the Prophet ﷺ established upon arriving in Medina?)",
                            correct("بناء المسجد مركزاً للعبادة والعلم والشورى، والمؤاخاة بين المهاجرين والأنصار، وكتابة وثيقة المدينة (Building the Mosque as community hub, forging the Muhajirun-Ansar Brotherhood, and drafting the Charter of Medina)"),
                            wrong("تشييد قصر ملكي محاط بالحرس والأسوار (Building a royal palace surrounded by guards)"),
                            wrong("إعلان الحرب على جميع القبائل العربية في يوم واحد (Declaring war on all tribes on day one)"),
                            wrong("إغلاق أسواق المدينة ومنع البيع والشراء (Closing markets and halting commerce)")),
                    q("كم تبلغ نسبة أوقات القتال الفعلي مقارنةً بمجموع سنوات البعثة النبوية الثلاث والعشرين؟ (Roughly what fraction of the 23-year Prophetic mission involved actual battlefield combat?)",
                            correct("أيام معدودة لا تتجاوز جزءاً ضئيلاً جداً من ثلاثٍ وعشرين سنة قُضيت في التربية والتشريع والعمران والدعوة (Only a tiny fraction of days across 23 years devoted overwhelmingly to faith, education, law, ethics, and society-building)"),
                            wrong("تسعون بالمائة من أيام الثلاث والعشرين سنة (90% of every single year for 23 years)"),
                            wrong("ثلاث عشرة سنة متواصلة داخل مكة المكرمة (13 nonstop years inside Mecca)"),
                            wrong("نصف العام في الشتاء ونصفه في الصيف بلا انقطاع (Every winter and summer without interruption)")),
                    q("أين يجد الباحث تفاصيل الحضارة النبوية من قضاء واقتصاد وأسرة وآداب وتكافل اجتماعي؟ (Where in Islamic sources are the rich civic, legal, economic, and ethical details of the Prophetic era preserved?)",
                            correct("في دواوين السنة النبوية وكتب الشمائل والأموال والأحكام الجليلية والآداب والتراجم إلى جانب كتب السيرة (Across the vast Hadith collections, Shama'il, public finance works, jurisprudence, and biographical literature)"),
                            wrong("لا توجد لها أي مصادر مكتوبة في التراث الإسلامي (No written sources exist for them)"),
                            wrong("في كتب الأساطير اليونانية وحدها (Only in Greek mythological texts)"),
                            wrong("ضاعت جميعها ولم يبقَ إلا أسماء السرايا العسكرية (All were lost except lists of battles)")),
                    q("بماذا تميزت «سوق المدينة الإسلامية» التي خطّها النبي ﷺ للمسلمين؟ (What characterized the independent market the Prophet ﷺ established in Medina?)",
                            correct("تحرير الاقتصاد من الاحتكار والربا والغش والضرائب الجائرة مع الرقابة الأخلاقية العادلة (Freeing trade from usury, monopoly, fraud, and unjust market tolls under clear ethical rules)"),
                            wrong("فرض ضرائب باهظة يومية على صغار الباعة (Imposing heavy daily taxes on small vendors)"),
                            wrong("قصر البيع والشراء على الأغنياء وحدهم (Restricting commerce to wealthy elites)"),
                            wrong("السماح بالربا والاحتكار لزيادة الأرباح (Permitting usury and hoarding)")),
                    q("كيف تعامل النبي ﷺ مع الأسرى المتعلمين يوم بدر في دلالته على مكانة العلم؟ (What famous ransom policy after Badr demonstrated the Prophetic priority placed on literacy and education?)",
                            correct("جعل فداء الأسير الكاتب أن يعلّم عشرةً من أبناء المسلمين القراءة والكتابة (Allowing literate captives to earn their freedom by teaching ten Muslim youths to read and write)"),
                            wrong("منع أبناء الصحابة من تعلم الكتابة والخط (Forbidding youth from learning writing)"),
                            wrong("اشترط دفع القناطير من الذهب فقط دون استثناء (Accepting massive gold sums exclusively)"),
                            wrong("أمر بإتلاف جميع الأقلام والصحف المكتوبة (Ordering pens and scrolls destroyed)"))
            );
            default -> List.of(
                    q("كم سنة استمرت فترة «الخلافة الراشدة» وفق الحديث النبوي الشريف والواقع التاريخي؟ (How many years did the Rightly Guided Caliphate span from Abu Bakr through 'Ali and Al-Hasan?)",
                            correct("ثلاثين سنةً (من 11 هـ إلى 40/41 هـ) (Thirty years — from 11 AH to 40/41 AH)"),
                            wrong("خمس سنوات فقط (Only five years)"),
                            wrong("مائتي سنة متصلة في دمشق وبغداد (Two hundred years in Damascus and Baghdad)"),
                            wrong("ثلاثة قرون في الأندلس (Three centuries in Al-Andalus)")),
                    q("ما الترتيب التاريخي الصحيح للخلفاء الراشدين الأربعة رضي الله عنهم؟ (What is the exact chronological order of the four Rightly Guided Caliphs?)",
                            correct("أبو بكر الصديق، ثم عمر بن الخطاب، ثم عثمان بن عفان، ثم علي بن أبي طالب رضي الله عنهم (Abu Bakr, then 'Umar, then 'Uthman, then 'Ali — may Allah be pleased with them)"),
                            wrong("عمر، ثم علي، ثم أبو بكر، ثم عثمان ('Umar, then 'Ali, then Abu Bakr, then 'Uthman)"),
                            wrong("عثمان، ثم أبو بكر، ثم عمر، ثم علي ('Uthman, then Abu Bakr, then 'Umar, then 'Ali)"),
                            wrong("علي، ثم عثمان، ثم عمر، ثم أبو بكر ('Ali, then 'Uthman, then 'Umar, then Abu Bakr)")),
                    q("ما الإنجاز العظيم الذي حُفظ به نص المصحف الشريف في عهد أبي بكر الصديق وعثمان بن عفان رضي الله عنهما؟ (What monumental preservation effort did Abu Bakr and 'Uthman accomplish for the Ummah?)",
                            correct("جمع القرآن الكريم في مصحف إمام واحد متواتر ونسخه وتوزيعه على الأمصار حسماً للاختلاف (Compiling the Quran in a master Mushaf and distributing verified copies across major cities)"),
                            wrong("تأليف كتب المنطق اليوناني بالعربية (Translating Greek logic manuals)"),
                            wrong("بناء الأسطول البحري في المحيط الهندي وحده (Building fleets in the Indian Ocean only)"),
                            wrong("نقل عاصمة الخلافة إلى مدينة قرطبة (Moving the capital to Cordoba)")),
                    q("بماذا افتتح أبو بكر الصديق رضي الله عنه خطبته الدستورية الأولى بعد بيعته بالخلافة؟ (What constitutional principle did Abu Bakr proclaim in his inaugural address as Caliph?)",
                            correct("«إني قد وُلّيت عليكم ولست بخيركم، فإن أحسنت فأعينوني وإن أسأت فقوّموني، والضعيف فيكم قوي عندي حتى آخذ الحق له» (Declaring accountability before the people, inviting correction, and pledging to secure the weak's right from the strong)"),
                            wrong("إعلان عصمة الحاكم ومنع الرعية من النصيحة (Claiming ruler infallibility and forbidding counsel)"),
                            wrong("تخصيص أموال بيت المال لأسرته وقبيلته (Reserving public funds for his own clan)"),
                            wrong("إلغاء الشورى والقضاء المستقل (Abolishing consultation and independent courts)")),
                    q("كيف أثبت القضاء في عهد علي بن أبي طالب وعمر بن الخطاب رضي الله عنهما استقلال العدالة الإسلامية؟ (How did court cases involving Caliph 'Umar and Caliph 'Ali prove judicial independence?)",
                            correct("مثول الخليفة نفسه خصماً متساوياً أمام القاضي مع المواطن المسلم أو الذمي ويُقضى بالبينة العادلة ولو على الخليفة (The Caliph stood as an equal litigant beside an ordinary citizen or non-Muslim dhimmi, bound by the judge's evidence-based ruling)"),
                            wrong("إعفاء الخليفة والولاة من المثول أمام القضاة مطلقاً (Exempting Caliphs and governors from courts)"),
                            wrong("قصر التقاضي على أشراف قريش دون عامة الناس (Restricting courts to Quraishi nobles)"),
                            wrong("الحكم لصالح الحاكم دائماً بلا شهود ولا بينة (Ruling automatically for the ruler without evidence)"))
            );
        };
    }

    private static Map<String, String> vocab(String arabic, String translit, String english, String note) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("arabic", arabic);
        map.put("translit", translit);
        map.put("english", english);
        map.put("note", note);
        return map;
    }

    private static QuestionData q(String text, OptionData... options) {
        return new QuestionData(text, Arrays.asList(options));
    }

    private static OptionData correct(String text) {
        return new OptionData(text, true);
    }

    private static OptionData wrong(String text) {
        return new OptionData(text, false);
    }

    private static class EpisodeSeed {
        final int episodeNumber;
        final String title;
        final String videoId;
        final int durationSeconds;
        final List<Map<String, String>> vocab;
        final List<QuestionData> questions;

        EpisodeSeed(int episodeNumber, String title, String videoId, int durationSeconds,
                    List<Map<String, String>> vocab, List<QuestionData> questions) {
            this.episodeNumber = episodeNumber;
            this.title = title;
            this.videoId = videoId;
            this.durationSeconds = durationSeconds;
            this.vocab = vocab;
            this.questions = questions;
        }
    }

    private static class QuestionData {
        final String text;
        final List<OptionData> options;
        QuestionData(String text, List<OptionData> options) {
            this.text = text;
            this.options = options;
        }
    }

    private static class OptionData {
        final String text;
        final boolean isCorrect;
        OptionData(String text, boolean isCorrect) {
            this.text = text;
            this.isCorrect = isCorrect;
        }
    }
}
