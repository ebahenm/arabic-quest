// Enforces strict per-question auto-approval rules (Pass 2 parsed, verified timestamp, blind test, option rules, cross-model).
package com.arabicquest.service;

import com.arabicquest.model.QuestionStatus;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AutoApprovalPolicy {

    @Value("${AUTO_APPROVE_SINGLE_MODEL:false}")
    private boolean autoApproveSingleModel = false;

    public boolean isAutoApproveSingleModel() {
        return autoApproveSingleModel;
    }

    public void setAutoApproveSingleModel(boolean autoApproveSingleModel) {
        this.autoApproveSingleModel = autoApproveSingleModel;
    }

    public boolean isSingleModel(String writerModel, String verifierModel) {
        if (writerModel == null || writerModel.isBlank() || verifierModel == null || verifierModel.isBlank()) {
            return true;
        }
        return writerModel.trim().equalsIgnoreCase(verifierModel.trim());
    }

    public boolean isTimestampVerified(Integer evidenceTimestamp, String evidence) {
        if (evidenceTimestamp == null || evidenceTimestamp < 0) {
            return false;
        }
        return evidence == null || !evidence.contains(PipelineCandidateNormalizer.UNVERIFIED_TIMESTAMP_TAG);
    }

    public QuestionStatus decideMcqStatus(
            boolean autoTriggered,
            boolean pass2Parsed,
            CandidateMcq candidate,
            boolean survivedBlindTest,
            boolean episodeNeedsReview,
            String writerModel,
            String verifierModel) {
        if (!autoTriggered || candidate == null) {
            return QuestionStatus.DRAFT;
        }
        boolean timestampVerified = isTimestampVerified(candidate.evidenceTimestamp(), candidate.evidence());
        boolean optionRuleOk = PipelineCandidateNormalizer.isOptionRuleCompliant(candidate);
        boolean singleModel = isSingleModel(writerModel, verifierModel);
        boolean modelDiversityOk = !singleModel || autoApproveSingleModel;

        if (pass2Parsed && timestampVerified && survivedBlindTest && optionRuleOk && !episodeNeedsReview && modelDiversityOk) {
            return QuestionStatus.APPROVED;
        }
        return QuestionStatus.DRAFT;
    }

    public QuestionStatus decideOpenStatus(
            boolean autoTriggered,
            boolean pass2Parsed,
            CandidateOpen candidate,
            boolean episodeNeedsReview,
            String writerModel,
            String verifierModel) {
        if (!autoTriggered || candidate == null) {
            return QuestionStatus.DRAFT;
        }
        boolean timestampVerified = isTimestampVerified(candidate.evidenceTimestamp(), candidate.evidence());
        boolean singleModel = isSingleModel(writerModel, verifierModel);
        boolean modelDiversityOk = !singleModel || autoApproveSingleModel;

        if (pass2Parsed && timestampVerified && !episodeNeedsReview && modelDiversityOk) {
            return QuestionStatus.APPROVED;
        }
        return QuestionStatus.DRAFT;
    }
}
