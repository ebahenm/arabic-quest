// Exports and imports course question banks including timestamps and evidence metadata.
package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.QuestionBankBackupDto;
import com.arabicquest.model.*;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.OpenQuestionRepository;
import com.arabicquest.repository.PlaylistRepository;
import com.arabicquest.repository.QuizRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class QuestionBankImportExportService {

    private final PlaylistRepository playlistRepository;
    private final EpisodeRepository episodeRepository;
    private final QuizRepository quizRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final ObjectMapper objectMapper;

    public QuestionBankImportExportService(
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            OpenQuestionRepository openQuestionRepository,
            ObjectMapper objectMapper) {
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public QuestionBankBackupDto exportQuestionBank() {
        QuestionBankBackupDto backup = new QuestionBankBackupDto();
        backup.setSchemaVersion(2);
        backup.setExportedAt(Instant.now());

        List<Playlist> playlists = playlistRepository.findAll();
        playlists.sort(Comparator.comparing(Playlist::getId));

        for (Playlist playlist : playlists) {
            QuestionBankBackupDto.CourseBackup courseDto = new QuestionBankBackupDto.CourseBackup();
            courseDto.setTitle(playlist.getTitle());
            courseDto.setYoutubePlaylistId(playlist.getYoutubePlaylistId());
            courseDto.setDescription(playlist.getDescription());
            courseDto.setCoverImageUrl(playlist.getCoverImageUrl());
            courseDto.setPublished(playlist.isPublished());
            courseDto.setArchived(playlist.isArchived());
            courseDto.setSortOrder(playlist.getSortOrder());
            courseDto.setRateArabicSub(playlist.getArabicRewardRate());
            courseDto.setRateEnglishDub(playlist.getEnglishRewardRate());
            courseDto.setCompletionBonus(playlist.getCompletionBonus());
            courseDto.setMinEpisodesForBonus(playlist.getMinEpisodesForBonus());

            List<Episode> episodes = new ArrayList<>(playlist.getEpisodes());
            episodes.sort(Comparator.comparing(Episode::getEpisodeNumber));

            for (Episode episode : episodes) {
                QuestionBankBackupDto.EpisodeBackup epDto = new QuestionBankBackupDto.EpisodeBackup();
                epDto.setEpisodeNumber(episode.getEpisodeNumber());
                epDto.setTitle(episode.getTitle());
                epDto.setYoutubeVideoId(episode.getYoutubeVideoId());
                epDto.setDurationSeconds(episode.getDurationSeconds());
                epDto.setArchived(episode.isArchived());

                if (episode.getVocabularyJson() != null && !episode.getVocabularyJson().isBlank()) {
                    try {
                        List<Map<String, String>> vocab = objectMapper.readValue(
                                episode.getVocabularyJson(), new TypeReference<List<Map<String, String>>>() {});
                        epDto.setVocabulary(vocab);
                    } catch (Exception ignored) {
                    }
                }

                Quiz quiz = quizRepository.findByEpisodeId(episode.getId()).orElse(null);
                if (quiz != null) {
                    epDto.setPassingScorePercentage(quiz.getPassingScorePercentage());
                    List<Question> questions = new ArrayList<>(quiz.getQuestions());
                    questions.sort(Comparator.comparing(Question::getQuestionOrder));
                    for (Question q : questions) {
                        QuestionBankBackupDto.QuestionBackup qDto = new QuestionBankBackupDto.QuestionBackup();
                        qDto.setQuestionText(q.getQuestionText());
                        qDto.setQuestionOrder(q.getQuestionOrder());
                        qDto.setActive(q.isActive());
                        qDto.setStatus(q.getStatus().name());
                        qDto.setType(q.getType().name());
                        qDto.setEvidence(q.getEvidence());
                        qDto.setEvidenceTimestamp(q.getEvidenceTimestamp());
                        for (QuestionOption opt : q.getOptions()) {
                            qDto.getOptions().add(new QuestionBankBackupDto.OptionBackup(
                                    opt.getOptionText(),
                                    Boolean.TRUE.equals(opt.getIsCorrect())
                            ));
                        }
                        epDto.getQuestions().add(qDto);
                    }
                }

                List<OpenQuestion> opens = openQuestionRepository.findByEpisodeIdOrderByIdAsc(episode.getId());
                for (OpenQuestion oq : opens) {
                    QuestionBankBackupDto.OpenQuestionBackup oqDto = new QuestionBankBackupDto.OpenQuestionBackup();
                    oqDto.setPromptText(oq.getPromptText());
                    oqDto.setEvidence(oq.getEvidence());
                    oqDto.setEvidenceTimestamp(oq.getEvidenceTimestamp());
                    oqDto.setActive(oq.isActive());
                    oqDto.setStatus(oq.getStatus().name());
                    epDto.getOpenQuestions().add(oqDto);
                }

                courseDto.getEpisodes().add(epDto);
            }
            backup.getCourses().add(courseDto);
        }

        return backup;
    }

    @Transactional
    public Map<String, Object> importQuestionBank(QuestionBankBackupDto backup) {
        if (backup == null || backup.getCourses() == null) {
            throw new IllegalArgumentException("Question bank JSON must contain a 'courses' array.");
        }

        int coursesImported = 0;
        int episodesImported = 0;
        int questionsImported = 0;
        int openQuestionsImported = 0;

        for (QuestionBankBackupDto.CourseBackup courseDto : backup.getCourses()) {
            if (courseDto.getYoutubePlaylistId() == null || courseDto.getYoutubePlaylistId().isBlank()) {
                throw new IllegalArgumentException("Each course in the backup must have a non-blank youtubePlaylistId.");
            }
            String ytPlaylistId = courseDto.getYoutubePlaylistId().trim();
            String courseTitle = (courseDto.getTitle() == null || courseDto.getTitle().isBlank())
                    ? ytPlaylistId
                    : courseDto.getTitle().trim();

            Playlist playlist = playlistRepository.findByYoutubePlaylistId(ytPlaylistId)
                    .orElseGet(() -> new Playlist(courseTitle, ytPlaylistId, courseDto.getDescription()));

            playlist.setTitle(courseTitle);
            if (courseDto.getDescription() != null) {
                playlist.setDescription(courseDto.getDescription());
            }
            if (courseDto.getCoverImageUrl() != null) {
                playlist.setCoverImageUrl(courseDto.getCoverImageUrl());
            }
            playlist.setPublished(courseDto.getPublished() != null ? courseDto.getPublished() : true);
            if (courseDto.getArchived() != null) {
                playlist.setArchived(courseDto.getArchived());
            }
            if (courseDto.getSortOrder() != null) {
                playlist.setSortOrder(courseDto.getSortOrder());
            }
            playlist.setArabicRewardRate(courseDto.getRateArabicSub() != null
                    ? courseDto.getRateArabicSub()
                    : BrandConstants.DEFAULT_RATE_ARABIC_SUB);
            playlist.setEnglishRewardRate(courseDto.getRateEnglishDub() != null
                    ? courseDto.getRateEnglishDub()
                    : BrandConstants.DEFAULT_RATE_ENGLISH_DUB);
            playlist.setCompletionBonus(courseDto.getCompletionBonus() != null
                    ? courseDto.getCompletionBonus()
                    : BrandConstants.DEFAULT_COMPLETION_BONUS);
            if (courseDto.getMinEpisodesForBonus() != null && courseDto.getMinEpisodesForBonus() > 0) {
                playlist.setMinEpisodesForBonus(courseDto.getMinEpisodesForBonus());
            }
            playlist = playlistRepository.save(playlist);
            coursesImported++;

            if (courseDto.getEpisodes() == null) {
                continue;
            }

            for (QuestionBankBackupDto.EpisodeBackup epDto : courseDto.getEpisodes()) {
                if (epDto.getEpisodeNumber() == null || epDto.getYoutubeVideoId() == null || epDto.getYoutubeVideoId().isBlank()) {
                    continue;
                }
                final Playlist savedPlaylist = playlist;
                Episode episode = episodeRepository.findByPlaylistIdAndEpisodeNumber(savedPlaylist.getId(), epDto.getEpisodeNumber())
                        .orElseGet(() -> new Episode(
                                savedPlaylist,
                                epDto.getEpisodeNumber(),
                                epDto.getTitle() != null ? epDto.getTitle().trim() : ("Episode " + epDto.getEpisodeNumber()),
                                epDto.getYoutubeVideoId().trim(),
                                epDto.getDurationSeconds() != null ? epDto.getDurationSeconds() : 1800
                        ));

                if (epDto.getTitle() != null && !epDto.getTitle().isBlank()) {
                    episode.setTitle(epDto.getTitle().trim());
                }
                episode.setYoutubeVideoId(epDto.getYoutubeVideoId().trim());
                if (epDto.getDurationSeconds() != null && epDto.getDurationSeconds() > 0) {
                    episode.setDurationSeconds(epDto.getDurationSeconds());
                }
                if (epDto.getArchived() != null) {
                    episode.setArchived(epDto.getArchived());
                }
                if (epDto.getVocabulary() != null && !epDto.getVocabulary().isEmpty()) {
                    try {
                        episode.setVocabularyJson(objectMapper.writeValueAsString(epDto.getVocabulary()));
                    } catch (Exception ignored) {
                    }
                }
                episode = episodeRepository.save(episode);
                episodesImported++;

                if (epDto.getQuestions() != null && !epDto.getQuestions().isEmpty()) {
                    final Episode savedEpisode = episode;
                    Quiz quiz = quizRepository.findByEpisodeId(savedEpisode.getId())
                            .orElseGet(() -> new Quiz(savedEpisode));
                    quiz.setPassingScorePercentage(epDto.getPassingScorePercentage() != null
                            ? epDto.getPassingScorePercentage()
                            : 100.0);

                    Map<String, Question> existingByText = new HashMap<>();
                    for (Question existingQ : quiz.getQuestions()) {
                        if (existingQ.getQuestionText() != null) {
                            existingByText.put(existingQ.getQuestionText().trim(), existingQ);
                        }
                    }

                    int order = quiz.getQuestions().size() + 1;
                    for (QuestionBankBackupDto.QuestionBackup qDto : epDto.getQuestions()) {
                        if (qDto.getQuestionText() == null || qDto.getQuestionText().isBlank()
                                || qDto.getOptions() == null || qDto.getOptions().size() < 2) {
                            continue;
                        }
                        String cleanText = qDto.getQuestionText().trim();
                        Question targetQ = existingByText.get(cleanText);
                        if (targetQ == null) {
                            int qOrder = qDto.getQuestionOrder() != null ? qDto.getQuestionOrder() : order++;
                            targetQ = new Question(quiz, cleanText, qOrder);
                            quiz.getQuestions().add(targetQ);
                            existingByText.put(cleanText, targetQ);
                        } else if (qDto.getQuestionOrder() != null) {
                            targetQ.setQuestionOrder(qDto.getQuestionOrder());
                        }

                        targetQ.setActive(qDto.getActive() == null || qDto.getActive());
                        targetQ.setStatus(parseStatus(qDto.getStatus()));
                        targetQ.setType(parseType(qDto.getType()));
                        targetQ.setEvidence(qDto.getEvidence());
                        targetQ.setEvidenceTimestamp(qDto.getEvidenceTimestamp());
                        targetQ.getOptions().clear();
                        for (QuestionBankBackupDto.OptionBackup optDto : qDto.getOptions()) {
                            if (optDto.getOptionText() == null || optDto.getOptionText().isBlank()) {
                                continue;
                            }
                            targetQ.getOptions().add(new QuestionOption(
                                    targetQ,
                                    optDto.getOptionText().trim(),
                                    Boolean.TRUE.equals(optDto.getIsCorrect())
                            ));
                        }
                        questionsImported++;
                    }
                    quizRepository.save(quiz);
                }

                if (epDto.getOpenQuestions() != null && !epDto.getOpenQuestions().isEmpty()) {
                    Map<String, OpenQuestion> existingOpenByPrompt = new HashMap<>();
                    for (OpenQuestion existingOq : openQuestionRepository.findByEpisodeIdOrderByIdAsc(episode.getId())) {
                        if (existingOq.getPromptText() != null) {
                            existingOpenByPrompt.put(existingOq.getPromptText().trim(), existingOq);
                        }
                    }
                    for (QuestionBankBackupDto.OpenQuestionBackup oqDto : epDto.getOpenQuestions()) {
                        if (oqDto.getPromptText() == null || oqDto.getPromptText().isBlank()) {
                            continue;
                        }
                        String cleanPrompt = oqDto.getPromptText().trim();
                        OpenQuestion targetOq = existingOpenByPrompt.get(cleanPrompt);
                        if (targetOq == null) {
                            targetOq = new OpenQuestion(
                                    episode,
                                    cleanPrompt,
                                    oqDto.getEvidence(),
                                    oqDto.getEvidenceTimestamp(),
                                    parseStatus(oqDto.getStatus())
                            );
                        } else {
                            targetOq.setEvidence(oqDto.getEvidence());
                            targetOq.setEvidenceTimestamp(oqDto.getEvidenceTimestamp());
                            targetOq.setStatus(parseStatus(oqDto.getStatus()));
                        }
                        targetOq.setActive(oqDto.getActive() == null || oqDto.getActive());
                        openQuestionRepository.save(targetOq);
                        existingOpenByPrompt.put(cleanPrompt, targetOq);
                        openQuestionsImported++;
                    }
                }
            }
        }

        return Map.of(
                "coursesImported", coursesImported,
                "episodesImported", episodesImported,
                "questionsImported", questionsImported,
                "openQuestionsImported", openQuestionsImported
        );
    }

    private QuestionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return QuestionStatus.APPROVED;
        }
        try {
            return QuestionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return QuestionStatus.APPROVED;
        }
    }

    private QuestionType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return QuestionType.RECALL;
        }
        try {
            return QuestionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return QuestionType.RECALL;
        }
    }
}
