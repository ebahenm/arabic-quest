package com.arabicquest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QuestionBankBackupDto {

    private int schemaVersion = 2;
    private Instant exportedAt = Instant.now();
    private List<CourseBackup> courses = new ArrayList<>();

    public QuestionBankBackupDto() {
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }

    public List<CourseBackup> getCourses() {
        return courses;
    }

    public void setCourses(List<CourseBackup> courses) {
        this.courses = courses;
    }

    public static class CourseBackup {
        private String title;
        private String youtubePlaylistId;
        private String description;
        private String coverImageUrl;
        private Boolean published;
        private Boolean archived;
        private Integer sortOrder;
        private BigDecimal rateArabicSub;
        private BigDecimal rateEnglishDub;
        private BigDecimal completionBonus;
        private Integer minEpisodesForBonus;
        private List<EpisodeBackup> episodes = new ArrayList<>();

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getYoutubePlaylistId() {
            return youtubePlaylistId;
        }

        public void setYoutubePlaylistId(String youtubePlaylistId) {
            this.youtubePlaylistId = youtubePlaylistId;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCoverImageUrl() {
            return coverImageUrl;
        }

        public void setCoverImageUrl(String coverImageUrl) {
            this.coverImageUrl = coverImageUrl;
        }

        public Boolean getPublished() {
            return published;
        }

        public void setPublished(Boolean published) {
            this.published = published;
        }

        public Boolean getArchived() {
            return archived;
        }

        public void setArchived(Boolean archived) {
            this.archived = archived;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public BigDecimal getRateArabicSub() {
            return rateArabicSub;
        }

        public void setRateArabicSub(BigDecimal rateArabicSub) {
            this.rateArabicSub = rateArabicSub;
        }

        public BigDecimal getRateEnglishDub() {
            return rateEnglishDub;
        }

        public void setRateEnglishDub(BigDecimal rateEnglishDub) {
            this.rateEnglishDub = rateEnglishDub;
        }

        public BigDecimal getCompletionBonus() {
            return completionBonus;
        }

        public void setCompletionBonus(BigDecimal completionBonus) {
            this.completionBonus = completionBonus;
        }

        public Integer getMinEpisodesForBonus() {
            return minEpisodesForBonus;
        }

        public void setMinEpisodesForBonus(Integer minEpisodesForBonus) {
            this.minEpisodesForBonus = minEpisodesForBonus;
        }

        public List<EpisodeBackup> getEpisodes() {
            return episodes;
        }

        public void setEpisodes(List<EpisodeBackup> episodes) {
            this.episodes = episodes;
        }
    }

    public static class EpisodeBackup {
        private Integer episodeNumber;
        private String title;
        private String youtubeVideoId;
        private Integer durationSeconds;
        private Boolean archived;
        private Double passingScorePercentage;
        private List<Map<String, String>> vocabulary = new ArrayList<>();
        private List<QuestionBackup> questions = new ArrayList<>();
        private List<OpenQuestionBackup> openQuestions = new ArrayList<>();

        public Integer getEpisodeNumber() {
            return episodeNumber;
        }

        public void setEpisodeNumber(Integer episodeNumber) {
            this.episodeNumber = episodeNumber;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getYoutubeVideoId() {
            return youtubeVideoId;
        }

        public void setYoutubeVideoId(String youtubeVideoId) {
            this.youtubeVideoId = youtubeVideoId;
        }

        public Integer getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(Integer durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        public Boolean getArchived() {
            return archived;
        }

        public void setArchived(Boolean archived) {
            this.archived = archived;
        }

        public Double getPassingScorePercentage() {
            return passingScorePercentage;
        }

        public void setPassingScorePercentage(Double passingScorePercentage) {
            this.passingScorePercentage = passingScorePercentage;
        }

        public List<Map<String, String>> getVocabulary() {
            return vocabulary;
        }

        public void setVocabulary(List<Map<String, String>> vocabulary) {
            this.vocabulary = vocabulary;
        }

        public List<QuestionBackup> getQuestions() {
            return questions;
        }

        public void setQuestions(List<QuestionBackup> questions) {
            this.questions = questions;
        }

        public List<OpenQuestionBackup> getOpenQuestions() {
            return openQuestions;
        }

        public void setOpenQuestions(List<OpenQuestionBackup> openQuestions) {
            this.openQuestions = openQuestions;
        }
    }

    public static class QuestionBackup {
        private String questionText;
        private Integer questionOrder;
        private Boolean active = true;
        private String status = "APPROVED";
        private String type = "RECALL";
        private String evidence;
        private Integer evidenceTimestamp;
        private List<OptionBackup> options = new ArrayList<>();

        public String getQuestionText() {
            return questionText;
        }

        public void setQuestionText(String questionText) {
            this.questionText = questionText;
        }

        public Integer getQuestionOrder() {
            return questionOrder;
        }

        public void setQuestionOrder(Integer questionOrder) {
            this.questionOrder = questionOrder;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getEvidence() {
            return evidence;
        }

        public void setEvidence(String evidence) {
            this.evidence = evidence;
        }
        

        public Integer getEvidenceTimestamp() {
            return evidenceTimestamp;
        }

        public void setEvidenceTimestamp(Integer evidenceTimestamp) {
            this.evidenceTimestamp = evidenceTimestamp;
        }

        public List<OptionBackup> getOptions() {
            return options;
        }

        public void setOptions(List<OptionBackup> options) {
            this.options = options;
        }
    }

    public static class OpenQuestionBackup {
        private String promptText;
        private String evidence;    
            private Integer evidenceTimestamp;
        
        private Boolean active = true;
        private String status = "APPROVED";

        public String getPromptText() {
            return promptText;
        }

        public void setPromptText(String promptText) {
            this.promptText = promptText;
        }

        public String getEvidence() {
            return evidence;
        }

        public void setEvidence(String evidence) {
            this.evidence = evidence;
        }
        

        public Integer getEvidenceTimestamp() {
            return evidenceTimestamp;
        }

        public void setEvidenceTimestamp(Integer evidenceTimestamp) {
            this.evidenceTimestamp = evidenceTimestamp;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class OptionBackup {
        private String optionText;
        private Boolean isCorrect;

        public OptionBackup() {
        }

        public OptionBackup(String optionText, Boolean isCorrect) {
            this.optionText = optionText;
            this.isCorrect = isCorrect;
        }

        public String getOptionText() {
            return optionText;
        }

        public void setOptionText(String optionText) {
            this.optionText = optionText;
        }

        public Boolean getIsCorrect() {
            return isCorrect;
        }

        public void setIsCorrect(Boolean isCorrect) {
            this.isCorrect = isCorrect;
        }
    }
}
