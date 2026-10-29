package com.example.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
public class MemberScheduleForm {

    @NotNull(message = "일정 날짜를 선택해 주세요.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate scheduleDate;

    @NotNull(message = "사이트를 선택해 주세요.")
    private Long siteId;

    @NotNull(message = "코스를 선택해 주세요.")
    private Long courseId;

    @NotNull(message = "첫 번째 스팟을 선택해 주세요.")
    private Long firstSpotId;

    @NotNull(message = "첫 번째 시작 시간을 입력해 주세요.")
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime firstStartTime;

    @NotNull(message = "두 번째 스팟을 선택해 주세요.")
    private Long secondSpotId;

    @NotNull(message = "두 번째 시작 시간을 입력해 주세요.")
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime secondStartTime;

    private String weather;
    private Double temperature;
    private Double humidity;

    public static MemberScheduleForm fromSchedule(ScheduleResponse schedule) {
        MemberScheduleForm form = new MemberScheduleForm();
        form.setScheduleDate(schedule.getScheduleDate());
        form.setSiteId(schedule.getSiteId());
        form.setCourseId(schedule.getCourseId());
        form.setWeather(schedule.getWeather());
        form.setTemperature(schedule.getTemperature());
        form.setHumidity(schedule.getHumidity());
        if (!schedule.getSpots().isEmpty()) {
            form.setFirstSpotId(schedule.getSpots().get(0).getSpotId());
            form.setFirstStartTime(schedule.getSpots().get(0).getStartTime());
        }
        if (schedule.getSpots().size() > 1) {
            form.setSecondSpotId(schedule.getSpots().get(1).getSpotId());
            form.setSecondStartTime(schedule.getSpots().get(1).getStartTime());
        }
        return form;
    }
}
