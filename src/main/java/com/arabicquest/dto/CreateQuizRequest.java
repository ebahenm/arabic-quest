package com.arabicquest.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public class CreateQuizRequest {

    private Double passingScorePercentage = 80.0;

    @NotNull
    private List<CreateQuestionRequest> questions;

    public CreateQuizRequest() {
    }

    public Double getPassingScorePercentage() {
        return passingScorePercentage;
    }

    public void setPassingScorePercentage(Double passingScorePercentage) {
        this.passingScorePercentage = passingScorePercentage;
    }

    public List<CreateQuestionRequest> getQuestions() {
        return questions;
    }

    public void setQuestions(List<CreateQuestionRequest> questions) {
        this.questions = questions;
    }

    public static class CreateQuestionRequest {
        private String questionText;
        private Integer questionOrder;
        private List<CreateOptionRequest> options;

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

        public List<CreateOptionRequest> getOptions() {
            return options;
        }

        public void setOptions(List<CreateOptionRequest> options) {
            this.options = options;
        }
    }

    public static class CreateOptionRequest {
        private String optionText;
        private Boolean isCorrect = false;

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
