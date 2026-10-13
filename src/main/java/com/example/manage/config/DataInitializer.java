package com.example.manage.config;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Site;
import com.example.manage.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
// Spring Boot 서버가 정상적으로 시작된 뒤 run() 메서드를 자동 실행
public class DataInitializer implements CommandLineRunner {

    private final AdminService adminService;
    private final MemberService memberService;
    private final SiteService siteService;
    private final HealingCourseService healingCourseService;
    private final HealingSpotService healingSpotService;

    @Override
    public void run(String... args) {
//        memberService.createMember(1, 1, "p001", "0001");
//        memberService.createMember(2, 1, "p002", "0002");
//        memberService.createMember(3, 1, "p003", "0003");
//        memberService.createMember(4, 2, "p004", "0004");
//        memberService.createMember(5, 2, "p005", "0005");
//        memberService.createMember(6, 2, "p006", "0006");
//        memberService.createMember(7, 2, "p007", "0007");
//        memberService.createMember(8, 3, "p008", "0008");
//        memberService.createMember(9, 3, "p009", "0009");
//        memberService.createMember(10, 3, "p010", "0010");
//        memberService.createMember(11, 3, "p011", "0011");
//
//        Site site = siteService.createSite("디에이치 방배", "서울 서초구 서초대로 50", 37.48405230687292, 126.98882349727562, 3);
//
//        HealingCourse healingCourseA = healingCourseService.createHealingCourse(site, "HC-A", "회복 코스", 37.4856509, 126.9888147, 90.0);
//        HealingCourse healingCourseB = healingCourseService.createHealingCourse(site, "HC-B", "감각 코스", 37.4831789, 126.9908729, 80.0);
//        HealingCourse healingCourseC = healingCourseService.createHealingCourse(site, "HC-C", "힐링 코스", 37.4824545, 126.9883743, 90.0);
//
//        healingSpotService.createHealingSpot(healingCourseA, "HS1", "호스타 정원", 37.48577591872291, 126.98839637835367);
//        healingSpotService.createHealingSpot(healingCourseA, "HS2", "곶자왈원", 37.48552596813059, 126.98923311873006);
//        healingSpotService.createHealingSpot(healingCourseB, "HS3", "가든 위스퍼스", 37.483215042958435, 126.99118380982651);
//        healingSpotService.createHealingSpot(healingCourseB, "HS4", "콜로네이드 가든", 37.483142914225716, 126.99056196515338);
//        healingSpotService.createHealingSpot(healingCourseC, "HS5", "블로썸 가든", 37.48283188337498, 126.98849859102245);
//        healingSpotService.createHealingSpot(healingCourseC, "HS6", "극림원", 37.48207726403847, 126.9882499689662);
    }
}
