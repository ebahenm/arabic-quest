package com.arabicquest.dto;

import com.arabicquest.model.Quiz;
import java.util.List;

public record QuizAdminViewResponse(
        Long id,
        Long episodeId,
        Double passingScorePercentage,
        List<AdminQuestionView> questions
) {
    public record AdminQuestionView(
            Long id,
            String questionText,
            Integer questionOrder,
            List<AdminOptionView> options
    ) {}

    public record AdminOptionView(
            Long id,
            String optionText,
            Boolean isCorrect
    ) {}

    public static QuizAdminViewResponse from(Quiz quiz, Long episodeId) {
        List<AdminQuestionView> qList = quiz.getQuestions().stream()
                .map(q -> new AdminQuestionView(
                        q.getId(),
                        q.getQuestionText(),
                        q.getQuestionOrder(),
                        q.getOptions().stream()
                                .map(o -> new AdminOptionView(o.getId(), o.getOptionText(), o.getIsCorrect()))
                                .toList()
                ))
                .toList();
        return new QuizAdminViewResponse(quiz.getId(), episodeId, quiz.getPassingScorePercentage(), qList);
    }
}
