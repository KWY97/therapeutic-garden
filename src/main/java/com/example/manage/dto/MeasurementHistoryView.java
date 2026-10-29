package com.example.manage.dto;

import com.example.manage.domain.HealingMeasurementRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record MeasurementHistoryView(String spotCode, String spotName, List<Record> records) {
    public record Metric(BigDecimal baseline, BigDecimal post, BigDecimal change,
            BigDecimal rate, String changeDisplay, String rateDisplay) {
        public static Metric of(BigDecimal baseline, BigDecimal post, boolean stress) {
            BigDecimal change = baseline == null || post == null ? null : post.subtract(baseline);
            BigDecimal rate = change == null || baseline.signum() == 0 ? null
                    : change.multiply(BigDecimal.valueOf(stress ? -100 : 100)).divide(baseline, 18, RoundingMode.HALF_UP);
            return new Metric(baseline, post, change, rate,
                    change == null ? "계산 불가" : change.stripTrailingZeros().toPlainString(),
                    rate == null ? "계산 불가" : stress ? HealingEffectView.stressChangeDisplay(rate) : HealingEffectView.emotionalChangeDisplay(rate));
        }
    }

    public record Record(String measurementDate, String courseCode, Metric stress, Metric emotional) {
        public static Record from(HealingMeasurementRecord r) {
            return new Record(r.measurementDate().toString(), r.courseCode(), Metric.of(r.baselineStress(), r.stressPost(), true),
                    Metric.of(r.baselineEmotional(), r.emotionalPost(), false));
        }
    }
}
