package com.example.manage.service;

import com.example.manage.dto.WeatherResponse;
import com.example.manage.dto.WeatherResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class WeatherService {

    public static final int PAST_DAYS = 69;
    public static final int FORECAST_DAYS = 16;

    private static final double LATITUDE = 37.484849174599;
    private static final double LONGITUDE = 126.987829717918;
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final RestClient restClient;
    private final Clock clock;

    public WeatherService() {
        this(
                RestClient.create(),
                Clock.system(SEOUL_ZONE)
        );
    }

    WeatherService(
            RestClient restClient,
            Clock clock
    ) {
        this.restClient = restClient;
        this.clock = clock;
    }


    public WeatherResponse getWeatherResponse() {

        return restClient.get()
                .uri(uriBuilder ->
                        uriBuilder
                                .scheme("https")
                                .host("api.open-meteo.com")
                                .path("/v1/forecast")
                                .queryParam("latitude", LATITUDE)
                                .queryParam("longitude", LONGITUDE)
                                .queryParam(
                                        "hourly",
                                        "temperature_2m,relative_humidity_2m,weather_code"
                                )
                                .queryParam("timezone", "auto")
                                .queryParam("past_days", PAST_DAYS)
                                .queryParam("forecast_days", FORECAST_DAYS)
                                .build()
                )
                .retrieve()
                .body(WeatherResponse.class);
    }


    public WeatherResult getWeather(
            LocalDate scheduleDate,
            LocalTime startTime
    ) {

        validateLookupDate(scheduleDate);

        WeatherResponse response =
                getWeatherResponse();

        if (response == null
                || response.getHourly() == null
                || response.getHourly().getTime() == null) {

            return null;
        }


        /*
         * 일정 날짜 + 시작 시간을 하나의 LocalDateTime으로 만든다.
         */
        LocalDateTime scheduleDateTime =
                LocalDateTime.of(
                        scheduleDate,
                        startTime
                );


        int closestIndex = -1;
        long smallestDifference = Long.MAX_VALUE;


        /*
         * Open-Meteo가 반환한 모든 시간 중에서
         * 일정 시작 시간과 가장 가까운 시간을 찾는다.
         */
        for (int i = 0;
             i < response.getHourly().getTime().size();
             i++) {

            LocalDateTime weatherDateTime =
                    LocalDateTime.parse(
                            response.getHourly()
                                    .getTime()
                                    .get(i)
                    );

            if (!weatherDateTime.toLocalDate().equals(scheduleDate)) {
                continue;
            }


            long difference =
                    Math.abs(
                            ChronoUnit.MINUTES.between(
                                    scheduleDateTime,
                                    weatherDateTime
                            )
                    );


            if (difference < smallestDifference) {

                smallestDifference = difference;
                closestIndex = i;
            }
        }


        /*
         * 적절한 시간을 찾지 못한 경우
         */
        if (closestIndex == -1) {
            return null;
        }

        if (!hasWeatherValues(response.getHourly(), closestIndex)) {
            return null;
        }


        Double temperature =
                response.getHourly()
                        .getTemperature()
                        .get(closestIndex);

        Double humidity =
                response.getHourly()
                        .getHumidity()
                        .get(closestIndex);

        Integer weatherCode =
                response.getHourly()
                        .getWeatherCode()
                        .get(closestIndex);


        String weather =
                convertWeatherCode(weatherCode);


        return new WeatherResult(
                weather,
                temperature,
                humidity
        );
    }

    public LocalDate getAvailableStartDate() {
        return getToday().minusDays(PAST_DAYS);
    }

    public LocalDate getAvailableEndDate() {
        return getToday().plusDays(FORECAST_DAYS - 1L);
    }

    public boolean isDateAvailable(LocalDate date) {
        return date != null
                && !date.isBefore(getAvailableStartDate())
                && !date.isAfter(getAvailableEndDate());
    }

    private LocalDate getToday() {
        return LocalDate.now(clock.withZone(SEOUL_ZONE));
    }

    private void validateLookupDate(LocalDate scheduleDate) {
        if (isDateAvailable(scheduleDate)) {
            return;
        }

        throw new WeatherDateOutOfRangeException(
                getAvailableStartDate(),
                getAvailableEndDate()
        );
    }

    private boolean hasWeatherValues(
            WeatherResponse.Hourly hourly,
            int index
    ) {
        return hasIndex(hourly.getTemperature(), index)
                && hasIndex(hourly.getHumidity(), index)
                && hasIndex(hourly.getWeatherCode(), index)
                && hourly.getTemperature().get(index) != null
                && hourly.getHumidity().get(index) != null
                && hourly.getWeatherCode().get(index) != null;
    }

    private boolean hasIndex(List<?> values, int index) {
        return values != null && index >= 0 && index < values.size();
    }

    public static class WeatherDateOutOfRangeException extends IllegalArgumentException {

        private final LocalDate startDate;
        private final LocalDate endDate;

        public WeatherDateOutOfRangeException(
                LocalDate startDate,
                LocalDate endDate
        ) {
            super("날씨는 " + startDate + "부터 " + endDate + "까지 불러올 수 있습니다.");
            this.startDate = startDate;
            this.endDate = endDate;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }
    }


    /*
     * Open-Meteo WMO Weather Code를
     * 화면에서 사용할 한글 날씨 문자열로 변환한다.
     */
    private String convertWeatherCode(Integer weatherCode) {

        if (weatherCode == null) {
            return "알 수 없음";
        }

        return switch (weatherCode) {

            case 0 ->
                    "맑음";

            case 1, 2 ->
                    "대체로 맑음";

            case 3 ->
                    "흐림";

            case 45, 48 ->
                    "안개";

            case 51, 53, 55, 56, 57 ->
                    "이슬비";

            case 61, 63, 65, 66, 67 ->
                    "비";

            case 71, 73, 75, 77 ->
                    "눈";

            case 80, 81, 82 ->
                    "소나기";

            case 85, 86 ->
                    "눈 소나기";

            case 95, 96, 99 ->
                    "뇌우";

            default ->
                    "알 수 없음";
        };
    }
}
