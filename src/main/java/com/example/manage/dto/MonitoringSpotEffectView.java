package com.example.manage.dto;

import com.example.manage.domain.HealingSpotEffectSummary;
/** Summary-only data used by the protected Monitoring screen. */
public record MonitoringSpotEffectView(
        String spotCode,
        String spotName,
        ImprovementMetricView stress,
        ImprovementMetricView emotional,
        Integer stressParticipantCount,
        Integer emotionalParticipantCount,
        boolean hasMeasurement) {

    public static MonitoringSpotEffectView overall(HealingSpotEffectSummary summary) {
        var stress = metric(summary.getStressValidSessionCount(), summary.getStressImprovedCount());
        var emotional = metric(summary.getEmotionalValidSessionCount(), summary.getEmotionalImprovedCount());
        return new MonitoringSpotEffectView(
                summary.getHealingSpot().getCode(), summary.getHealingSpot().getName(),
                stress, emotional, summary.getStressParticipantCount(), summary.getEmotionalParticipantCount(), true);
    }

    public static MonitoringSpotEffectView missing(String code, String name) {
        return new MonitoringSpotEffectView(code, name, null, null, null, null, false);
    }

    private static ImprovementMetricView metric(Integer validCount, Integer improvedCount) {
        if (validCount == null || improvedCount == null || validCount < 0
                || improvedCount < 0 || improvedCount > validCount) return null;
        return ImprovementMetricView.of(validCount, improvedCount);
    }
}
