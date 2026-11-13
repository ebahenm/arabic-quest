// Preserves historical watch mode enum values while unifying default episode reward to $15.00.
package com.arabicquest.model;

import java.math.BigDecimal;

public enum WatchMode {
    ARABIC_SUB("Standard Mode", new BigDecimal("15.00")),
    ENGLISH_DUB("English Dub (Legacy)", new BigDecimal("15.00"));

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
