// Persists pipeline MCQs/open questions with AutoApprovalPolicy status, verifier metadata, and Compare Models selection.
package com.arabicquest.service;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.arabicquest.service.BlindTestService.BlindTestedCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class PipelinePersistenceHelper {

    private final EpisodeRepository episodeRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final AutoApprovalPolicy autoApprovalPolicy;
    private final TransactionTemplate transactionTemplate;

    public PipelinePersistenceHelper(
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            AutoApprovalPolicy autoApprovalPolicy,
            TransactionTemplate transactionTemplate) {
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.autoApprovalPolicy = autoApprovalPolicy;
        this.transactionTemplate = transactionTemplate;
    }

    public record PersistableOpen(CandidateOpen candidate, boolean pass2Parsed) {}
    public record PersistOutcome(int savedMcqs, int approvedMcqs, int savedOpens, boolean anyApproved) {}

    public PersistOutcome persistResultsTransactionally(
            Long episodeId,
            boolean autoTriggered,
            boolean episodeNeedsReview,
            List<BlindTestedCandidate> selectedMcqs,
            Map<Integer, Boolean> pass2ParsedByChunk,
            List<PersistableOpen> opens,
            String source,
            String writerModel,
            String verifierModel) {
        boolean singleModel = autoApprovalPolicy.isSingleModel(writerModel, verifierModel);
        PersistOutcome result = transactionTemplate.execute(st -> {
            Episode episode = episodeRepository.findById(episodeId).orElseThrow();
            if (episodeNeedsReview) {
                episode.setNeedsReview(true);
                episodeRepository.save(episode);
            }
            Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElseGet(() -> quizRepository.save(new Quiz(episode)));
            quiz.setPassingScorePercentage(100.0);
            int order = questionRepository.findByEpisodeIdWithOptions(episodeId).size() + 1;
            int vocabCount = 0, savedMcqs = 0, approvedMcqs = 0, savedOpens = 0;

            for (BlindTestedCandidate btc : selectedMcqs) {
                CandidateMcq c = btc.candidate();
                if (c.type() == QuestionType.VOCABULARY && ++vocabCount > 2) continue;
                boolean chunkPass2Parsed = pass2ParsedByChunk.getOrDefault(btc.chunkIndex(), false);
                QuestionStatus status = autoApprovalPolicy.decideMcqStatus(
                        autoTriggered, chunkPass2Parsed, c, btc.survivedBlindTest(),
                        episodeNeedsReview, writerModel, verifierModel);

                Question q = new Question(quiz, c.questionText(), order++);
                q.setType(c.type() != null ? c.type() : QuestionType.REASONING);
                q.setStatus(status);
                q.setActive(status == QuestionStatus.APPROVED);
                q.setEvidence(c.evidence());
                q.setEvidenceTimestamp(c.evidenceTimestamp());
                q.setChunkIndex(btc.chunkIndex());
                q.setSource(source);
                q.setVerifierModel(verifierModel);
                q.setSingleModel(singleModel);
                for (CandidateOption co : c.options()) {
                    q.getOptions().add(new QuestionOption(q, co.optionText(), co.isCorrect()));
                }
                questionRepository.save(q);
                savedMcqs++;
                if (status == QuestionStatus.APPROVED) approvedMcqs++;
            }

            for (PersistableOpen po : opens) {
                CandidateOpen co = po.candidate();
                QuestionStatus status = autoApprovalPolicy.decideOpenStatus(
                        autoTriggered, po.pass2Parsed(), co, episodeNeedsReview, writerModel, verifierModel);
                OpenQuestion oq = new OpenQuestion(episode, co.promptText(), co.evidence(), co.evidenceTimestamp(), status);
                oq.setActive(status == QuestionStatus.APPROVED);
                oq.setSource(source);
                oq.setVerifierModel(verifierModel);
                oq.setSingleModel(singleModel);
                openQuestionRepository.save(oq);
                savedOpens++;
            }
            return new PersistOutcome(savedMcqs, approvedMcqs, savedOpens, approvedMcqs > 0);
        });
        return result != null ? result : new PersistOutcome(0, 0, 0, false);
    }

    @Transactional
    public Map<String, Object> chooseComparedSet(Long episodeId, String chosenSet, Map<String, String> revealedModels) {
        String winner = "SET_B".equalsIgnoreCase(chosenSet) ? "SET_B" : "SET_A";
        String loser = "SET_A".equals(winner) ? "SET_B" : "SET_A";
        int approved = 0, retired = 0;
        for (Question q : questionRepository.findByEpisodeIdWithOptions(episodeId)) {
            if (winner.equals(q.getSource())) {
                q.setStatus(QuestionStatus.APPROVED);
                q.setActive(true);
                questionRepository.save(q);
                approved++;
            } else if (loser.equals(q.getSource())) {
                q.setStatus(QuestionStatus.RETIRED);
                q.setActive(false);
                questionRepository.save(q);
                retired++;
            }
        }
        return Map.of(
                "episodeId", episodeId,
                "chosenSet", winner,
                "approvedCount", approved,
                "retiredCount", retired,
                "revealedModels", revealedModels
        );
    }
}
