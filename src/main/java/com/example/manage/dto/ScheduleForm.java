package com.example.manage.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
public class ScheduleForm {

    @NotNull(message = "참가자를 선택해 주세요.")
    private Long memberId;


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

    // 날씨 정보
    private String weather;

    private Double temperature;

    private Double humidity;
}
