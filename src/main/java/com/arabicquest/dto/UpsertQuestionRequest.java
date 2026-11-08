package com.arabicquest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpsertQuestionRequest(
        @NotBlank(message = "Question text is required")
        @Size(max = 2000)
        String questionText,

        String type,
        String status,
        Boolean active,

        @Size(max = 3000)
        String evidence,

        Integer evidenceTimestamp,

        @Valid
        @NotEmpty(message = "Options are required")
        List<OptionInput> options
) {
    public record OptionInput(
            @NotBlank(message = "Option text is required")
            @Size(max = 1000)
            String optionText,
            Boolean isCorrect
    ) {}
}
