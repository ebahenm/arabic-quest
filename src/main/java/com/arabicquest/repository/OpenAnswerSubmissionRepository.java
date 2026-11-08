package com.arabicquest.repository;

import com.arabicquest.model.OpenAnswerStatus;
import com.arabicquest.model.OpenAnswerSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OpenAnswerSubmissionRepository extends JpaRepository<OpenAnswerSubmission, Long> {

    Optional<OpenAnswerSubmission> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);

    @Query("SELECT oas FROM OpenAnswerSubmission oas " +
           "JOIN FETCH oas.student " +
           "JOIN FETCH oas.episode e " +
           "JOIN FETCH e.playlist " +
           "JOIN FETCH oas.openQuestion " +
           "WHERE oas.id = :id")
    Optional<OpenAnswerSubmission> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT oas FROM OpenAnswerSubmission oas " +
           "JOIN FETCH oas.student " +
           "JOIN FETCH oas.episode e " +
           "JOIN FETCH e.playlist " +
           "JOIN FETCH oas.openQuestion " +
           "ORDER BY oas.submittedAt DESC")
    List<OpenAnswerSubmission> findAllWithDetailsOrderBySubmittedAtDesc();

    @Query("SELECT oas FROM OpenAnswerSubmission oas " +
           "JOIN FETCH oas.student " +
           "JOIN FETCH oas.episode e " +
           "JOIN FETCH e.playlist " +
           "JOIN FETCH oas.openQuestion " +
           "WHERE oas.status = :status " +
           "ORDER BY oas.submittedAt ASC")
    List<OpenAnswerSubmission> findByStatusWithDetailsOrderBySubmittedAtAsc(@Param("status") OpenAnswerStatus status);

    @Query("SELECT oas FROM OpenAnswerSubmission oas " +
           "JOIN FETCH oas.student " +
           "JOIN FETCH oas.episode e " +
           "JOIN FETCH e.playlist " +
           "JOIN FETCH oas.openQuestion " +
           "WHERE (oas.readByAdmin IS NULL OR oas.readByAdmin = false) " +
           "ORDER BY oas.submittedAt ASC")
    List<OpenAnswerSubmission> findUnreadWithDetailsOrderBySubmittedAtAsc();

    @Query("SELECT oas FROM OpenAnswerSubmission oas " +
           "JOIN FETCH oas.student " +
           "JOIN FETCH oas.episode e " +
           "JOIN FETCH e.playlist " +
           "JOIN FETCH oas.openQuestion " +
           "WHERE oas.readByAdmin = true " +
           "ORDER BY oas.submittedAt DESC")
    List<OpenAnswerSubmission> findReadWithDetailsOrderBySubmittedAtDesc();

    @Query("SELECT COUNT(oas) FROM OpenAnswerSubmission oas WHERE oas.readByAdmin IS NULL OR oas.readByAdmin = false")
    long countUnread();

    @Query("SELECT oas FROM OpenAnswerSubmission oas " +
           "JOIN FETCH oas.episode e " +
           "JOIN FETCH e.playlist " +
           "WHERE oas.student.id = :studentId")
    List<OpenAnswerSubmission> findByStudentIdWithEpisodeAndPlaylist(@Param("studentId") Long studentId);

    @Modifying
    @Query("UPDATE OpenAnswerSubmission oas SET oas.readByAdmin = true WHERE oas.readByAdmin IS NULL AND oas.status = com.arabicquest.model.OpenAnswerStatus.APPROVED")
    int backfillNullReadByAdminForApproved();

    @Modifying
    @Query("UPDATE OpenAnswerSubmission oas SET oas.readByAdmin = false WHERE oas.readByAdmin IS NULL")
    int backfillNullReadByAdminToFalse();

    boolean existsByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);
}
