package com.arabicquest.dto;

public record GradeSuggestionResponse(
        String recommendedAction,
        String confidence,
        String reasoning,
        String suggestedFeedback
) {}
