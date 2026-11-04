package com.arabicquest.dto;

import java.util.List;

public class QuestionViewResponse {
    private Long id;
    private String questionText;
    private Integer questionOrder;
    private List<OptionViewResponse> options;

    public QuestionViewResponse() {
    }

    public QuestionViewResponse(Long id, String questionText, Integer questionOrder, List<OptionViewResponse> options) {
        this.id = id;
        this.questionText = questionText;
        this.questionOrder = questionOrder;
        this.options = options;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public List<OptionViewResponse> getOptions() {
        return options;
    }

    public void setOptions(List<OptionViewResponse> options) {
        this.options = options;
    }
}
