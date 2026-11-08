package com.arabicquest.repository;

import com.arabicquest.model.BalanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface BalanceAdjustmentRepository extends JpaRepository<BalanceAdjustment, Long> {

    @Query("SELECT ba FROM BalanceAdjustment ba JOIN FETCH ba.student ORDER BY ba.timestamp DESC, ba.id DESC")
    List<BalanceAdjustment> findAllWithStudentOrderByTimestampDesc();

    @Query("SELECT ba FROM BalanceAdjustment ba JOIN FETCH ba.student WHERE ba.student.id = :studentId ORDER BY ba.timestamp DESC, ba.id DESC")
    List<BalanceAdjustment> findByStudentIdOrderByTimestampDesc(@Param("studentId") Long studentId);

    @Query("SELECT COALESCE(SUM(ba.amount), 0) FROM BalanceAdjustment ba WHERE ba.student.id = :studentId")
    BigDecimal calculateTotalAdjustmentsByStudent(@Param("studentId") Long studentId);

    boolean existsByStudentIdAndNote(Long studentId, String note);

    void deleteByStudentId(Long studentId);
}
