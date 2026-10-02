package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoleBasedHomeTests {
    @Autowired WebApplicationContext context;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Value("${kakao.maps.javascript-key}") String kakaoKey;
    MockMvc mvc;
    @Autowired ResourceUrlProvider resourceUrlProvider;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void anonymousLandingAndRoleHomeRedirects() throws Exception {
        String html = mvc.perform(get("/"))
                .andExpect(status().isOk()).andExpect(view().name("landing"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("href=\"/admin/login\"", "href=\"/member/login\"", "href=\"/\"")
                .doesNotContain("home-map.js", "sdk.js", "id=\"map\"");
        mvc.perform(get("/").sessionAttr("loginAdminId", 1L))
                .andExpect(redirectedUrl("/admin/monitoring"));
        mvc.perform(get("/").sessionAttr("loginMemberId", 1L))
                .andExpect(redirectedUrl("/member"));
    }

    @Test
    void adminLoginReplacesMemberSessionAndOpensMonitoring() throws Exception {
        Admin admin = admins.save(new Admin("role-home-admin", encoder.encode("password")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginMemberId", 10L);
        mvc.perform(post("/admin/login").session(session)
                        .param("loginId", admin.getLoginId()).param("password", "password"))
                .andExpect(redirectedUrl("/admin/monitoring"));
        assertThat(session.getAttribute("loginAdminId")).isEqualTo(admin.getAdminId());
        assertThat(session.getAttribute("loginMemberId")).isNull();
        mvc.perform(get("/member").session(session)).andExpect(redirectedUrl("/member/login"));
    }

    @Test
    void memberLoginReplacesAdminSessionAndKeepsMemberHome() throws Exception {
        Member member = members.save(new Member(987654, 1, "role-home-member", encoder.encode("password")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginAdminId", 10L);
        mvc.perform(post("/member/login").session(session)
                        .param("loginId", member.getLoginId()).param("password", "password"))
                .andExpect(redirectedUrl("/member"));
        assertThat(session.getAttribute("loginMemberId")).isEqualTo(member.getMemberId());
        assertThat(session.getAttribute("loginAdminId")).isNull();
        String html = mvc.perform(get("/member").session(session)).andExpect(status().isOk())
                .andExpect(view().name("member/home")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"calendar\"", "id=\"schedule-data\"", "id=\"schedule-detail\"",
                "MY HEALING ANALYSIS", "나의 치유 분석", "MY CALENDAR", "나의 달력", "SELECTED SCHEDULE", "일정을 선택해 주세요.")
                .doesNotContain("PARTICIPANT", "나의 일정", "참가자님의 일정을 확인해 주세요.", "landing.js");
        String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));
        assertThat(header).contains("href=\"/member\"", "href=\"/member/logout\"")
                .doesNotContain("/admin", "/member/login");
        mvc.perform(get("/admin").session(session)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/monitoring").session(session)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/member/logout").session(session)).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void monitoringRequiresAdminAndRetainsModelAndScripts() throws Exception {
        mvc.perform(get("/admin/monitoring")).andExpect(redirectedUrl("/admin/login"));
        String html = mvc.perform(get("/admin/monitoring").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(view().name("home"))
                .andExpect(model().attributeExists("sites", "monitoringEffects"))
                .andExpect(model().attributeDoesNotExist("monitoringParticipants", "monitoringHistory"))
                .andExpect(model().attribute("kakaoMapsJavaScriptKey", kakaoKey))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"siteSelect\"", "id=\"map\"", "id=\"healingSpotSelect\"",
                "id=\"spotDetailModal\"", "href=\"/admin/monitoring\"");
        String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));
        assertThat(header).contains("href=\"/admin/monitoring\"", "href=\"/admin\"", "href=\"/admin/logout\"")
                .doesNotContain("href=\"/member\"", "href=\"/member/login\"", "/admin/login");
        assertThat(html).doesNotContain("landing.js");
        assertThat(html).contains("sdk.js", "home-survey-analysis.js", "home-map.js");
        assertThat(html.indexOf("sdk.js")).isLessThan(html.indexOf("home-survey-analysis.js"));
        assertThat(html.indexOf("home-survey-analysis.js")).isLessThan(html.indexOf("home-map.js"));
    }

    @Test
    void loginPagesRenderAnonymousHeaderAndMemberFailureMessage() throws Exception {
        for (String path : new String[]{"/admin/login", "/member/login"}) {
            String html = mvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));
            assertThat(header).contains("href=\"/\"", "href=\"/admin/login\"", "href=\"/member/login\"")
                    .doesNotContain("/logout");
            assertThat(html).doesNotContain("home-map.js", "home-survey-analysis.js", "sdk.js", "landing.js");
        }
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginAdminId", 1L);
        String html = mvc.perform(post("/member/login").session(session)
                        .param("loginId", "missing-role-home-member").param("password", "wrong"))
                .andExpect(status().isOk()).andExpect(view().name("member/login"))
                .andExpect(model().attributeExists("errorMessage"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("아이디 또는 비밀번호가 일치하지 않습니다.", "role=\"alert\"");
        assertThat(session.getAttribute("loginAdminId")).isEqualTo(1L);
        assertThat(session.getAttribute("loginMemberId")).isNull();
    }

    @Test
    void everyServiceTemplateIncludesHeaderAndFragmentContainsNoMapScripts() throws Exception {
        Path templates = Path.of("src/main/resources/templates");
        assertThat(Files.readString(templates.resolve("fragments/header.html")))
                .contains("th:fragment=\"header\"")
                .doesNotContain("<script", "sdk.js", "home-map.js", "home-survey-analysis.js");
        try (var paths = Files.walk(templates)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> !p.startsWith(templates.resolve("fragments"))).toList()) {
                assertThat(Files.readString(path)).as(path.toString())
                        .contains("th:replace=\"~{fragments/header :: header}\"");
            }
        }
    }
    @Test
    void landingEditorialScenesPreserveRealSpacesHeaderAndProtectedMonitoringLinks() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));
        String hero = html.substring(html.indexOf("<section"), html.indexOf("</section>"));
        String cta = html.substring(html.indexOf("<section class=\"landing-section landing-final-cta"), html.indexOf("</main>"));
        assertThat(header).contains("href=\"/admin/login\"", "href=\"/member/login\"", "href=\"/\"");
        assertThat(hero).contains("THERAPEUTIC GARDEN", "공간이", "SCROLL TO EXPLORE")
                .doesNotContain("/admin/login", "/member/login");
        assertThat(cta).contains("/images/landing/optimized/effect-hs3-cta.webp", "치유 공간의", "경험을 확인하세요.")
                .contains("href=\"/admin/monitoring\"", "공간 모니터링 보기")
                .doesNotContain("<button", "<nav", "/admin/login", "/member/login");
        String experience = html.substring(html.indexOf("<section class=\"landing-section landing-connection"),
                html.indexOf("<section class=\"landing-section landing-final-cta"));
        assertThat(experience).contains("/images/landing/optimized/personal-hs4.webp",
                "/images/landing/optimized/effect-hs2.webp", "/images/landing/optimized/personal-hs6.webp",
                "회복 코스", "감각 코스", "힐링 코스", "나의 상태에 맞춰,", "치유의 길을 구성합니다.")
                .doesNotContain("/images/landing/HS2_4.png", "/images/landing/HS4_3.jpg", "/images/landing/HS4_4.png");
        assertThat(html).contains("01 / HEALING EFFECT", "02 / MONITORING", "03 / PERSONAL HEALING COURSE",
                "PERSONAL HEALING COURSE", "THERAPEUTIC GARDEN MONITORING", "src=\"/js/landing.js\"")
                .doesNotContain("THERAPEUTIC SPACE", "01 / HEALING CHANGE", "04 / PERSONAL HEALING COURSE",
                        "<video", "<iframe", "<footer", "SIGBRAIN");
        assertThat(html.indexOf("landing-hero"))
                .isLessThan(html.indexOf("01 / HEALING EFFECT"));
        assertThat(html.indexOf("01 / HEALING EFFECT"))
                .isLessThan(html.indexOf("02 / MONITORING"));
        assertThat(html.indexOf("02 / MONITORING"))
                .isLessThan(html.indexOf("03 / PERSONAL HEALING COURSE"));
        assertThat(html).contains("id=\"healing-effect\"", "/images/landing/optimized/personal-change.webp",
                "/images/landing/optimized/site-plan.webp", "/images/landing/optimized/effect-hs1.webp",
                "/images/landing/optimized/effect-hs2.webp", "/images/landing/optimized/effect-hs3-cta.webp",
                "/images/landing/optimized/effect-hs4.webp", "/images/landing/optimized/effect-hs5.webp",
                "/images/landing/optimized/effect-hs6.webp",
                "data-course-id=\"HC-A\"", "data-course-id=\"HC-B\"", "data-course-id=\"HC-C\"",
                "data-field=\"stress-reduction\"", "data-field=\"emotional-increase\"",
                "데이터 변화", "데이터 준비 중");
        assertThat(html).contains("/images/landing/optimized/monitoring-background.webp",
                "/images/landing/optimized/personal-course-background.webp",
                "landing-effect-overview-hero", "landing-monitoring-hero", "landing-connection-hero",
                "landing-effect-overview-content", "landing-monitoring-content", "landing-connection-content",
                "PERSON / HEALING SPOT")
                .doesNotContain(".local/", "http://", "https://");
        String effect = html.substring(html.indexOf("<section id=\"healing-effect\""),
                html.indexOf("<section class=\"landing-section landing-monitoring"));
        assertThat(effect).contains("HS1 · 호스타 정원", "HS2 · 곶자왈원",
                "HS3 · 가든 위스퍼스", "HS4 · 콜로네이드 가든",
                "HS5 · 블로썸 가든", "HS6 · 극림원", "데이터 준비 중")
                .doesNotContain("landing-chart-line-stress", "landing-chart-line-emotional",
                        "data-chart-current-stress", "data-chart-current-emotional",
                        "Healing Course별 대표 공간과 평균 변화를 요약합니다.",
                        "한 참가자의 측정 전후 변화입니다.", "Sample · 예시 데이터",
                        "Sample Effect · 현재 수치는 서비스 설명을 위한 예시이며 다음 데이터 연동에서 교체됩니다.",
                        "개인 측정 값과 변화 흐름은 데이터 연동 전 예시입니다.");
        assertThat(hero).contains("/images/landing/optimized/hero-hs2.webp")
                .doesNotContain("HC-A", "HC-B", "HS1", "HS2</span>", "공간 구조 개념도");
        assertThat(html).contains("바이오마커", "뇌파", "맥파", "공간별 치유효과", "landing-spatial-visual",
                "데이터 준비 중", "개인 힐링코스 구성", "href=\"/css/landing.css\"")
                .doesNotContain(".codex-reference", "home-course-overlay.js", "id=\"siteSelect\"", "<canvas");
        assertThat(html).contains("몸이 보내는 신호,", "공간이 만드는 변화", "곶자왈원", "콜로네이드 가든",
                "블로썸 가든", "극림원", "데이터 준비 중")
                .doesNotContain("HRV", "Sample Data", "아래 수치는 설명을 위한 예시 데이터입니다.",
                        "머리에 착용하는 생체신호 측정 장비");
        assertThat(experience).contains("HS1 · 호스타 정원", "HS2 · 곶자왈원", "HS3 · 가든 위스퍼스",
                        "HS4 · 콜로네이드 가든", "HS5 · 블로썸 가든", "HS6 · 극림원")
                .doesNotContain("긴장 완화", "감각 환기", "정서 안정", "Sample Personal Healing Course", "오늘의 상태 · 예시");
        assertThat(experience.indexOf("HS4 ·")).isLessThan(experience.indexOf("HS5 ·"));
        assertThat(experience.indexOf("HS5 ·")).isLessThan(experience.indexOf("HS6 ·"));
        var images = java.util.regex.Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(html);
        int spacePhotos = 0;
        while (images.find()) {
            String source = images.group(1);
            assertThat(source).startsWith("/images/");
            assertThat(Files.isRegularFile(Path.of("src/main/resources/static" + source))).isTrue();
            if (source.startsWith("/images/landing/")) spacePhotos++;
        }
        assertThat(spacePhotos).isEqualTo(15);
        assertThat(html).contains("landing-image-scene", "landing-person-scene", "landing-course-journey",
                        "data-rotation-toggle", "aria-live=\"off\"")
                .doesNotContain("Sample Monitoring", "landing-waveform", "--level:");
        assertThat(html.indexOf("landing-image-scene")).isLessThan(html.indexOf("02 / MONITORING"));
        assertThat(html.indexOf("02 / MONITORING")).isLessThan(html.indexOf("landing-person-scene"));
        assertThat(html.indexOf("landing-person-scene")).isLessThan(html.indexOf("03 / PERSONAL HEALING COURSE"));
        var ids = java.util.regex.Pattern.compile("\\s+id=\"([^\"]+)\"").matcher(html);
        var uniqueIds = new java.util.HashSet<String>();
        while (ids.find()) assertThat(uniqueIds.add(ids.group(1))).as("unique id: " + ids.group(1)).isTrue();
        mvc.perform(get("/js/landing.js")).andExpect(status().isOk());
    }

    @Test
    void landingEnhancementIsIsolatedAndHasAccessibleFallbacks() throws Exception {
        Path resources = Path.of("src/main/resources");
        String js = Files.readString(resources.resolve("static/js/landing.js"));
        assertThat(js).contains("'IntersectionObserver' in window", "motion.matches", "showAll",
                "observer.disconnect()", "prefers-reduced-motion: reduce", "focusin",
                "initializeHealingSpotCarousels", "changeHealingSpot", "initializePersonalChangeAnimation",
                "data-personal-spot", "scheduleNext", "activeMetric", "8500");
        assertThat(Files.readString(resources.resolve("static/css/landing.css")))
                .contains("@media (prefers-reduced-motion: reduce)", "data-active-metric=\"stress\"",
                        "data-active-metric=\"emotional\"", "landing-effect-overview-background",
                        "landing-section-hero", "landing-section-content")
                .doesNotContain("landing-chart-line-stress", "landing-chart-line-emotional");
        assertThat(Files.readString(resources.resolve("static/css/style.css")))
                .contains("@media (prefers-reduced-motion: reduce)", ".landing-reveal-enabled .landing-page");
        try (var paths = Files.walk(resources.resolve("templates"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> !p.getFileName().toString().equals("landing.html")).toList()) {
                assertThat(Files.readString(path)).as(path.toString()).doesNotContain("landing.js", "landing.css");
            }
        }
    }

    @Test
    void staticAssetsUseContentVersionUrlsAndBareUrlsRevalidate() throws Exception {
        String versionedStyle = resourceUrlProvider.getForLookupPath("/css/style.css");
        assertThat(versionedStyle).containsPattern("/css/style-[0-9a-f]{32}\\.css");
        assertThat(resourceUrlProvider.getForLookupPath("/css/landing.css"))
                .containsPattern("/css/landing-[0-9a-f]{32}\\.css");
        assertThat(resourceUrlProvider.getForLookupPath("/js/landing.js"))
                .containsPattern("/js/landing-[0-9a-f]{32}\\.js");
        assertThat(resourceUrlProvider.getForLookupPath("/images/landing/optimized/personal-change.webp"))
                .containsPattern("/images/landing/optimized/personal-change-[0-9a-f]{32}\\.webp");
        assertThat(resourceUrlProvider.getForLookupPath("/images/landing/optimized/site-plan.webp"))
                .containsPattern("/images/landing/optimized/site-plan-[0-9a-f]{32}\\.webp");
        assertThat(resourceUrlProvider.getForLookupPath("/images/landing/optimized/effect-hs1.webp"))
                .containsPattern("/images/landing/optimized/effect-hs1-[0-9a-f]{32}\\.webp");
        assertThat(resourceUrlProvider.getForLookupPath("/images/landing/optimized/effect-hs6.webp"))
                .containsPattern("/images/landing/optimized/effect-hs6-[0-9a-f]{32}\\.webp");
        assertThat(resourceUrlProvider.getForLookupPath("/images/landing/optimized/monitoring-background.webp"))
                .containsPattern("/images/landing/optimized/monitoring-background-[0-9a-f]{32}\\.webp");
        assertThat(resourceUrlProvider.getForLookupPath("/images/landing/optimized/personal-course-background.webp"))
                .containsPattern("/images/landing/optimized/personal-course-background-[0-9a-f]{32}\\.webp");
        mvc.perform(get("/css/style.css")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
        mvc.perform(get(versionedStyle)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=31536000"),
                        org.hamcrest.Matchers.containsString("public"),
                        org.hamcrest.Matchers.containsString("immutable"))));
    }

}
