package com.example.manage.healingeffect;

import java.math.BigDecimal;
import java.util.List;

public record HealingEffectSource(String sha256, String filename, List<Overall> overall, List<Participant> participants) {
    public HealingEffectSource {
        overall = List.copyOf(overall);
        participants = List.copyOf(participants);
    }

    public record Overall(String spot, int stressParticipants, int stressSessions, BigDecimal stressRate,
            int emotionalParticipants, int emotionalSessions, BigDecimal emotionalRate) {
    }

    public record Participant(String participant, int participantNo, String spot, int stressSessions,
            BigDecimal stressRate, int emotionalSessions, BigDecimal emotionalRate) {
    }
}
