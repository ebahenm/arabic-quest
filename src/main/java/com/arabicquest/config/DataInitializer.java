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
                Episode ep1 = new Episode(playlist, 1, "الحلقة 1: كيف بدأ تدوين التاريخ الإسلامي؟", "p1qG6FqfJ8w");
                ep1 = episodeRepository.save(ep1);
                createQuizForEpisode(quizRepository, ep1, Arrays.asList(
                    new QuestionData("ما هي الكلمة العربية التي تعني 'التاريخ'؟", Arrays.asList(
                        new OptionData("التاريخ (At-Tarikh)", true),
                        new OptionData("الجغرافيا (Al-Jughrafiya)", false),
                        new OptionData("الفلسفة (Al-Falsafa)", false),
                        new OptionData("الهندسة (Al-Handasa)", false)
                    )),
                    new QuestionData("من هو المؤرخ الشهير صاحب كتاب 'تاريخ الرسل والملوك' الذي ذُكر في الحلقة؟", Arrays.asList(
                        new OptionData("الإمام الطبري (Al-Tabari)", true),
                        new OptionData("ابن خلدون (Ibn Khaldun)", false),
                        new OptionData("ابن بطوطة (Ibn Battuta)", false),
                        new OptionData("ابن سينا (Ibn Sina)", false)
                    ))
                ));

                // Episode 2
                Episode ep2 = new Episode(playlist, 2, "الحلقة 2: لماذا ندرس تاريخ الأمم السابقة؟", "XhksJMfDJtv");
                ep2 = episodeRepository.save(ep2);
                createQuizForEpisode(quizRepository, ep2, Arrays.asList(
                    new QuestionData("ما هو الهدف الأساسي من دراسة التاريخ كما ذكر المحاضر؟", Arrays.asList(
                        new OptionData("أخذ العبرة والدروس للمستقبل (Learning lessons for the future)", true),
                        new OptionData("حفظ التواريخ فقط (Only memorizing dates)", false),
                        new OptionData("التسلية وقضاء الوقت (Mere entertainment)", false),
                        new OptionData("نسيان الماضي (Forgetting the past)", false)
                    )),
                    new QuestionData("ما معنى كلمة 'العِبرة' في اللغة العربية؟", Arrays.asList(
                        new OptionData("الدرس المستفاد والموعظة (Lesson / Moral wisdom)", true),
                        new OptionData("البكاء الشديد (Heavy crying)", false),
                        new OptionData("السفر البعيد (Distant travel)", false),
                        new OptionData("المال الكثير (Great wealth)", false)
                    ))
                ));

                // Episode 3
                Episode ep3 = new Episode(playlist, 3, "الحلقة 3: منهجية التحقق من الروايات التاريخية", "V_M8Z4WqCjk");
                ep3 = episodeRepository.save(ep3);
                createQuizForEpisode(quizRepository, ep3, Arrays.asList(
                    new QuestionData("ما هو 'الإسناد' في علم الرواية والتاريخ الإسلامي؟", Arrays.asList(
                        new OptionData("سلسلة الرواة الناقلين للخبر (Chain of narrators)", true),
                        new OptionData("عنوان الكتاب (Book title)", false),
                        new OptionData("ثمن المخطوطة (Price of manuscript)", false),
                        new OptionData("اسم المطبعة (Printing press)", false)
                    )),
                    new QuestionData("كيف يتعامل المؤرخ المحقق مع الروايات المتعارضة؟", Arrays.asList(
                        new OptionData("مقارنة الأدلة ونقد المتن والسند (Compare evidence & examine source)", true),
                        new OptionData("حذف كل الروايات (Discard everything)", false),
                        new OptionData("قبول أول رواية يسمعها (Accept first one heard)", false),
                        new OptionData("تأليف قصة خيالية (Invent a fiction)", false)
                    ))
                ));

                // Episode 4
                Episode ep4 = new Episode(playlist, 4, "الحلقة 4: حضارة الأندلس ونقل العلوم إلى أوروبا", "7hYh4R9yHfk");
                ep4 = episodeRepository.save(ep4);
                createQuizForEpisode(quizRepository, ep4, Arrays.asList(
                    new QuestionData("ما هي المدينة الأندلسية التي اشتهرت بمكتباتها العظيمة وجامعتها؟", Arrays.asList(
                        new OptionData("قرطبة (Cordoba)", true),
                        new OptionData("باريس (Paris)", false),
                        new OptionData("لندن (London)", false),
                        new OptionData("روما (Rome)", false)
                    ))
                ));

                // Episode 5
                Episode ep5 = new Episode(playlist, 5, "الحلقة 5: معالم العمارة والهندسة الإسلامية", "2b9N_R6yFp8");
                ep5 = episodeRepository.save(ep5);
                createQuizForEpisode(quizRepository, ep5, Arrays.asList(
                    new QuestionData("ما هو العنصر المعماري البارز الذي يميز المساجد في العمارة الإسلامية؟", Arrays.asList(
                        new OptionData("المئذنة والقبة (Minaret and Dome)", true),
                        new OptionData("الأعمدة الرومانية المنحوتة بالأصنام (Roman idol columns)", false),
                        new OptionData("الأبراج الزجاجية (Glass towers)", false),
                        new OptionData("الخنادق المائية (Water moats)", false)
                    ))
                ));

                System.out.println(">>> Seeded 100 Questions in History series with 5 episodes & quizzes!");
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
