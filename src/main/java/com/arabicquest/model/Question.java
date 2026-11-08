package com.arabicquest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    @JsonIgnore
    private Quiz quiz;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "question_order", nullable = false)
    private Integer questionOrder;

    @Column(name = "active")
    private Boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private QuestionStatus status = QuestionStatus.APPROVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20)
    private QuestionType type = QuestionType.RECALL;

    @JsonIgnore
    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @JsonIgnore
    @Column(name = "evidence_timestamp")
    private Integer evidenceTimestamp;

    @Column(name = "report_count")
    private Integer reportCount = 0;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuestionOption> options = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Question() {
    }

    public Question(Quiz quiz, String questionText, Integer questionOrder) {
        this.quiz = quiz;
        this.questionText = questionText;
        this.questionOrder = questionOrder;
        this.active = true;
        this.status = QuestionStatus.APPROVED;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.active == null) {
            this.active = true;
        }
        if (this.status == null) {
            this.status = QuestionStatus.APPROVED;
        }
        if (this.type == null) {
            this.type = QuestionType.RECALL;
        }
        if (this.reportCount == null) {
            this.reportCount = 0;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
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

    public boolean isActive() {
        return active == null || active;
    }

    public Boolean getActive() {
        return active == null ? Boolean.TRUE : active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public QuestionStatus getStatus() {
        return status == null ? QuestionStatus.APPROVED : status;
    }

    public void setStatus(QuestionStatus status) {
        this.status = status;
    }

    public QuestionType getType() {
        return type == null ? QuestionType.RECALL : type;
    }

    public void setType(QuestionType type) {
        this.type = type;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public Integer getEvidenceTimestamp() {
        return evidenceTimestamp;
    }

    public void setEvidenceTimestamp(Integer evidenceTimestamp) {
        this.evidenceTimestamp = evidenceTimestamp;
    }

    public int getReportCount() {
        return reportCount == null ? 0 : reportCount;
    }

    public void setReportCount(Integer reportCount) {
        this.reportCount = reportCount;
    }

    public List<QuestionOption> getOptions() {
        return options;
    }

    public void setOptions(List<QuestionOption> options) {
        this.options = options;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
