package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long scheduleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private LocalDate scheduleDate;

    private String weather;
    private Double temperature;
    private Double humidity;

    public Schedule(
            Member member,
            LocalDate scheduleDate) {
        this.member = member;
        this.scheduleDate = scheduleDate;
    }

    public void update(
            Member member,
            LocalDate scheduleDate
    ) {
        this.member = member;
        this.scheduleDate = scheduleDate;
    }

    public void updateDate(LocalDate scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public void updateWeather(
            String weather,
            Double temperature,
            Double humidity
    ) {
        this.weather = weather;
        this.temperature = temperature;
        this.humidity = humidity;
    }
}
