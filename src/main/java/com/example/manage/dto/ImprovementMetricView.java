package com.example.manage.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Count-backed improvement metric. A zero denominator produces an unavailable rate, never 0%. */
public record ImprovementMetricView(int validCount, int improvedCount, BigDecimal improvementRate,
        String improvementRateDisplay) {

    public static ImprovementMetricView of(int validCount, int improvedCount) {
        if (validCount < 0 || improvedCount < 0 || improvedCount > validCount)
            throw new IllegalArgumentException("invalid improvement counts");
        BigDecimal rate = validCount == 0 ? null
                : BigDecimal.valueOf(improvedCount).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(validCount), 18, RoundingMode.HALF_UP);
        return new ImprovementMetricView(validCount, improvedCount, rate,
                rate == null ? "측정 없음" : rate.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%");
    }

    public String improvementCountDisplay() {
        return validCount == 0 ? "측정 없음" : validCount + "회 중 " + improvedCount + "회 개선";
    }
}
