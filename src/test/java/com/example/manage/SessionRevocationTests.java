package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Member;
import com.example.manage.domain.Site;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.SiteRepository;
import com.example.manage.service.HealingSpotImageService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:session-revocation;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1",
        "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
class SessionRevocationTests {
    private static final String CURRENT = "CurrentPass123!";
    private static final String NEXT = "ChangedPass123!";
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(950_000_000);

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired MemberRepository members;
    @Autowired AdminRepository admins;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean HealingSpotImageService spotImages;

    private MockMvc mvc;
    private Member target;
    private Member other;
    private Admin admin;
    private Admin otherAdmin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        target = member();
        other = member();
        admin = admin();
        otherAdmin = admin();
    }

    private Member member() {
        return members.saveAndFlush(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                "revocation-member-" + UUID.randomUUID(), encoder.encode(CURRENT), "참가자", null));
    }

    private Admin admin() {
        return admins.saveAndFlush(new Admin("revocation-admin-" + UUID.randomUUID(), encoder.encode(CURRENT)));
    }

    private MockHttpSession loginMember(Member member) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/member/login").session(session).with(csrf())
                        .param("loginId", member.getLoginId()).param("password", CURRENT))
                .andExpect(redirectedUrl("/member"));
        assertThat(session.getAttribute("loginMemberFingerprint")).isInstanceOf(byte[].class);
        return session;
    }

    private MockHttpSession loginAdmin(Admin account) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/admin/login").session(session).with(csrf())
                        .param("loginId", account.getLoginId()).param("password", CURRENT))
                .andExpect(redirectedUrl("/admin/monitoring"));
        assertThat(session.getAttribute("loginAdminFingerprint")).isInstanceOf(byte[].class);
        return session;
    }

    @Test
    void adminResetRevokesEveryTargetSessionAndPreservesOthers() throws Exception {
        MockHttpSession first = loginMember(target);
        MockHttpSession second = loginMember(target);
        MockHttpSession unaffected = loginMember(other);
        MockHttpSession administrator = loginAdmin(admin);
        mvc.perform(get("/member/mypage").session(first)).andExpect(status().isOk());
        mvc.perform(get("/admin/monitoring").session(administrator)).andExpect(status().isOk());

        mvc.perform(post("/admin/members/" + target.getMemberId() + "/password")
                        .session(administrator).with(csrf())
                        .param("newPassword", NEXT).param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/admin/members/" + target.getMemberId() + "/edit"));

        for (MockHttpSession stale : new MockHttpSession[]{first, second}) {
            mvc.perform(get("/member/mypage").session(stale)).andExpect(redirectedUrl("/member/login"));
            assertThat(stale.isInvalid()).isTrue();
        }
        mvc.perform(get("/member/mypage").session(unaffected)).andExpect(status().isOk());
        mvc.perform(get("/admin/monitoring").session(administrator)).andExpect(status().isOk());
    }

    @Test
    void ownPasswordChangesRevokeOtherDevicesAndKeepOtherAdmin() throws Exception {
        MockHttpSession memberCurrent = loginMember(target);
        MockHttpSession memberOther = loginMember(target);
        mvc.perform(post("/member/password").session(memberCurrent).with(csrf())
                        .param("currentPassword", CURRENT).param("newPassword", NEXT)
                        .param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/member/login?passwordChanged=1"));
        assertThat(memberCurrent.isInvalid()).isTrue();
        mvc.perform(get("/member/api/spatial-layout").session(memberOther))
                .andExpect(status().isUnauthorized());
        assertThat(memberOther.isInvalid()).isTrue();

        MockHttpSession adminCurrent = loginAdmin(admin);
        MockHttpSession adminOther = loginAdmin(admin);
        MockHttpSession unaffected = loginAdmin(otherAdmin);
        mvc.perform(post("/admin/password").session(adminCurrent).with(csrf())
                        .param("currentPassword", CURRENT).param("newPassword", NEXT)
                        .param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/admin/login?passwordChanged=1"));
        assertThat(adminCurrent.isInvalid()).isTrue();
        mvc.perform(get("/admin/monitoring").session(adminOther)).andExpect(redirectedUrl("/admin/login"));
        assertThat(adminOther.isInvalid()).isTrue();
        mvc.perform(get("/admin/monitoring").session(unaffected)).andExpect(status().isOk());
    }

    @Test
    void deletedAccountsAndDirectHashChangeRevokeSessions() throws Exception {
        MockHttpSession memberSession = loginMember(target);
        MockHttpSession administrator = loginAdmin(admin);
        mvc.perform(post("/admin/members/" + target.getMemberId() + "/delete")
                        .session(administrator).with(csrf()))
                .andExpect(redirectedUrl("/admin/members"));
        assertThat(members.findById(target.getMemberId())).isEmpty();
        mvc.perform(get("/member/mypage").session(memberSession)).andExpect(redirectedUrl("/member/login"));
        assertThat(memberSession.isInvalid()).isTrue();

        MockHttpSession adminSession = loginAdmin(otherAdmin);
        admins.deleteById(otherAdmin.getAdminId());
        mvc.perform(get("/admin/monitoring").session(adminSession)).andExpect(redirectedUrl("/admin/login"));
        assertThat(adminSession.isInvalid()).isTrue();
        mvc.perform(get("/admin/monitoring").session(administrator)).andExpect(status().isOk());

        MockHttpSession otherSession = loginMember(other);
        transactions.executeWithoutResult(status -> members.findById(other.getMemberId()).orElseThrow()
                .changePassword(encoder.encode(NEXT)));
        mvc.perform(get("/member/mypage").session(otherSession)).andExpect(redirectedUrl("/member/login"));
        assertThat(otherSession.isInvalid()).isTrue();
    }

    @Test
    void missingFingerprintAndFailedOrRolledBackChangeDoNotGrantOrRevokeIncorrectly() throws Exception {
        MockHttpSession legacy = new MockHttpSession();
        legacy.setAttribute("loginMemberId", target.getMemberId());
        mvc.perform(get("/member/mypage").session(legacy)).andExpect(redirectedUrl("/member/login"));
        assertThat(legacy.isInvalid()).isTrue();
        MockHttpSession adminLegacy = new MockHttpSession();
        adminLegacy.setAttribute("loginAdminId", admin.getAdminId());
        mvc.perform(get("/admin/monitoring").session(adminLegacy)).andExpect(redirectedUrl("/admin/login"));
        assertThat(adminLegacy.isInvalid()).isTrue();

        MockHttpSession malformed = loginMember(other);
        malformed.setAttribute("loginMemberFingerprint", new byte[32]);
        mvc.perform(get("/member/mypage").session(malformed)).andExpect(redirectedUrl("/member/login"));
        assertThat(malformed.isInvalid()).isTrue();

        MockHttpSession valid = loginMember(target);
        mvc.perform(post("/member/password").session(valid).with(csrf())
                        .param("currentPassword", "wrong").param("newPassword", NEXT)
                        .param("confirmPassword", NEXT)).andExpect(status().isOk());
        mvc.perform(get("/member/mypage").session(valid)).andExpect(status().isOk());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            members.findById(target.getMemberId()).orElseThrow().changePassword(encoder.encode(NEXT));
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        mvc.perform(get("/member/mypage").session(valid)).andExpect(status().isOk());
    }

    @Test
    void failedAdminResetAndCsrfRejectionKeepExistingMemberSessions() throws Exception {
        MockHttpSession participant = loginMember(target);
        MockHttpSession administrator = loginAdmin(admin);
        String path = "/admin/members/" + target.getMemberId() + "/password";
        mvc.perform(post(path).session(administrator).with(csrf())
                        .param("newPassword", NEXT).param("confirmPassword", "different"))
                .andExpect(status().isOk());
        mvc.perform(post(path).session(administrator)
                        .param("newPassword", NEXT).param("confirmPassword", NEXT))
                .andExpect(status().isForbidden());
        mvc.perform(get("/member/mypage").session(participant)).andExpect(status().isOk());
        assertThat(participant.isInvalid()).isFalse();
    }

    @Test
    void publicRoutesIgnoreStaleAdminAndRoleSwitchAndLogoutStillWork() throws Exception {
        MockHttpSession administrator = loginAdmin(admin);
        Site site = sites.saveAndFlush(new Site("revocation-site-" + UUID.randomUUID(), "주소", 37.0, 127.0, 3));
        HealingCourse course = courses.saveAndFlush(new HealingCourse(site, "HC-REV", "코스", 37.0, 127.0, 30.0));
        HealingSpot spot = spots.saveAndFlush(new HealingSpot(course, "HS-REV", "정원", 37.0, 127.0));
        transactions.executeWithoutResult(status -> admins.findById(admin.getAdminId()).orElseThrow()
                .changePassword(encoder.encode(NEXT)));
        clearInvocations(spotImages);
        mvc.perform(get("/api/sites/" + site.getSiteId() + "/spots").session(administrator))
                .andExpect(status().isOk());
        verify(spotImages, never()).list(spot.getSpotId());
        assertThat(administrator.isInvalid()).isTrue();

        MockHttpSession staleLanding = loginAdmin(otherAdmin);
        transactions.executeWithoutResult(status -> admins.findById(otherAdmin.getAdminId()).orElseThrow()
                .changePassword(encoder.encode(NEXT)));
        mvc.perform(get("/").session(staleLanding)).andExpect(status().isOk())
                .andExpect(view().name("landing"));

        MockHttpSession memberSession = loginMember(target);
        mvc.perform(post("/admin/login").session(memberSession).with(csrf())
                        .param("loginId", admin.getLoginId()).param("password", NEXT))
                .andExpect(redirectedUrl("/admin/monitoring"));
        assertThat(memberSession.getAttribute("loginMemberId")).isNull();
        assertThat(memberSession.getAttribute("loginMemberFingerprint")).isNull();
        mvc.perform(get("/admin/monitoring").session(memberSession)).andExpect(status().isOk());
        mvc.perform(get("/member/mypage").session(memberSession)).andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/admin/logout").session(memberSession)).andExpect(redirectedUrl("/"));
        assertThat(memberSession.isInvalid()).isTrue();
    }
}
