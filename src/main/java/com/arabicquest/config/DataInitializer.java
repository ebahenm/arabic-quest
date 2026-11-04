package com.arabicquest.config;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Arrays;

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
                        "Arabic Cartoon Series (Comprehensible Input)",
                        "PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB",
                        "Master Arabic listening comprehension episode by episode."
                );
                playlist.setArabicRewardRate(new BigDecimal("15.00"));
                playlist.setEnglishRewardRate(new BigDecimal("10.00"));
                playlist.setCompletionBonus(new BigDecimal("100.00"));
                playlist = playlistRepository.save(playlist);

                // Episode 1
                Episode ep1 = new Episode(playlist, 1, "Episode 1: The Beginning", "dQw4w9WgXcQ");
                ep1 = episodeRepository.save(ep1);

                // Quiz for Episode 1
                Quiz quiz1 = new Quiz(ep1);
                quiz1.setPassingScorePercentage(80.0);

                Question q1 = new Question(quiz1, "What did the main character say when entering the house?", 1);
                QuestionOption o1 = new QuestionOption(q1, "السلام عليكم (As-salamu alaykum)", true);
                QuestionOption o2 = new QuestionOption(q1, "صباح الخير (Sabah al-khayr)", false);
                QuestionOption o3 = new QuestionOption(q1, "مع السلامة (Ma'a as-salama)", false);
                QuestionOption o4 = new QuestionOption(q1, "شكراً (Shukran)", false);
                q1.setOptions(Arrays.asList(o1, o2, o3, o4));

                Question q2 = new Question(quiz1, "Which word was used for 'Book' in the dialogue?", 2);
                QuestionOption q2o1 = new QuestionOption(q2, "كتاب (Kitab)", true);
                QuestionOption q2o2 = new QuestionOption(q2, "قلم (Qalam)", false);
                QuestionOption q2o3 = new QuestionOption(q2, "باب (Bab)", false);
                QuestionOption q2o4 = new QuestionOption(q2, "سيارة (Sayyara)", false);
                q2.setOptions(Arrays.asList(q2o1, q2o2, q2o3, q2o4));

                quiz1.setQuestions(Arrays.asList(q1, q2));
                quizRepository.save(quiz1);

                System.out.println(">>> Seeded Playlist and Episode 1 with Quiz.");
            }
        };
    }
}
