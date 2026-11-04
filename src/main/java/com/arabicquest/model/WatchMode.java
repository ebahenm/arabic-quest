package com.arabicquest.model;

import java.math.BigDecimal;

public enum WatchMode {
    ARABIC_SUB("Arabic Audio with English Subs", new BigDecimal("15.00")),
    ENGLISH_DUB("English Dub", new BigDecimal("10.00"));

    private final String displayName;
    private final BigDecimal defaultReward;

    WatchMode(String displayName, BigDecimal defaultReward) {
        this.displayName = displayName;
        this.defaultReward = defaultReward;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BigDecimal getDefaultReward() {
        return defaultReward;
    }
}
