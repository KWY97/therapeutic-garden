package com.example.manage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class WeatherResponse {

    private Hourly hourly;

    @Getter
    @Setter
    public static class Hourly {

        private List<String> time;

        @JsonProperty("temperature_2m")
        private List<Double> temperature;

        @JsonProperty("relative_humidity_2m")
        private List<Double> humidity;

        @JsonProperty("weather_code")
        private List<Integer> weatherCode;
    }
}
