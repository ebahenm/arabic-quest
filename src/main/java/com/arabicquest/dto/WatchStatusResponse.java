// Watch progress status DTO tracking verified watch time, quiz unlock state, and retry cooldown.
package com.arabicquest.dto;

import com.arabicquest.model.WatchMode;

public record WatchStatusResponse(
        Long episodeId,
        WatchMode lockedWatchMode,
        boolean modeLocked,
        int verifiedWatchSeconds,
        int requiredWatchSeconds,
        boolean quizUnlocked,
        long retryCooldownRemainingSeconds,
        boolean watchStarted
) {
    public WatchStatusResponse(
            Long episodeId,
            WatchMode lockedWatchMode,
            boolean modeLocked,
            int verifiedWatchSeconds,
            int requiredWatchSeconds,
            boolean quizUnlocked,
            long retryCooldownRemainingSeconds) {
        this(episodeId, lockedWatchMode, modeLocked, verifiedWatchSeconds, requiredWatchSeconds,
                quizUnlocked, retryCooldownRemainingSeconds, verifiedWatchSeconds > 0);
    }
}
