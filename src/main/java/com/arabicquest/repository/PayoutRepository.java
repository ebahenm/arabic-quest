package com.arabicquest.repository;

import com.arabicquest.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {

    @Query("SELECT p FROM Payout p JOIN FETCH p.student ORDER BY p.createdAt DESC, p.id DESC")
    List<Payout> findAllWithStudentOrderByCreatedAtDesc();

    @Query("SELECT p FROM Payout p JOIN FETCH p.student WHERE p.student.id = :studentId ORDER BY p.createdAt DESC, p.id DESC")
    List<Payout> findByStudentIdOrderByCreatedAtDesc(@Param("studentId") Long studentId);

    @Query("SELECT p FROM Payout p JOIN FETCH p.student WHERE p.id = :id")
    Optional<Payout> findByIdWithStudent(@Param("id") Long id);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payout p WHERE p.student.id = :studentId AND p.isPaid = true AND (p.voided IS NULL OR p.voided = false)")
    BigDecimal calculateTotalPaidToStudent(@Param("studentId") Long studentId);

    @Modifying
    @Query("UPDATE Payout p SET p.voided = false WHERE p.voided IS NULL")
    int backfillNullVoidedToFalse();

    void deleteByStudentId(Long studentId);
}
