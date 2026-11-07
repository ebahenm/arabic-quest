package com.arabicquest.dto;

public record CourseResyncResponse(
        CourseAdminResponse course,
        int addedEpisodes,
        int updatedEpisodes,
        int deactivatedEpisodes,
        int skippedPrivateOrDeleted
) {
}
