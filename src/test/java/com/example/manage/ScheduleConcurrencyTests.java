package com.example.manage;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.ScheduleRepository;
import com.example.manage.repository.ScheduleSpotRepository;
import com.example.manage.repository.SiteRepository;
import com.example.manage.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ScheduleConcurrencyTests {
    @Autowired ScheduleService service;
    @Autowired MemberRepository members;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleSpotRepository scheduleSpots;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentIdenticalCreatesStoreOneScheduleAndTwoSpots() throws Exception {
        Member member = members.saveAndFlush(new Member(765432, 1, "concurrent-member", "password"));
        Site site = sites.saveAndFlush(new Site("동시성 사이트", "주소", 37.0, 127.0, 3));
        HealingCourse course = courses.saveAndFlush(new HealingCourse(site, "CONCURRENT", "동시성 코스", 37.0, 127.0, 50.0));
        HealingSpot first = spots.saveAndFlush(new HealingSpot(course, "FIRST", "첫 스팟", 37.0, 127.0));
        HealingSpot second = spots.saveAndFlush(new HealingSpot(course, "SECOND", "둘째 스팟", 37.0, 127.0));
        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalTime startTime = LocalTime.of(9, 0);
        CountDownLatch firstCreated = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstRequest = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                        first.getSpotId(), startTime, second.getSpotId(), startTime.plusMinutes(10),
                        null, null, null);
                firstCreated.countDown();
                try {
                    if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Commit timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return true;
            }));
            assertThat(firstCreated.await(5, TimeUnit.SECONDS)).isTrue();
            var secondRequest = executor.submit(() -> {
                secondStarted.countDown();
                try {
                    service.createSchedule(member.getMemberId(), date, site.getSiteId(), course.getCourseId(),
                            first.getSpotId(), startTime, second.getSpotId(), startTime.plusMinutes(10),
                            null, null, null);
                    return true;
                } catch (ScheduleService.DuplicateScheduleException exception) {
                    return false;
                }
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> secondRequest.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowCommit.countDown();

            assertThat(firstRequest.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(secondRequest.get(15, TimeUnit.SECONDS)).isFalse();
            assertThat(schedules.findByMemberMemberIdAndScheduleDate(member.getMemberId(), date)).hasSize(1);
            assertThat(scheduleSpots.count()).isEqualTo(2);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }
}
