package com.arabicquest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateEpisodeRequest {

    // Optional: if omitted, defaults to primary active playlist
    private Long playlistId;

    @NotNull(message = "Episode number is required")
    @Min(value = 1, message = "Episode number must be >= 1")
    private Integer episodeNumber;

    @NotBlank(message = "Episode title is required")
    @Size(max = 250, message = "Episode title must not exceed 250 characters")
    private String title;

    @NotBlank(message = "YouTube video ID or URL is required")
    @Size(max = 200, message = "YouTube video ID or URL is too long")
    private String youtubeVideoId;

    private Integer durationSeconds;

    public CreateEpisodeRequest() {
    }

    public CreateEpisodeRequest(Long playlistId, Integer episodeNumber, String title, String youtubeVideoId) {
        this.playlistId = playlistId;
        this.episodeNumber = episodeNumber;
        this.title = title;
        this.youtubeVideoId = youtubeVideoId;
    }

    public Long getPlaylistId() {
        return playlistId;
    }

    public void setPlaylistId(Long playlistId) {
        this.playlistId = playlistId;
    }

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
}
