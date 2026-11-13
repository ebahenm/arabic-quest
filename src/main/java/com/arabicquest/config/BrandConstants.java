// Single source of truth for brand identity, palette, and default reward constants.
package com.arabicquest.config;

import java.math.BigDecimal;

/**
 * Single source of truth for brand identity, palette, and default reward constants.
 */
public final class BrandConstants {

    public static final String BRAND_NAME_LATIN = "Turathtona";
    public static final String BRAND_NAME_ARABIC = "تراثُنا";
    public static final String TAGLINE = "Know your history. Stand tall.";

    public static final String COLOR_NAVY = "#0b1f33";
    public static final String COLOR_DEEP_TEAL = "#045b62";
    public static final String COLOR_GOLD = "#c9a24d";
    public static final String COLOR_AMBER = "#e0a84f";
    public static final String COLOR_CREAM = "#f5ecd6";

    public static final BigDecimal DEFAULT_EPISODE_REWARD = new BigDecimal("15.00");
    public static final BigDecimal DEFAULT_RATE_ARABIC_SUB = DEFAULT_EPISODE_REWARD;
    public static final BigDecimal DEFAULT_RATE_ENGLISH_DUB = DEFAULT_EPISODE_REWARD;
    public static final BigDecimal DEFAULT_COMPLETION_BONUS = new BigDecimal("100.00");

    private BrandConstants() {
    }
}
