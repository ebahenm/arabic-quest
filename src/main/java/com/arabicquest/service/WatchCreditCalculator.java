// Computes server-side bounded watch-time credit (up to 90s cap, bounded by elapsed * 2 + tolerance) and clamps resume positions.
package com.arabicquest.service;

import java.time.Duration;
import java.time.LocalDateTime;

public final class WatchCreditCalculator {

    public static final int MAX_HEARTBEAT_CREDIT_SECONDS = 90;
    public static final double MAX_PLAYBACK_RATE_MULTIPLIER = 2.0;
    public static final double ELAPSED_TOLERANCE_SECONDS = 2.0;

    private WatchCreditCalculator() {
    }

    public record CreditComputation(long elapsedSeconds, int wallCap, int creditSeconds) {}

    public static CreditComputation computeCredit(
            LocalDateTime lastHeartbeatAt,
            LocalDateTime now,
            Integer reportedDeltaSeconds) {
        if (lastHeartbeatAt == null || now == null) {
            return new CreditComputation(0L, 0, 0);
        }
        long elapsedMillis = Math.max(0L, Duration.between(lastHeartbeatAt, now).toMillis());
        double elapsedSecs = elapsedMillis / 1000.0;
        long elapsedWhole = (long) Math.floor(elapsedSecs);

        int reported = reportedDeltaSeconds != null ? Math.max(0, reportedDeltaSeconds) : 0;
        if (reported <= 0 || elapsedMillis < 500L) {
            return new CreditComputation(elapsedWhole, 0, 0);
        }

        int maxAllowedByWall = (int) Math.floor(elapsedSecs * MAX_PLAYBACK_RATE_MULTIPLIER + ELAPSED_TOLERANCE_SECONDS);
        int wallCap = Math.min(MAX_HEARTBEAT_CREDIT_SECONDS, Math.max(0, maxAllowedByWall));
        int credit = Math.min(reported, wallCap);
        return new CreditComputation(elapsedWhole, wallCap, Math.max(0, credit));
    }

    public static int clampResumePosition(Integer reportedPositionSeconds, int episodeDurationSeconds) {
        if (reportedPositionSeconds == null || reportedPositionSeconds <= 0) {
            return 0;
        }
        int safeDuration = Math.max(0, episodeDurationSeconds);
        if (safeDuration <= 0) {
            return reportedPositionSeconds;
        }
        // Leave a tiny buffer before the very end so reopening a finished video doesn't get stuck at EOF
        int maxResume = Math.max(0, safeDuration - 5);
        return Math.min(reportedPositionSeconds, maxResume);
    }
}
