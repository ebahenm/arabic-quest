package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateEpisodeRequest {

    @NotNull
    private Long playlistId;

    @NotNull
    private Integer episodeNumber;

    @NotBlank
    private String title;

    @NotBlank
    private String youtubeVideoId;

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
}
