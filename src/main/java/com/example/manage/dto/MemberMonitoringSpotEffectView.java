package com.example.manage.dto;

public record MemberMonitoringSpotEffectView(String spotCode, String spotName,
        ImprovementMetricView stress, ImprovementMetricView emotional, boolean hasMeasurement) {

    public static MemberMonitoringSpotEffectView from(HealingSpotImprovementView effect) {
        boolean measured = effect.stress().validCount() > 0 || effect.emotional().validCount() > 0;
        return new MemberMonitoringSpotEffectView(effect.spotCode(), effect.spotName(),
                effect.stress(), effect.emotional(), measured);
    }
}
