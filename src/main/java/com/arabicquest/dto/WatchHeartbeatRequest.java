package com.arabicquest.dto;

import com.arabicquest.model.WatchMode;

public record WatchHeartbeatRequest(
        WatchMode watchMode,
        boolean external,
        boolean videoEnded,
        Integer watchedDeltaSeconds,
        Integer currentPositionSeconds,
        Integer videoDurationSeconds,
        Boolean playing
) {}
