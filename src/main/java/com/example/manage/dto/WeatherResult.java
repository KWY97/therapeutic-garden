package com.example.manage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class WeatherResult {

    private String weather;
    private Double temperature;
    private Double humidity;
}
