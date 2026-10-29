package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.SessionAuthentication;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountPasswordChangeTests {
    private static final String CURRENT = "CurrentPass123!";
    private static final String NEXT = "ChangedPass123!";
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(900_000_000);

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired MemberRepository members;
    @Autowired AdminRepository admins;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthentication authentication;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private record Account(String role, Long id, String loginId, MockHttpSession session) {}

    private Account account(String role) {
        String loginId = role + "-password-" + UUID.randomUUID();
        Long id;
        if (role.equals("member")) {
            id = members.saveAndFlush(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                    loginId, encoder.encode(CURRENT), "테스트 참가자", null)).getMemberId();
        } else {
            id = admins.saveAndFlush(new Admin(loginId, encoder.encode(CURRENT))).getAdminId();
        }
        MockHttpSession session = new MockHttpSession();
        if (role.equals("member")) {
            authentication.authenticateMember(session, members.findById(id).orElseThrow());
        } else {
            authentication.authenticateAdmin(session, admins.findById(id).orElseThrow());
        }
        return new Account(role, id, loginId, session);
    }

    private MockHttpServletRequestBuilder change(Account account, String current, String next, String confirmation) {
        return post("/" + account.role() + "/password").session(account.session()).with(csrf())
                .param("currentPassword", current).param("newPassword", next).param("confirmPassword", confirmation);
    }

    private String stored(Account account) {
        return account.role().equals("member")
                ? members.findById(account.id()).orElseThrow().getPassword()
                : admins.findById(account.id()).orElseThrow().getPassword();
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "admin"})
    void successfulChangeInvalidatesSessionAndChangesLoginPassword(String role) throws Exception {
        Account account = account(role);
        String form = mvc.perform(get("/" + role + "/password").session(account.session()))
                .andExpect(status().isOk()).andExpect(view().name(role + "/password"))
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("name=\"_csrf\"", "name=\"currentPassword\"",
                "name=\"newPassword\"", "name=\"confirmPassword\"");
        assertThat(form).contains(role.equals("member") ? "8자 이상" : "12자 이상",
                "minlength=\"" + (role.equals("member") ? 8 : 12) + "\"");

        mvc.perform(change(account, CURRENT, NEXT, NEXT).param(role + "Id", "999999"))
                .andExpect(redirectedUrl("/" + role + "/login?passwordChanged=1"));
        assertThat(account.session().isInvalid()).isTrue();
        assertThat(encoder.matches(NEXT, stored(account))).isTrue();
        assertThat(encoder.matches(CURRENT, stored(account))).isFalse();

        mvc.perform(post("/" + role + "/login").with(csrf())
                        .param("loginId", account.loginId()).param("password", CURRENT))
                .andExpect(status().isOk()).andExpect(model().attributeExists("errorMessage"));
        mvc.perform(post("/" + role + "/login").with(csrf())
                        .param("loginId", account.loginId()).param("password", NEXT))
                .andExpect(redirectedUrl(role.equals("member") ? "/member" : "/admin/monitoring"));
        mvc.perform(get("/" + role + "/login?passwordChanged=1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("새 비밀번호로 다시 로그인")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "admin"})
    void rejectsWrongCurrentConfirmationLengthAndReuseWithoutEchoingSecrets(String role) throws Exception {
        Account account = account(role);
        String originalHash = stored(account);
        String[][] cases = {
                {"WrongCurrent123!", NEXT, NEXT, "현재 비밀번호가 올바르지"},
                {CURRENT, NEXT, "DifferentPass123!", "확인이 일치하지"},
                {CURRENT, role.equals("member") ? "Short12" : "Short123456",
                        role.equals("member") ? "Short12" : "Short123456",
                        role.equals("member") ? "8자 이상" : "12자 이상"},
                {CURRENT, "가".repeat(25), "가".repeat(25), "72바이트 이하"},
                {CURRENT, CURRENT, CURRENT, "다른 비밀번호"}
        };
        for (String[] testCase : cases) {
            String html = mvc.perform(change(account, testCase[0], testCase[1], testCase[2]))
                    .andExpect(status().isOk()).andExpect(view().name(role + "/password"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains(testCase[3]).doesNotContain(testCase[0], testCase[1], testCase[2]);
            assertThat(stored(account)).isEqualTo(originalHash);
            assertThat(account.session().isInvalid()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "admin"})
    void appliesRoleSpecificMinimumLengthBoundary(String role) throws Exception {
        Account account = account(role);
        int minimum = role.equals("member") ? 8 : 12;
        String originalHash = stored(account);
        String tooShort = "x".repeat(minimum - 1);
        String accepted = "y".repeat(minimum);

        mvc.perform(change(account, CURRENT, tooShort, tooShort))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(minimum + "자 이상")));
        assertThat(stored(account)).isEqualTo(originalHash);
        assertThat(account.session().isInvalid()).isFalse();

        mvc.perform(change(account, CURRENT, accepted, accepted))
                .andExpect(redirectedUrl("/" + role + "/login?passwordChanged=1"));
        assertThat(encoder.matches(accepted, stored(account))).isTrue();
        assertThat(account.session().isInvalid()).isTrue();
    }

    @Test
    void memberAndAdminAreasAreSeparateAndAnonymousRequestsCannotChangePasswords() throws Exception {
        Account member = account("member");
        Account admin = account("admin");
        for (String role : new String[]{"member", "admin"}) {
            mvc.perform(get("/" + role + "/password"))
                    .andExpect(redirectedUrl("/" + role + "/login"));
            mvc.perform(post("/" + role + "/password").with(csrf())
                            .param("currentPassword", CURRENT).param("newPassword", NEXT)
                            .param("confirmPassword", NEXT))
                    .andExpect(redirectedUrl("/" + role + "/login"));
        }
        mvc.perform(get("/admin/password").session(member.session())).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(change(member, CURRENT, NEXT, NEXT).session(admin.session()))
                .andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/member/password").session(admin.session())).andExpect(redirectedUrl("/member/login"));
        mvc.perform(change(admin, CURRENT, NEXT, NEXT).session(member.session()))
                .andExpect(redirectedUrl("/admin/login"));
        assertThat(encoder.matches(CURRENT, stored(member))).isTrue();
        assertThat(encoder.matches(CURRENT, stored(admin))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "admin"})
    void missingCsrfTokenIsForbidden(String role) throws Exception {
        Account account = account(role);
        mvc.perform(post("/" + role + "/password").session(account.session())
                        .param("currentPassword", CURRENT).param("newPassword", NEXT)
                        .param("confirmPassword", NEXT))
                .andExpect(status().isForbidden());
        assertThat(encoder.matches(CURRENT, stored(account))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "admin"})
    void acceptsExactlySeventyTwoUtf8Bytes(String role) throws Exception {
        Account account = account(role);
        String seventyTwoBytes = "가".repeat(24);
        mvc.perform(change(account, CURRENT, seventyTwoBytes, seventyTwoBytes))
                .andExpect(redirectedUrl("/" + role + "/login?passwordChanged=1"));
        assertThat(encoder.matches(seventyTwoBytes, stored(account))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "admin"})
    void deletedAccountInvalidatesStaleSession(String role) throws Exception {
        MockHttpSession stale = new MockHttpSession();
        stale.setAttribute(role.equals("member") ? "loginMemberId" : "loginAdminId", Long.MAX_VALUE);
        mvc.perform(post("/" + role + "/password").session(stale).with(csrf())
                        .param("currentPassword", CURRENT).param("newPassword", NEXT)
                        .param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/" + role + "/login"));
        assertThat(stale.isInvalid()).isTrue();
    }
}
