// JPA entity tracking daily AI provider call counts and token usage for cost caps.
package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "ai_usage_daily", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"usage_date", "provider"})
})
public class AiUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "calls", nullable = false)
    private Integer calls = 0;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    public AiUsageDaily() {
    }

    public AiUsageDaily(LocalDate usageDate, String provider) {
        this.usageDate = usageDate;
        this.provider = provider;
        this.calls = 0;
        this.inputTokens = 0L;
        this.outputTokens = 0L;
    }

    public Long getId() { return id; }
    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getCalls() { return calls == null ? 0 : calls; }
    public void setCalls(Integer calls) { this.calls = calls == null ? 0 : calls; }
    public long getInputTokens() { return inputTokens == null ? 0L : inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens == null ? 0L : inputTokens; }
    public long getOutputTokens() { return outputTokens == null ? 0L : outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens == null ? 0L : outputTokens; }
}
