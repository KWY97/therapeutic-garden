package com.example.manage;

import com.example.manage.domain.Member;
import com.example.manage.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminNavigationTests {
    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String header(MockHttpSession session) throws Exception {
        String path = session.getAttribute("loginAdminId") != null ? "/admin/monitoring"
                : session.getAttribute("loginMemberId") != null ? "/member" : "/";
        String html = mvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return html.substring(html.indexOf("<header"), html.indexOf("</header>"));
    }

    @Test
    void anonymousHomeRetainsLoginLinksAndBrand() throws Exception {
        assertThat(header(new MockHttpSession()))
                .contains("치유 공간 모니터링", "/images/logo/logo.png", "href=\"/admin/login\"", "href=\"/member/login\"")
                .doesNotContain("href=\"/admin\"", "href=\"/admin/logout\"");
        mvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void adminHomeHeaderUsesExistingLogoutAndReturnsToAnonymousState() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginAdminId", 1L);
        assertThat(header(session))
                .contains("치유 공간 모니터링", "/images/logo/logo.png", "href=\"/admin\"", "href=\"/admin/logout\"",
                        ">통합 설정</a>", "href=\"/admin/sites\"", "href=\"/admin/members\"",
                        "href=\"/admin/schedules\"", "href=\"/admin/schedules/calendar\"")
                .doesNotContain("관리자 페이지")
                .doesNotContain("href=\"/admin/login\"", "href=\"/member/login\"");
        mvc.perform(get("/admin/logout").session(session)).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(header(new MockHttpSession())).contains("href=\"/admin/login\"");
    }

    @Test
    void participantSessionDoesNotGainAdminNavigationOrCalendarAccess() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Member member = members.saveAndFlush(new Member(800_001, 1,
                "navigation-" + java.util.UUID.randomUUID(), "unused", "참가자", null));
        session.setAttribute("loginMemberId", member.getMemberId());
        assertThat(header(session)).contains("href=\"/member\"", "href=\"/member/logout\"")
                .doesNotContain("href=\"/admin\"", "href=\"/admin/logout\"", "href=\"/admin/login\"", "href=\"/member/login\"");
        mvc.perform(get("/admin/schedules/calendar").session(session))
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void adminHubAndCalendarRenderWithExistingDestinations() throws Exception {
        String hub = mvc.perform(get("/admin").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hub).contains("href=\"/admin/sites\"", "href=\"/admin/members\"",
                        "href=\"/admin/schedules\"", "href=\"/admin/schedules/calendar\"", "href=\"/admin/logout\"")
                .doesNotContain("href=\"/admin/courses\"");
        String calendar = mvc.perform(get("/admin/schedules/calendar").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(calendar).contains("ADMIN CALENDAR", "id=\"calendar\"", "id=\"schedule-data\"", "href=\"/admin\"",
                        "\"prev,next\"", "\"title\"", "\"today\"", "calendar-toolbar-grid")
                .doesNotContain("\"prev,next today\"", "dashboard-header", "dashboard-title", "dashboard-eyebrow");
    }

    @Test
    void topLevelListsRenderNavigationAndHeadingOutsideContent() throws Exception {
        for (String path : new String[]{"/admin/sites", "/admin/members", "/admin/schedules"}) {
            String html = mvc.perform(get(path).sessionAttr("loginAdminId", 1L))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("class=\"logout-link\" href=\"/admin\">관리자 메인</a>",
                            "class=\"section-label\">ADMINISTRATOR</p>")
                    .doesNotContain("관리자 메인으로", "class=\"card-header\"");
            assertThat(html.indexOf("</nav>")).isLessThan(html.indexOf("<h1"));
            assertThat(html.indexOf("</header>")).isLessThan(html.indexOf("<section"));
        }
        mvc.perform(get("/admin/schedules").param("sort", "group").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(model().attribute("sort", "group"));
    }

}
