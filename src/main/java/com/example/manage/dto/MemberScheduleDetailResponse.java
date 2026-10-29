package com.example.manage.dto;

import com.example.manage.domain.ScheduleSpot;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class MemberScheduleDetailResponse {

    private Long scheduleId;
    private LocalDate scheduleDate;
    private Long siteId;
    private String siteName;
    private Long courseId;
    private String courseCode;
    private String courseName;
    private Integer groupNo;
    private String weather;
    private List<SpotResponse> spots;

    @Getter
    public static class SpotResponse {

        private Long spotId;
        private String code;
        private String name;
        private LocalTime startTime;
        private Integer sequence;

        public SpotResponse(ScheduleSpot scheduleSpot) {
            this.spotId = scheduleSpot.getHealingSpot().getSpotId();
            this.code = scheduleSpot.getHealingSpot().getCode();
            this.name = scheduleSpot.getHealingSpot().getName();
            this.startTime = scheduleSpot.getStartTime();
            this.sequence = scheduleSpot.getSequence();
        }
    }
}
