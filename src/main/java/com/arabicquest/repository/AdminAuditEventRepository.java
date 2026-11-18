// Repository for querying recent admin audit log events globally or per student.
package com.arabicquest.repository;

import com.arabicquest.model.AdminAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminAuditEventRepository extends JpaRepository<AdminAuditEvent, Long> {
    List<AdminAuditEvent> findTop50ByStudentIdOrderByCreatedAtDescIdDesc(Long studentId);
    List<AdminAuditEvent> findTop100ByOrderByCreatedAtDescIdDesc();
}
