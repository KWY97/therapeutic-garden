package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
class LoginAttemptLimitIntegrationTests {
    private static final String PASSWORD = "LoginLimitPassword123!";
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(960_000_000);
    private static final String LIMITED = "로그인 시도가 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.";

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private Admin admin(String loginId) {
        return admins.saveAndFlush(new Admin(loginId, encoder.encode(PASSWORD)));
    }

    private Member member(String loginId) {
        return members.saveAndFlush(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                loginId, encoder.encode(PASSWORD), "제한 테스트", null));
    }

    @Test
    void normalAdminAndMemberLoginKeepSessionsAndProtectedPages() throws Exception {
        Admin admin = admin("limit-admin-" + UUID.randomUUID());
        Member member = member("limit-member-" + UUID.randomUUID());
        MockHttpSession adminSession = new MockHttpSession();
        MockHttpSession memberSession = new MockHttpSession();

        mvc.perform(post("/admin/login").session(adminSession).with(csrf())
                        .param("loginId", admin.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/admin/monitoring"));
        mvc.perform(post("/member/login").session(memberSession).with(csrf())
                        .param("loginId", member.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/member"));
        assertThat(adminSession.getAttribute("loginAdminFingerprint")).isInstanceOf(byte[].class);
        assertThat(memberSession.getAttribute("loginMemberFingerprint")).isInstanceOf(byte[].class);
        mvc.perform(get("/admin/password").session(adminSession)).andExpect(status().isOk());
        mvc.perform(get("/member/password").session(memberSession)).andExpect(status().isOk());
        mvc.perform(get("/admin/password").session(memberSession)).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void wrongPasswordsAndUnknownIdsUseTheSameTemporaryLimitWithoutAuthentication() throws Exception {
        Admin admin = admin("limit-admin-" + UUID.randomUUID());
        String absent = "absent-admin-" + UUID.randomUUID();
        for (String loginId : new String[]{admin.getLoginId(), absent}) {
            MockHttpSession session = new MockHttpSession();
            for (int attempt = 0; attempt < 5; attempt++) {
                mvc.perform(post("/admin/login").session(session).with(csrf())
                                .param("loginId", loginId).param("password", "wrong"))
                        .andExpect(status().isOk())
                        .andExpect(model().attribute("errorMessage", "아이디 또는 비밀번호가 올바르지 않습니다."));
            }
            mvc.perform(post("/admin/login").session(session).with(csrf())
                            .param("loginId", loginId).param("password", PASSWORD))
                    .andExpect(status().isOk()).andExpect(view().name("admin/login"))
                    .andExpect(model().attribute("errorMessage", LIMITED));
            assertThat(session.getAttribute("loginAdminId")).isNull();
            assertThat(session.getAttribute("loginAdminFingerprint")).isNull();
            mvc.perform(get("/admin/password").session(session)).andExpect(redirectedUrl("/admin/login"));
        }
    }

    @Test
    void memberLimiterSeparatesRolesAccountsAndIgnoresForwardedFor() throws Exception {
        String loginId = "shared-login-" + UUID.randomUUID();
        Admin admin = admin(loginId);
        Member member = member(loginId);
        Admin other = admin("other-admin-" + UUID.randomUUID());
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/admin/login").with(csrf())
                            .header("X-Forwarded-For", "198.51.100." + attempt)
                            .param("loginId", loginId).param("password", "wrong"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/admin/login").with(csrf())
                        .header("X-Forwarded-For", "203.0.113.1")
                        .param("loginId", loginId).param("password", PASSWORD))
                .andExpect(model().attribute("errorMessage", LIMITED));
        mvc.perform(post("/member/login").with(csrf())
                        .param("loginId", member.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/member"));
        mvc.perform(post("/admin/login").with(csrf())
                        .param("loginId", other.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/admin/monitoring"));
        assertThat(admin.getLoginId()).isEqualTo(member.getLoginId());
    }

    @Test
    void successfulLoginClearsEarlierFailuresAndCsrfRejectionDoesNotConsumeAttempts() throws Exception {
        Member member = member("limit-member-" + UUID.randomUUID());
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/member/login")
                            .param("loginId", member.getLoginId()).param("password", "wrong"))
                    .andExpect(status().isForbidden());
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(post("/member/login").with(csrf())
                            .param("loginId", member.getLoginId()).param("password", "wrong"))
                    .andExpect(model().attribute("errorMessage", "아이디 또는 비밀번호가 일치하지 않습니다."));
        }
        mvc.perform(post("/member/login").with(csrf())
                        .param("loginId", member.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/member"));
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/member/login").with(csrf())
                            .param("loginId", member.getLoginId()).param("password", "wrong"))
                    .andExpect(model().attribute("errorMessage", "아이디 또는 비밀번호가 일치하지 않습니다."));
        }
        mvc.perform(post("/member/login").with(csrf())
                        .param("loginId", member.getLoginId()).param("password", PASSWORD))
                .andExpect(model().attribute("errorMessage", LIMITED));
    }
}
