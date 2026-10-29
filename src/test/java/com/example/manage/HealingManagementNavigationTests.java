package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class HealingManagementNavigationTests {
    @Autowired WebApplicationContext context;
    @Autowired SiteService sites;
    @Autowired HealingCourseService courses;
    @Autowired HealingSpotService spots;
    MockMvc mvc;
    Site site;
    HealingCourse course;
    HealingSpot spot;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).build();
        site = sites.createSite("탐색 사이트", "주소", 37.0, 127.0, 3);
        course = courses.createHealingCourse(site, "HC-A", "회복 코스", null, null, 50.0);
        spot = spots.createHealingSpot(course, "HS1", "곶자왈원", 37.1, 127.1);
    }

    String page(String path) throws Exception {
        return mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    void nameLink(String html, String url, String name) {
        assertThat(html).contains("class=\"member-detail-link\" href=\"" + url + "\">" + name + "</a>");
    }

    void listLayout(String html, String title) {
        assertThat(html).contains("dashboard-container admin-list-container", "class=\"section-title\">" + title)
                .doesNotContain("상세보기", "class=\"card-header\"", "class=\"delete-form\"");
        assertThat(html.indexOf("<h1")).isLessThan(html.indexOf("<section class=\"card\""));
    }

    @Test
    void siteNameOpensDetailWithExistingCourseDestinationAndRenamedActions() throws Exception {
        String list = page("/admin/sites");
        listLayout(list, "Site 목록");
        assertThat(list).contains("<th>사이트명</th><th>주소</th>", "<td>주소</td>")
                .doesNotContain("<th>ID</th>", "<th>지도 레벨</th>");
        nameLink(list, "/admin/sites/" + site.getSiteId(), site.getName());
        assertThat(list).doesNotContain("<th>상세</th>", "/delete", "/edit");
        String detail = page("/admin/sites/" + site.getSiteId());
        assertThat(detail).contains("HC 관리", "Site 수정", "Site 삭제", "Site 목록으로",
                "href=\"/admin/courses?siteId=" + site.getSiteId() + "\"")
                .doesNotContain("HealingCourse 관리");
    }

    @Test
    void courseListOnlyLinksNamesAndDetailContainsManagementActions() throws Exception {
        String list = page("/admin/courses?siteId=" + site.getSiteId());
        listLayout(list, "HC 목록");
        assertThat(list).contains("<th>코스명</th><th>HC 코드</th><th>반경(m)</th>");
        nameLink(list, "/admin/courses/" + course.getCourseId(), course.getName());
        assertThat(list).contains(site.getName()).doesNotContain("HealingCourse 관리", "HS 관리", "/edit", "/delete");
        String detail = page("/admin/courses/" + course.getCourseId());
        assertThat(detail).contains("card member-detail-card", "member-detail-list", "site-detail-actions",
                "HC-A", "회복 코스", "탐색 사이트", "50.0 m", "HS 관리", "HC 수정", "HC 삭제", "HC 목록으로",
                "href=\"/admin/spots?courseId=" + course.getCourseId() + "&amp;siteId=" + site.getSiteId() + "\"",
                "href=\"/admin/courses/" + course.getCourseId() + "/edit\"",
                "action=\"/admin/courses/" + course.getCourseId() + "/delete\"", "method=\"post\"", "return confirm(",
                "href=\"/admin/courses?siteId=" + site.getSiteId() + "\"");
        nameLink(detail, "/admin/sites/" + site.getSiteId(), site.getName());
        page("/admin/courses/" + course.getCourseId() + "/edit");
    }

    @Test
    void spotListOnlyLinksNamesAndDetailContainsHierarchyAndActions() throws Exception {
        String list = page("/admin/spots?courseId=" + course.getCourseId());
        listLayout(list, "HS 목록");
        assertThat(list).contains("<th>스팟명</th><th>HS 코드</th>");
        nameLink(list, "/admin/spots/" + spot.getSpotId(), spot.getName());
        assertThat(list).contains("탐색 사이트 / HC-A 회복 코스")
                .doesNotContain("HealingSpot 관리", "/edit", "/delete");
        String detail = page("/admin/spots/" + spot.getSpotId());
        assertThat(detail).contains("card member-detail-card", "member-detail-list", "site-detail-actions",
                "HS1", "곶자왈원", "HS 수정", "HS 삭제", "HS 목록으로",
                "href=\"/admin/spots/" + spot.getSpotId() + "/edit\"",
                "action=\"/admin/spots/" + spot.getSpotId() + "/delete\"", "method=\"post\"", "return confirm(",
                "href=\"/admin/spots?courseId=" + course.getCourseId() + "\"")
                .doesNotContain("HS 관리", "37.1", "127.1");
        nameLink(detail, "/admin/courses/" + course.getCourseId(), "HC-A 회복 코스");
        nameLink(detail, "/admin/sites/" + site.getSiteId(), site.getName());
        page("/admin/spots/" + spot.getSpotId() + "/edit");
    }

    @Test
    void nullableCourseGeometryAndEmptyListsRender() throws Exception {
        courses.updateHealingCourse(course.getCourseId(), site.getSiteId(), "HC-A", "회복 코스", null, null, null);
        assertThat(page("/admin/courses/" + course.getCourseId())).contains("미설정").doesNotContain(">null<");
        assertThat(page("/admin/courses")).contains("HC 목록");
        spots.deleteHealingSpot(spot.getSpotId());
        assertThat(page("/admin/spots?courseId=" + course.getCourseId())).contains("등록된 HS가 없습니다.");
        courses.deleteHealingCourse(course.getCourseId());
        assertThat(page("/admin/courses?siteId=" + site.getSiteId())).contains("등록된 HC가 없습니다.");
    }

    @Test
    void missingDetailsAndUnauthenticatedAccessFollowExistingPolicy() throws Exception {
        for (String path : new String[]{"/admin/courses/-1", "/admin/spots/-1"}) {
            mvc.perform(get(path).sessionAttr("loginAdminId", 1L)).andExpect(redirectedUrl("/admin/courses"))
                    .andExpect(flash().attributeExists("errorMessage"));
        }
        for (String path : new String[]{"/admin/courses/" + course.getCourseId(), "/admin/spots/" + spot.getSpotId()}) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/admin/login"));
            mvc.perform(get(path).sessionAttr("loginMemberId", 1L)).andExpect(redirectedUrl("/admin/login"));
        }
    }
}
