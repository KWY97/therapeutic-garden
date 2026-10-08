package com.example.manage.healingeffect;

import com.example.manage.domain.HealingMeasurementRecord;
import com.example.manage.dto.ImprovementMetricView;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HealingImprovementAggregatorTests {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 14);

    private HealingMeasurementRecord row(long member, long spot, String baselineStress, String stress,
            String baselineEmotional, String emotional) {
        return new HealingMeasurementRecord(member, spot, DATE, "HC-A",
                decimal(baselineStress), decimal(stress), decimal(baselineEmotional), decimal(emotional));
    }

    private BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }

    @Test void rawPredicatesUseStrictDirectionAndMissingIsInvalid() {
        assertThat(row(1, 1, "10", "9", "10", "11").stressImproved()).isTrue();
        assertThat(row(1, 1, "10", "10", "10", "10").stressImproved()).isFalse();
        assertThat(row(1, 1, "10", "11", "10", "9").stressImproved()).isFalse();
        assertThat(row(1, 1, null, "9", null, "11").stressValid()).isFalse();
        assertThat(row(1, 1, "10", null, "10", null).stressValid()).isFalse();

        assertThat(row(1, 1, "10", "9", "10", "11").emotionalImproved()).isTrue();
        assertThat(row(1, 1, "10", "9", "10", "10").emotionalImproved()).isFalse();
        assertThat(row(1, 1, "10", "9", "10", "9").emotionalImproved()).isFalse();
        assertThat(row(1, 1, "10", "9", null, "11").emotionalValid()).isFalse();
        assertThat(row(1, 1, "10", "9", "10", null).emotionalValid()).isFalse();
    }

    @Test void aggregatesMemberSpotOverallSpotAndParticipantOverallFromCounts() {
        var result = new HealingImprovementAggregator().aggregate(List.of(
                row(1, 1, "10", "9", "10", "11"),
                row(1, 1, "10", "10", "10", "9"),
                row(1, 1, null, "8", null, "12"),
                row(2, 1, "10", "8", "10", "10"),
                row(1, 2, "10", "11", "10", "12")));

        var memberSpot = result.memberSpots().getFirst();
        assertThat(memberSpot.totalExperienceCount()).isEqualTo(3);
        assertThat(memberSpot.stress()).isEqualTo(new HealingImprovementAggregator.MetricCounts(2, 1));
        assertThat(memberSpot.emotional()).isEqualTo(new HealingImprovementAggregator.MetricCounts(2, 1));

        var overall = result.overallSpots().getFirst();
        assertThat(overall.participantCount()).isEqualTo(2);
        assertThat(overall.totalExperienceCount()).isEqualTo(4);
        assertThat(overall.stress()).isEqualTo(new HealingImprovementAggregator.MetricCounts(3, 2));
        assertThat(overall.emotional()).isEqualTo(new HealingImprovementAggregator.MetricCounts(3, 1));

        var participant = result.participantOverall().getFirst();
        assertThat(participant.totalExperienceCount()).isEqualTo(4);
        assertThat(participant.stress()).isEqualTo(new HealingImprovementAggregator.MetricCounts(3, 1));
        assertThat(participant.emotional()).isEqualTo(new HealingImprovementAggregator.MetricCounts(3, 2));
    }

    @Test void rateUsesValidDenominatorRoundsOnlyForDisplayAndZeroIsUnavailable() {
        var metric = ImprovementMetricView.of(17, 15);
        assertThat(metric.improvementRate()).isEqualByComparingTo("88.235294117647058824");
        assertThat(metric.improvementRateDisplay()).isEqualTo("88.2%");
        assertThat(metric.improvementCountDisplay()).isEqualTo("17회 중 15회 개선");
        var unavailable = ImprovementMetricView.of(0, 0);
        assertThat(unavailable.improvementRate()).isNull();
        assertThat(unavailable.improvementRateDisplay()).isEqualTo("측정 없음");
        assertThat(unavailable.improvementCountDisplay()).isEqualTo("측정 없음");
        assertThatThrownBy(() -> ImprovementMetricView.of(1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
