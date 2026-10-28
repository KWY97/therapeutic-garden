package com.example.manage.controller;

import com.example.manage.dto.WeatherResult;
import com.example.manage.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WeatherLookupResponse {
    private final WeatherService weatherService;

    public ResponseEntity<?> getWeather(LocalDate date, LocalTime time) {
        try {
            WeatherResult result = weatherService.getWeather(date, time);
            if (result == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "선택한 날짜의 날씨 데이터가 제공되지 않습니다. 다른 날짜를 선택해 주세요."
                ));
            }
            return ResponseEntity.ok(result);
        } catch (WeatherService.WeatherDateOutOfRangeException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
