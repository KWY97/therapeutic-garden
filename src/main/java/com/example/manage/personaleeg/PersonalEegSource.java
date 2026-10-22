package com.example.manage.personaleeg;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Four reported indices per dated HS observation; the private workbook stays outside Git. */
public record PersonalEegSource(String sha256, String filename, List<Session> sessions) {
    public PersonalEegSource {
        sessions = List.copyOf(sessions);
    }
    public record Session(int sourceRow, int participantNo, LocalDate sourceDate, LocalDate confirmedDate,
                          String courseCode, List<Spot> spots) {
        public Session { spots = List.copyOf(spots); }
    }
    public record Spot(String code, BigDecimal baselineStress, BigDecimal stressPost,
                       BigDecimal baselineEmotional, BigDecimal emotionalPost) {}
    public int resultCount() { return sessions.stream().mapToInt(s -> s.spots().size()).sum(); }
    public int completeCount() { return (int) sessions.stream().flatMap(s -> s.spots().stream())
            .filter(s -> s.stressPost() != null && s.emotionalPost() != null).count(); }
    public int postMissingCount() { return resultCount() - completeCount(); }
    public int correctedDateCount() { return (int) sessions.stream().filter(s -> !s.sourceDate().equals(s.confirmedDate())).count(); }
}
