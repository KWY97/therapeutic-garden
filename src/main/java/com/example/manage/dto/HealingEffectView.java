package com.example.manage.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Contains no participant identifier; safe to reuse as a future public view. */
public record HealingEffectView(
        String spotCode, String spotName,
        BigDecimal stressReductionRate, String stressReductionDisplay, String stressChangeDisplay,
        BigDecimal emotionalIncreaseRate, String emotionalIncreaseDisplay, String emotionalChangeDisplay,
        Integer stressValidSessionCount, Integer emotionalValidSessionCount,
        boolean hasMeasurement) {
    public static HealingEffectView measured(String code, String name, BigDecimal stress, BigDecimal emotional,
            Integer stressSessions, Integer emotionalSessions) {
        return new HealingEffectView(code, name, stress, display(stress), stressChangeDisplay(stress),
                emotional, display(emotional), emotionalChangeDisplay(emotional),
                stressSessions, emotionalSessions, true);
    }

    public static HealingEffectView missing(String code, String name) {
        return new HealingEffectView(code, name, null, "측정 없음", "측정 없음",
                null, "측정 없음", "측정 없음", null, null, false);
    }

    public static String display(BigDecimal rate) {
        return rate.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static String stressChangeDisplay(BigDecimal rate) {
        return directionalDisplay(rate, "감소", "증가");
    }

    public static String emotionalChangeDisplay(BigDecimal rate) {
        return directionalDisplay(rate, "증가", "감소");
    }

    private static String directionalDisplay(BigDecimal rate, String positiveDirection, String negativeDirection) {
        if (rate.signum() == 0)
            return "0.0% 변화 없음";
        String direction = rate.signum() > 0 ? positiveDirection : negativeDirection;
        return display(rate.abs()) + " " + direction;
    }
}
