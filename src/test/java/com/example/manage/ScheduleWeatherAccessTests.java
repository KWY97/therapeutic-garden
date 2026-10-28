package com.example.manage;

import com.example.manage.dto.WeatherResult;
import com.example.manage.service.WeatherService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleWeatherAccessTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @MockitoBean WeatherService weatherService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).addFilters(security).build();
    }

    @Test
    void memberRequestToExistingAdminWeatherEndpointReturnsUnauthorized() throws Exception {
        mvc.perform(get("/admin/weather").sessionAttr("loginMemberId", 1L)
                        .param("date", "2026-10-28").param("time", "09:00"))
                .andExpect(status().isUnauthorized());

        var login = mvc.perform(get("/admin/login").sessionAttr("loginMemberId", 1L))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(login.getContentType()).startsWith("text/html");
        assertThat(login.getContentAsString()).contains("<html", "관리자 로그인");
    }

    @Test
    void memberWeatherReturnsTheSharedServiceResultWithRequestedDateAndTime() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalTime time = LocalTime.of(9, 0);
        when(weatherService.getWeather(date, time)).thenReturn(new WeatherResult("맑음", 24.5, 61.0));

        mvc.perform(get("/member/weather").sessionAttr("loginMemberId", 1L)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.weather").value("맑음"))
                .andExpect(jsonPath("$.temperature").value(24.5))
                .andExpect(jsonPath("$.humidity").value(61.0));
        verify(weatherService).getWeather(date, time);
    }

    @Test
    void adminWeatherKeepsItsExistingEndpointAndResponse() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalTime time = LocalTime.of(9, 0);
        when(weatherService.getWeather(date, time)).thenReturn(new WeatherResult("비", 18.0, 80.0));

        mvc.perform(get("/admin/weather").sessionAttr("loginAdminId", 1L)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weather").value("비"))
                .andExpect(jsonPath("$.temperature").value(18.0))
                .andExpect(jsonPath("$.humidity").value(80.0));
        verify(weatherService).getWeather(date, time);
    }

    @Test
    void weatherEndpointsRequireTheirOwnRolesAndReturnJsonErrors() throws Exception {
        mvc.perform(get("/member/weather").param("date", "2026-10-28").param("time", "09:00"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/member/weather").sessionAttr("loginAdminId", 1L)
                        .param("date", "2026-10-28").param("time", "09:00"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/weather").param("date", "2026-10-28").param("time", "09:00"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/schedules").sessionAttr("loginMemberId", 1L))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/login"));

        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalTime time = LocalTime.of(9, 0);
        mvc.perform(get("/member/weather").sessionAttr("loginMemberId", 1L)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("선택한 날짜의 날씨 데이터가 제공되지 않습니다. 다른 날짜를 선택해 주세요."));

        when(weatherService.getWeather(date, time)).thenThrow(
                new WeatherService.WeatherDateOutOfRangeException(date.minusDays(69), date.plusDays(15)));
        mvc.perform(get("/member/weather").sessionAttr("loginMemberId", 1L)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "member"})
    void bothWeatherEndpointsKeepExactNotFoundAndRangeErrorResponses(String role) throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalTime time = LocalTime.of(9, 0);
        String path = "/" + role + "/weather";
        String sessionKey = role.equals("admin") ? "loginAdminId" : "loginMemberId";

        mvc.perform(get(path).sessionAttr(sessionKey, 1L)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value(
                        "선택한 날짜의 날씨 데이터가 제공되지 않습니다. 다른 날짜를 선택해 주세요."));

        var rangeError = new WeatherService.WeatherDateOutOfRangeException(
                date.minusDays(69), date.plusDays(15));
        when(weatherService.getWeather(date, time)).thenThrow(rangeError);
        mvc.perform(get(path).sessionAttr(sessionKey, 1L)
                        .param("date", date.toString()).param("time", "09:00"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value(rangeError.getMessage()));

        mvc.perform(get(path).sessionAttr(sessionKey, 1L)
                        .param("date", "invalid-date").param("time", "09:00"))
                .andExpect(status().isBadRequest());
    }
}
