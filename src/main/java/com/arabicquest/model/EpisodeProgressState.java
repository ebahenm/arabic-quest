package com.arabicquest.model;

public enum EpisodeProgressState {
    LOCKED("Locked"),
    AVAILABLE("Available"),
    WATCHING("Watching"),
    QUIZ_READY("Quiz ready"),
    QUIZ_NOT_READY("Quiz not ready (coming soon)"),
    PENDING_REVIEW("Pending review"),
    PASSED("Passed");

    private final String label;

    EpisodeProgressState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
