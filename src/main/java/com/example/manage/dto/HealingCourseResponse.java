package com.example.manage.dto;

import com.example.manage.domain.HealingCourse;
import lombok.Getter;

@Getter
public class HealingCourseResponse {

    private final Long courseId;
    private final String code;
    private final String name;
    private final Double centerLatitude;
    private final Double centerLongitude;
    private final Double radius;

    public HealingCourseResponse(HealingCourse healingCourse) {
        this.courseId = healingCourse.getCourseId();
        this.code = healingCourse.getCode();
        this.name = healingCourse.getName();
        this.centerLatitude = healingCourse.getCenterLatitude();
        this.centerLongitude = healingCourse.getCenterLongitude();
        this.radius = healingCourse.getRadius();
    }
}
