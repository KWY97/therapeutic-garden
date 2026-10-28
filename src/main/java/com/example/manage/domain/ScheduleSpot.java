package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Entity
@Getter
@NoArgsConstructor
public class ScheduleSpot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long scheduleSpotId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spot_id", nullable = false)
    private HealingSpot healingSpot;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private Integer sequence;

    public ScheduleSpot(
            Schedule schedule,
            HealingSpot healingSpot,
            LocalTime startTime,
            Integer sequence) {
        this.schedule = schedule;
        this.healingSpot = healingSpot;
        this.startTime = startTime;
        this.sequence = sequence;
    }

    public void update(HealingSpot healingSpot, LocalTime startTime) {
        this.healingSpot = healingSpot;
        this.startTime = startTime;
    }
}
