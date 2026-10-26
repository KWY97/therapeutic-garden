package com.example.manage.service;

import com.example.manage.dto.WeatherResponse;
import com.example.manage.dto.WeatherResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeatherServiceTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-10-26T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void calculatesAvailableDatesFromTodayInSeoul() {
        WeatherService service = new WeatherService(RestClient.create(), FIXED_CLOCK);

        assertThat(service.getAvailableStartDate()).isEqualTo(LocalDate.of(2026, 7, 26));
        assertThat(service.getAvailableEndDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(service.isDateAvailable(LocalDate.of(2026, 7, 26))).isTrue();
        assertThat(service.isDateAvailable(LocalDate.of(2026, 11, 10))).isTrue();
        assertThat(service.isDateAvailable(LocalDate.of(2026, 7, 25))).isFalse();
        assertThat(service.isDateAvailable(LocalDate.of(2026, 11, 11))).isFalse();
    }

    @Test
    void rejectsOutOfRangeDatesBeforeCallingForecastApi() {
        StubWeatherService service = new StubWeatherService(FIXED_CLOCK, response(
                List.of("2026-10-26T09:00"),
                List.of(18.5),
                List.of(55.0),
                List.of(0)
        ));

        assertThatThrownBy(() -> service.getWeather(
                LocalDate.of(2026, 7, 25),
                LocalTime.of(9, 0)
        )).isInstanceOf(WeatherService.WeatherDateOutOfRangeException.class)
                .hasMessageContaining("2026-07-26")
                .hasMessageContaining("2026-11-10");

        assertThat(service.calls).isZero();
    }

    @Test
    void selectsClosestTimeOnlyFromRequestedDateAndKeepsWeatherValues() {
        StubWeatherService service = new StubWeatherService(FIXED_CLOCK, response(
                List.of(
                        "2026-10-25T23:50",
                        "2026-10-26T09:00",
                        "2026-10-26T11:00"
                ),
                List.of(99.0, 18.5, 20.0),
                List.of(1.0, 55.0, 60.0),
                List.of(0, 61, 3)
        ));

        WeatherResult result = service.getWeather(
                LocalDate.of(2026, 10, 26),
                LocalTime.of(0, 10)
        );

        assertThat(result.getWeather()).isEqualTo("비");
        assertThat(result.getTemperature()).isEqualTo(18.5);
        assertThat(result.getHumidity()).isEqualTo(55.0);
    }

    @Test
    void returnsNoWeatherWhenResponseDoesNotContainRequestedDate() {
        StubWeatherService service = new StubWeatherService(FIXED_CLOCK, response(
                List.of("2026-10-25T23:00", "2026-10-27T00:00"),
                List.of(17.0, 20.0),
                List.of(50.0, 60.0),
                List.of(1, 2)
        ));

        assertThat(service.getWeather(
                LocalDate.of(2026, 10, 26),
                LocalTime.of(0, 0)
        )).isNull();
    }

    @Test
    void requestsNinetyTwoPastDaysAndSixteenForecastDays() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService service = new WeatherService(builder.build(), FIXED_CLOCK);

        server.expect(request -> assertThat(request.getURI().getQuery())
                        .contains("past_days=92")
                        .contains("forecast_days=16")
                        .contains("latitude=37.484849174599")
                        .contains("longitude=126.987829717918"))
                .andRespond(withSuccess("{\"hourly\":{\"time\":[]}}", MediaType.APPLICATION_JSON));

        service.getWeatherResponse();

        server.verify();
    }

    private static WeatherResponse response(
            List<String> times,
            List<Double> temperatures,
            List<Double> humidities,
            List<Integer> weatherCodes
    ) {
        WeatherResponse.Hourly hourly = new WeatherResponse.Hourly();
        hourly.setTime(times);
        hourly.setTemperature(temperatures);
        hourly.setHumidity(humidities);
        hourly.setWeatherCode(weatherCodes);

        WeatherResponse response = new WeatherResponse();
        response.setHourly(hourly);
        return response;
    }

    private static class StubWeatherService extends WeatherService {

        private final WeatherResponse response;
        private int calls;

        private StubWeatherService(Clock clock, WeatherResponse response) {
            super(RestClient.create(), clock);
            this.response = response;
        }

        @Override
        public WeatherResponse getWeatherResponse() {
            calls++;
            return response;
        }
    }
}
