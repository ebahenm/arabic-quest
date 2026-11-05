package com.arabicquest.config;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(
            UserRepository userRepository,
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            PasswordEncoder passwordEncoder) {
        return args -> {
            // Seed Admin and Student users if empty
            if (userRepository.count() == 0) {
                User admin = new User("admin", passwordEncoder.encode("admin123"), "Admin Account", Role.ROLE_ADMIN);
                User student = new User("brother", passwordEncoder.encode("learn123"), "Brother (Student)", Role.ROLE_STUDENT);
                userRepository.saveAll(Arrays.asList(admin, student));
                System.out.println(">>> Seeded Users: [admin:admin123], [brother:learn123]");
            }

            // Seed YouTube Playlist if empty
            if (playlistRepository.count() == 0) {
                Playlist playlist = new Playlist(
                        "100 سؤال في التاريخ | One Hundred Questions in History",
                        "PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB",
                        "Master Arabic listening comprehension through fascinating historical storytelling by Tareekhuna (محمد إلهامي)."
                );
                playlist.setArabicRewardRate(new BigDecimal("15.00"));
                playlist.setEnglishRewardRate(new BigDecimal("10.00"));
                playlist.setCompletionBonus(new BigDecimal("100.00"));
                playlist = playlistRepository.save(playlist);

                // Episode 1
                Episode ep1 = episodeRepository.save(new Episode(playlist, 1, "الحلقة 1: كيف بدأ تدوين التاريخ الإسلامي؟", "p1qG6FqfJ8w"));
                createQuizForEpisode(quizRepository, ep1, List.of(
                    new QuestionData("ما هي الكلمة العربية التي تعني 'التاريخ'؟", List.of(
                        new OptionData("التاريخ (At-Tarikh)", true),
                        new OptionData("الجغرافيا (Al-Jughrafiya)", false),
                        new OptionData("الفلسفة (Al-Falsafa)", false),
                        new OptionData("الهندسة (Al-Handasa)", false)
                    )),
                    new QuestionData("من هو المؤرخ الشهير صاحب كتاب 'تاريخ الرسل والملوك' الذي ذُكر في الحلقة؟", List.of(
                        new OptionData("الإمام الطبري (Al-Tabari)", true),
                        new OptionData("ابن خلدون (Ibn Khaldun)", false),
                        new OptionData("ابن بطوطة (Ibn Battuta)", false),
                        new OptionData("ابن سينا (Ibn Sina)", false)
                    ))
                ));

                // Episode 2
                Episode ep2 = episodeRepository.save(new Episode(playlist, 2, "الحلقة 2: لماذا ندرس تاريخ الأمم السابقة؟", "XhksJMfDJtv"));
                createQuizForEpisode(quizRepository, ep2, List.of(
                    new QuestionData("ما هو الهدف الأساسي من دراسة التاريخ كما ذكر المحاضر؟", List.of(
                        new OptionData("أخذ العبرة والدروس للمستقبل (Learning lessons for the future)", true),
                        new OptionData("حفظ التواريخ فقط (Only memorizing dates)", false),
                        new OptionData("التسلية وقضاء الوقت (Mere entertainment)", false),
                        new OptionData("نسيان الماضي (Forgetting the past)", false)
                    )),
                    new QuestionData("ما معنى كلمة 'العِبرة' في اللغة العربية؟", List.of(
                        new OptionData("الدرس المستفاد والموعظة (Lesson / Moral wisdom)", true),
                        new OptionData("البكاء الشديد (Heavy crying)", false),
                        new OptionData("السفر البعيد (Distant travel)", false),
                        new OptionData("المال الكثير (Great wealth)", false)
                    ))
                ));

                // Episode 3
                Episode ep3 = episodeRepository.save(new Episode(playlist, 3, "الحلقة 3: منهجية التحقق من الروايات التاريخية", "V_M8Z4WqCjk"));
                createQuizForEpisode(quizRepository, ep3, List.of(
                    new QuestionData("ما هو 'الإسناد' في علم الرواية والتاريخ الإسلامي؟", List.of(
                        new OptionData("سلسلة الرواة الناقلين للخبر (Chain of narrators)", true),
                        new OptionData("عنوان الكتاب (Book title)", false),
                        new OptionData("ثمن المخطوطة (Price of manuscript)", false),
                        new OptionData("اسم المطبعة (Printing press)", false)
                    )),
                    new QuestionData("كيف يتعامل المؤرخ المحقق مع الروايات المتعارضة؟", List.of(
                        new OptionData("مقارنة الأدلة ونقد المتن والسند (Compare evidence & examine source)", true),
                        new OptionData("حذف كل الروايات (Discard everything)", false),
                        new OptionData("قبول أول رواية يسمعها (Accept first one heard)", false),
                        new OptionData("تأليف قصة خيالية (Invent a fiction)", false)
                    ))
                ));

                // Episode 4
                Episode ep4 = episodeRepository.save(new Episode(playlist, 4, "الحلقة 4: حضارة الأندلس ونقل العلوم إلى أوروبا", "7hYh4R9yHfk"));
                createQuizForEpisode(quizRepository, ep4, List.of(
                    new QuestionData("ما هي المدينة الأندلسية التي اشتهرت بمكتباتها العظيمة وجامعتها؟", List.of(
                        new OptionData("قرطبة (Cordoba)", true),
                        new OptionData("باريس (Paris)", false),
                        new OptionData("لندن (London)", false),
                        new OptionData("روما (Rome)", false)
                    )),
                    new QuestionData("ما هو الأثر الثقافي الأبرز للأندلس على القارة الأوروبية؟", List.of(
                        new OptionData("ترجمة العلوم والفلسفة والطب إلى اللاتينية (Translating sciences to Latin)", true),
                        new OptionData("فرض لغة واحدة فقط (Imposing a single language)", false),
                        new OptionData("بناء القلاع العسكرية فقط (Building only military forts)", false),
                        new OptionData("وقف التجارة البحرية (Stopping maritime trade)", false)
                    ))
                ));

                // Episode 5
                Episode ep5 = episodeRepository.save(new Episode(playlist, 5, "الحلقة 5: معالم العمارة والهندسة الإسلامية", "2b9N_R6yFp8"));
                createQuizForEpisode(quizRepository, ep5, List.of(
                    new QuestionData("ما هو العنصر المعماري البارز الذي يميز المساجد في العمارة الإسلامية؟", List.of(
                        new OptionData("المئذنة والقبة والمحراب (Minaret, Dome & Mihrab)", true),
                        new OptionData("الأعمدة الزجاجية العصرية (Modern glass columns)", false),
                        new OptionData("الأبراج الخشبية العالية (High wooden towers)", false),
                        new OptionData("الخنادق المائية المحيطة (Surrounding water moats)", false)
                    ))
                ));

                // Episode 6
                Episode ep6 = episodeRepository.save(new Episode(playlist, 6, "الحلقة 6: حركة الترجمة في بيت الحكمة ببغداد", "gqX9_K6yFn1"));
                createQuizForEpisode(quizRepository, ep6, List.of(
                    new QuestionData("ما اسم الصرح العلمي الشهير الذي أسسه العباسيون للترجمة والبحث؟", List.of(
                        new OptionData("بيت الحكمة (House of Wisdom)", true),
                        new OptionData("دار الأوبرا (Opera House)", false),
                        new OptionData("قصر الحمراء (Alhambra Palace)", false),
                        new OptionData("جامع القرويين (Al-Qarawiyyin)", false)
                    ))
                ));

                // Episode 7
                Episode ep7 = episodeRepository.save(new Episode(playlist, 7, "الحلقة 7: رحلات ابن بطوطة وجغرافيا العالم الإسلامي", "k8Nm_W4yTp2"));
                createQuizForEpisode(quizRepository, ep7, List.of(
                    new QuestionData("ما هو اللقب التاريخي الذي عُرف به ابن بطوطة؟", List.of(
                        new OptionData("أمير الرحالة المسلمين (Prince of Muslim Travelers)", true),
                        new OptionData("شيخ المعماريين (Master Architect)", false),
                        new OptionData("شاعر البلاط (Court Poet)", false),
                        new OptionData("قائد الجيوش (Army Commander)", false)
                    ))
                ));

                // Episode 8
                Episode ep8 = episodeRepository.save(new Episode(playlist, 8, "الحلقة 8: ابن خلدون ونظرية العمران البشري", "v3Rz_T8yLq7"));
                createQuizForEpisode(quizRepository, ep8, List.of(
                    new QuestionData("ما هو الكتاب الشهير لابن خلدون الذي يعتبر مؤسساً لعلم الاجتماع؟", List.of(
                        new OptionData("مقدمة ابن خلدون (The Muqaddimah)", true),
                        new OptionData("القانون في الطب (The Canon of Medicine)", false),
                        new OptionData("كليلة ودمنة (Kalila wa Dimna)", false),
                        new OptionData("ألف ليلة وليلة (One Thousand and One Nights)", false)
                    ))
                ));

                // Episode 9
                Episode ep9 = episodeRepository.save(new Episode(playlist, 9, "الحلقة 9: طرق التجارة القديمة وطريق الحرير", "m5Jx_P2yRq4"));
                createQuizForEpisode(quizRepository, ep9, List.of(
                    new QuestionData("ما هي السلعة الثمينة التي سمي طريق التجارة التاريخي باسمها؟", List.of(
                        new OptionData("الحرير (Silk)", true),
                        new OptionData("الحديد (Iron)", false),
                        new OptionData("الخشب (Wood)", false),
                        new OptionData("الملح (Salt)", false)
                    ))
                ));

                // Episode 10
                Episode ep10 = episodeRepository.save(new Episode(playlist, 10, "الحلقة 10: صناعة الورق وتطور صناعة الكتب والمكتبات", "p9Vw_R3yTn6"));
                createQuizForEpisode(quizRepository, ep10, List.of(
                    new QuestionData("كيف أثر إدخال صناعة الورق على العالم الإسلامي؟", List.of(
                        new OptionData("سهل نسخ الكتب وانتشار المكتبات العامة (Facilitated book copying & public libraries)", true),
                        new OptionData("أدى إلى إغلاق المدارس (Led to closing schools)", false),
                        new OptionData("اقتصر العلم على الأغنياء فقط (Restricted knowledge to wealthy)", false),
                        new OptionData("توقف تدوين العلوم (Stopped documentation)", false)
                    ))
                ));

                // Episode 11
                Episode ep11 = episodeRepository.save(new Episode(playlist, 11, "الحلقة 11: المستشفيات والطب في الحضارة الإسلامية", "q2Bn_L7yXm9"));
                createQuizForEpisode(quizRepository, ep11, List.of(
                    new QuestionData("ما هو الاسم التاريخي الذي أُطلق على المستشفيات في العصر الإسلامي؟", List.of(
                        new OptionData("البيمارستان (Bimaristan)", true),
                        new OptionData("الخان (The Khan)", false),
                        new OptionData("الديوان (The Diwan)", false),
                        new OptionData("الرباط (The Ribat)", false)
                    ))
                ));

                // Episode 12
                Episode ep12 = episodeRepository.save(new Episode(playlist, 12, "الحلقة 12: علم الفلك والتقويم الهجري وتحديد القبلة", "w4Zk_M9yVp1"));
                createQuizForEpisode(quizRepository, ep12, List.of(
                    new QuestionData("ما هي الآلة الفلكية الدقيقة التي طورها العلماء لتحديد المواقيت والقبلة؟", List.of(
                        new OptionData("الأسطرلاب (The Astrolabe)", true),
                        new OptionData("المجهر الإلكتروني (Electron Microscope)", false),
                        new OptionData("الساعة الرملية البسيطة (Simple Hourglass)", false),
                        new OptionData("البوصلة الصينية القديمة فقط (Only old compass)", false)
                    ))
                ));

                System.out.println(">>> Seeded 12 full episodes of '100 سؤال في التاريخ' with quizzes!");
            }
        };
    }

    private void createQuizForEpisode(QuizRepository quizRepository, Episode episode, List<QuestionData> questionsData) {
        Quiz quiz = new Quiz(episode);
        quiz.setPassingScorePercentage(80.0);

        int qOrder = 1;
        for (QuestionData qd : questionsData) {
            Question q = new Question(quiz, qd.text, qOrder++);
            for (OptionData od : qd.options) {
                QuestionOption opt = new QuestionOption(q, od.text, od.isCorrect);
                q.getOptions().add(opt);
            }
            quiz.getQuestions().add(q);
        }
        quizRepository.save(quiz);
    }

    private static class QuestionData {
        String text;
        List<OptionData> options;
        QuestionData(String text, List<OptionData> options) {
            this.text = text;
            this.options = options;
        }
    }

    private static class OptionData {
        String text;
        boolean isCorrect;
        OptionData(String text, boolean isCorrect) {
            this.text = text;
            this.isCorrect = isCorrect;
        }
    }
}
