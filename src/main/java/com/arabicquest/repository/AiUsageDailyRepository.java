// Repository for querying and updating daily AI provider call and token usage rows.
package com.arabicquest.repository;

import com.arabicquest.model.AiUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiUsageDailyRepository extends JpaRepository<AiUsageDaily, Long> {
    Optional<AiUsageDaily> findByUsageDateAndProvider(LocalDate usageDate, String provider);
    List<AiUsageDaily> findByUsageDateOrderByProviderAsc(LocalDate usageDate);
}
