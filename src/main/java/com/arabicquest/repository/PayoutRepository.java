package com.arabicquest.repository;

import com.arabicquest.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {

    @Query("SELECT p FROM Payout p JOIN FETCH p.student ORDER BY p.createdAt DESC, p.id DESC")
    List<Payout> findAllWithStudentOrderByCreatedAtDesc();

    @Query("SELECT p FROM Payout p JOIN FETCH p.student WHERE p.student.id = :studentId ORDER BY p.createdAt DESC, p.id DESC")
    List<Payout> findByStudentIdOrderByCreatedAtDesc(@Param("studentId") Long studentId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payout p WHERE p.student.id = :studentId AND p.isPaid = true")
    BigDecimal calculateTotalPaidToStudent(@Param("studentId") Long studentId);
}
