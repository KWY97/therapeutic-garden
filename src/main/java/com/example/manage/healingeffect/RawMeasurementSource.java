package com.example.manage.healingeffect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RawMeasurementSource(String sha256, String filename, List<Session> sessions) {
    public record Session(int sourceRow, int participantNo, LocalDate date, String courseCode,
            String experience, BigDecimal baselineStress, BigDecimal baselineEmotional,
            List<Spot> spots) {
    }

    public record Spot(String code, BigDecimal stress, BigDecimal emotional) {
    }

    public int measurementCount() {
        return sessions.stream().mapToInt(s -> s.spots().size()).sum();
    }
}
