package com.arabicquest.dto;

import com.arabicquest.model.WatchMode;

public record WatchStatusResponse(
        Long episodeId,
        WatchMode lockedWatchMode,
        boolean modeLocked,
        int verifiedWatchSeconds,
        int requiredWatchSeconds,
        boolean quizUnlocked,
        long retryCooldownRemainingSeconds
) {}
