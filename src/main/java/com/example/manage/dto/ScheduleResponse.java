package com.example.manage.dto;

import com.example.manage.domain.Schedule;
import com.example.manage.domain.ScheduleSpot;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
public class ScheduleResponse {

    private final Long scheduleId;
    private final LocalDate scheduleDate;
    private final MemberResponse member;
    private final String weather;
    private final Double temperature;
    private final Double humidity;
    private final Long siteId;
    private final String siteName;
    private final Long courseId;
    private final String courseCode;
    private final String courseName;
    private final List<MemberScheduleDetailResponse.SpotResponse> spots;

    public ScheduleResponse(Schedule schedule, List<ScheduleSpot> scheduleSpots) {
        this.scheduleId = schedule.getScheduleId();
        this.scheduleDate = schedule.getScheduleDate();
        this.member = new MemberResponse(schedule.getMember().getMemberId(),
                schedule.getMember().getParticipantNo(), schedule.getMember().getGroupNo(),
                schedule.getMember().getLoginId(), schedule.getMember().getName());
        this.weather = schedule.getWeather();
        this.temperature = schedule.getTemperature();
        this.humidity = schedule.getHumidity();
        this.spots = scheduleSpots.stream()
                .map(MemberScheduleDetailResponse.SpotResponse::new).toList();
        // 등록/수정 시 두 스팟이 같은 코스에 속하는지 검증한다.
        var course = scheduleSpots.isEmpty() ? null
                : scheduleSpots.get(0).getHealingSpot().getHealingCourse();
        this.courseId = course == null ? null : course.getCourseId();
        this.courseCode = course == null ? "" : course.getCode();
        this.courseName = course == null ? "미지정" : course.getName();
        this.siteId = course == null ? null : course.getSite().getSiteId();
        this.siteName = course == null ? "미지정" : course.getSite().getName();
    }

    @Getter
    @AllArgsConstructor
    public static class MemberResponse {

        private Long memberId;
        private Integer participantNo;
        private Integer groupNo;
        private String loginId;
        private String name;
    }
}
