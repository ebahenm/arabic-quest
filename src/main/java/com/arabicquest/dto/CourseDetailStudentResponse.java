package com.arabicquest.dto;

import java.util.List;

public record CourseDetailStudentResponse(
        CourseCatalogItemResponse course,
        List<EpisodeStudentResponse> episodes
) {}
