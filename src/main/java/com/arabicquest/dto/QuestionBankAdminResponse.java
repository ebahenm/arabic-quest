package com.arabicquest.dto;

import com.arabicquest.model.OpenQuestion;
import com.arabicquest.model.Question;

import java.util.List;

public record QuestionBankAdminResponse(
        Long episodeId,
        String episodeTitle,
        boolean hasTranscript,
        int approvedMcqCount,
        int draftMcqCount,
        int retiredMcqCount,
        int approvedOpenCount,
        int draftOpenCount,
        boolean quizReadyForStudents,
        List<McqAdminItem> questions,
        List<OpenQuestionAdminItem> openQuestions
) {
    public record McqAdminItem(
            Long id,
            String questionText,
            Integer questionOrder,
            String status,
            String type,
            boolean active,
            String evidence,
            int reportCount,
            List<OptionAdminItem> options
    ) {
        public static McqAdminItem from(Question q) {
            return new McqAdminItem(
                    q.getId(),
                    q.getQuestionText(),
                    q.getQuestionOrder(),
                    q.getStatus().name(),
                    q.getType().name(),
                    q.isActive(),
                    q.getEvidence(),
                    q.getReportCount(),
                    q.getOptions().stream()
                            .map(o -> new OptionAdminItem(o.getId(), o.getOptionText(), Boolean.TRUE.equals(o.getIsCorrect())))
                            .toList()
            );
        }
    }

    public record OptionAdminItem(
            Long id,
            String optionText,
            boolean isCorrect
    ) {}

    public record OpenQuestionAdminItem(
            Long id,
            String promptText,
            String evidence,
            String status,
            boolean active
    ) {
        public static OpenQuestionAdminItem from(OpenQuestion oq) {
            return new OpenQuestionAdminItem(
                    oq.getId(),
                    oq.getPromptText(),
                    oq.getEvidence(),
                    oq.getStatus().name(),
                    oq.isActive()
            );
        }
    }
}
