// Enforces daily AI call and per-provider output token caps using ai_usage_daily, extensible for additional providers.
package com.arabicquest.service;

import com.arabicquest.model.AiUsageDaily;
import com.arabicquest.repository.AiUsageDailyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiUsageGuard {

    public static final int DEFAULT_DAILY_CALL_CAP = 150;
    public static final long DEFAULT_CLAUDE_OUTPUT_TOKEN_CAP = 150_000L;
    public static final int DEFAULT_DEEPSEEK_DAILY_CALL_CAP = 150;
    public static final long DEFAULT_DEEPSEEK_OUTPUT_TOKEN_CAP = 250_000L;

    public static class AiCostCapExceededException extends RuntimeException {
        public AiCostCapExceededException(String message) {
            super(message);
        }
    }

    private final AiUsageDailyRepository usageRepository;
    private final Map<String, Integer> providerCallCaps = new ConcurrentHashMap<>();
    private final Map<String, Long> providerOutputTokenCaps = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.systemUTC();

    @Value("${AI_DAILY_CALL_CAP:" + DEFAULT_DAILY_CALL_CAP + "}")
    private int dailyCallCap = DEFAULT_DAILY_CALL_CAP;

    public AiUsageGuard(
            AiUsageDailyRepository usageRepository,
            @Value("${CLAUDE_DAILY_OUTPUT_TOKEN_CAP:" + DEFAULT_CLAUDE_OUTPUT_TOKEN_CAP + "}") long claudeOutputTokenCap,
            @Value("${DEEPSEEK_DAILY_CALL_CAP:" + DEFAULT_DEEPSEEK_DAILY_CALL_CAP + "}") int deepSeekDailyCallCap,
            @Value("${DEEPSEEK_DAILY_OUTPUT_TOKEN_CAP:" + DEFAULT_DEEPSEEK_OUTPUT_TOKEN_CAP + "}") long deepSeekOutputTokenCap) {
        this.usageRepository = usageRepository;
        this.providerOutputTokenCaps.put("claude", claudeOutputTokenCap);
        this.providerCallCaps.put("deepseek", deepSeekDailyCallCap);
        this.providerOutputTokenCaps.put("deepseek", deepSeekOutputTokenCap);
    }

    public void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public int getDailyCallCap() {
        return dailyCallCap;
    }

    public void setDailyCallCap(int dailyCallCap) {
        this.dailyCallCap = dailyCallCap;
    }

    public void setProviderCallCap(String provider, Integer cap) {
        String key = normalizeProvider(provider);
        if (cap == null || cap <= 0) providerCallCaps.remove(key);
        else providerCallCaps.put(key, cap);
    }

    public void setProviderOutputTokenCap(String provider, Long cap) {
        String key = normalizeProvider(provider);
        if (cap == null || cap <= 0) providerOutputTokenCaps.remove(key);
        else providerOutputTokenCaps.put(key, cap);
    }

    @Transactional(readOnly = true)
    public boolean isGlobalCallCapReached() {
        if (dailyCallCap <= 0) return false;
        return getTotalCallsForDate(today()) >= dailyCallCap;
    }

    @Transactional(readOnly = true)
    public void assertGlobalCallCapAvailable() {
        if (dailyCallCap <= 0) return;
        int totalCalls = getTotalCallsForDate(today());
        if (totalCalls >= dailyCallCap) {
            throw new AiCostCapExceededException(
                    "Daily AI call cap reached (" + totalCalls + "/" + dailyCallCap + " calls today). Generation paused until tomorrow (UTC).");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanCall(String provider) {
        String key = normalizeProvider(provider);
        LocalDate date = today();
        List<AiUsageDaily> rows = usageRepository.findByUsageDateOrderByProviderAsc(date);
        int totalCalls = rows.stream().mapToInt(AiUsageDaily::getCalls).sum();
        if (dailyCallCap > 0 && totalCalls >= dailyCallCap) {
            throw new AiCostCapExceededException(
                    "Daily AI call cap reached (" + totalCalls + "/" + dailyCallCap + " calls today). Generation refused.");
        }
        AiUsageDaily providerRow = rows.stream().filter(r -> key.equals(r.getProvider())).findFirst().orElse(null);
        int pCalls = providerRow != null ? providerRow.getCalls() : 0;
        long pOutTokens = providerRow != null ? providerRow.getOutputTokens() : 0L;

        Integer pCallCap = providerCallCaps.get(key);
        if (pCallCap != null && pCallCap > 0 && pCalls >= pCallCap) {
            throw new AiCostCapExceededException(
                    "Daily " + key + " call cap reached (" + pCalls + "/" + pCallCap + " calls today).");
        }
        Long pTokenCap = providerOutputTokenCaps.get(key);
        if (pTokenCap != null && pTokenCap > 0 && pOutTokens >= pTokenCap) {
            throw new AiCostCapExceededException(
                    "Daily " + key + " output token cap reached (" + pOutTokens + "/" + pTokenCap + " tokens today).");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void recordCall(String provider, int inputTokens, int outputTokens) {
        String key = normalizeProvider(provider);
        LocalDate date = today();
        AiUsageDaily row = usageRepository.findByUsageDateAndProvider(date, key)
                .orElseGet(() -> new AiUsageDaily(date, key));
        row.setCalls(row.getCalls() + 1);
        row.setInputTokens(row.getInputTokens() + Math.max(0, inputTokens));
        row.setOutputTokens(row.getOutputTokens() + Math.max(0, outputTokens));
        usageRepository.save(row);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDailyUsageSnapshot() {
        LocalDate date = today();
        List<AiUsageDaily> rows = usageRepository.findByUsageDateOrderByProviderAsc(date);
        int totalCalls = 0;
        long totalInput = 0L, totalOutput = 0L;
        Map<String, Map<String, Object>> providers = new LinkedHashMap<>();

        Set<String> allProviders = new LinkedHashSet<>(List.of("claude", "gemini", "deepseek"));
        allProviders.addAll(providerCallCaps.keySet());
        allProviders.addAll(providerOutputTokenCaps.keySet());
        rows.forEach(r -> allProviders.add(r.getProvider()));

        for (String p : allProviders) {
            AiUsageDaily r = rows.stream().filter(x -> p.equals(x.getProvider())).findFirst().orElse(null);
            int calls = r != null ? r.getCalls() : 0;
            long inTok = r != null ? r.getInputTokens() : 0L;
            long outTok = r != null ? r.getOutputTokens() : 0L;
            totalCalls += calls;
            totalInput += inTok;
            totalOutput += outTok;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("calls", calls);
            pm.put("inputTokens", inTok);
            pm.put("outputTokens", outTok);
            pm.put("callCap", providerCallCaps.get(p));
            pm.put("outputTokenCap", providerOutputTokenCaps.get(p));
            providers.put(p, pm);
        }

        boolean capReached = (dailyCallCap > 0 && totalCalls >= dailyCallCap)
                || providers.entrySet().stream().anyMatch(e -> {
                    Long tokCap = providerOutputTokenCaps.get(e.getKey());
                    long outTok = ((Number) e.getValue().get("outputTokens")).longValue();
                    return tokCap != null && tokCap > 0 && outTok >= tokCap;
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("totalCalls", totalCalls);
        out.put("dailyCallCap", dailyCallCap);
        out.put("totalInputTokens", totalInput);
        out.put("totalOutputTokens", totalOutput);
        out.put("capReached", capReached);
        out.put("providers", providers);
        return out;
    }

    private int getTotalCallsForDate(LocalDate date) {
        return usageRepository.findByUsageDateOrderByProviderAsc(date)
                .stream().mapToInt(AiUsageDaily::getCalls).sum();
    }

    private static String normalizeProvider(String provider) {
        return (provider == null || provider.isBlank()) ? "unknown" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
