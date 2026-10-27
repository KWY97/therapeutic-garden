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
class AdminMemberPasswordResetTests {
    private static final String CURRENT = "CurrentPass123!";
    private static final String NEXT = "ChangedPass123!";
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(920_000_000);

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired MemberRepository members;
    @Autowired AdminRepository admins;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private Member member() {
        return members.saveAndFlush(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                "reset-member-" + UUID.randomUUID(), encoder.encode(CURRENT), "대상 참가자", null));
    }

    private MockHttpSession adminSession() {
        Admin admin = admins.saveAndFlush(new Admin("reset-admin-" + UUID.randomUUID(), encoder.encode(CURRENT)));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginAdminId", admin.getAdminId());
        return session;
    }

    private String path(Member member) {
        return "/admin/members/" + member.getMemberId() + "/password";
    }

    private String stored(Member member) {
        return members.findById(member.getMemberId()).orElseThrow().getPassword();
    }

    @Test
    void adminResetsOnlyPathMemberAndNewPasswordWorksForLogin() throws Exception {
        Member target = member();
        Member other = member();
        MockHttpSession admin = adminSession();
        String originalOtherHash = stored(other);

        String edit = mvc.perform(get("/admin/members/" + target.getMemberId() + "/edit").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(edit).contains("href=\"" + path(target) + "\"", "비밀번호 변경");

        String form = mvc.perform(get(path(target)).session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-password-reset"))
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("P" + target.getParticipantNo(), target.getLoginId(),
                "name=\"_csrf\"", "name=\"newPassword\"", "name=\"confirmPassword\"",
                "href=\"/admin/members/" + target.getMemberId() + "/edit\"")
                .doesNotContain("currentPassword", "name=\"memberId\"", target.getPassword());

        mvc.perform(post(path(target)).session(admin).with(csrf())
                        .param("memberId", other.getMemberId().toString())
                        .param("newPassword", NEXT).param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/admin/members/" + target.getMemberId() + "/edit"))
                .andExpect(flash().attributeExists("successMessage"));
        assertThat(admin.isInvalid()).isFalse();
        assertThat(stored(target)).startsWith("$2");
        assertThat(encoder.matches(NEXT, stored(target))).isTrue();
        assertThat(encoder.matches(CURRENT, stored(target))).isFalse();
        assertThat(stored(other)).isEqualTo(originalOtherHash);

        mvc.perform(post("/member/login").with(csrf())
                        .param("loginId", target.getLoginId()).param("password", CURRENT))
                .andExpect(status().isOk()).andExpect(model().attributeExists("errorMessage"));
        mvc.perform(post("/member/login").with(csrf())
                        .param("loginId", target.getLoginId()).param("password", NEXT))
                .andExpect(redirectedUrl("/member"));
    }

    @Test
    void anonymousParticipantAndDeletedAdminCannotOpenOrSubmitReset() throws Exception {
        Member target = member();
        MockHttpSession participant = new MockHttpSession();
        participant.setAttribute("loginMemberId", target.getMemberId());
        MockHttpSession deletedAdmin = new MockHttpSession();
        deletedAdmin.setAttribute("loginAdminId", Long.MAX_VALUE);
        String originalHash = stored(target);

        for (MockHttpSession session : new MockHttpSession[]{new MockHttpSession(), participant}) {
            mvc.perform(get(path(target)).session(session)).andExpect(redirectedUrl("/admin/login"));
            mvc.perform(post(path(target)).session(session).with(csrf())
                            .param("newPassword", NEXT).param("confirmPassword", NEXT))
                    .andExpect(redirectedUrl("/admin/login"));
        }
        mvc.perform(get(path(target)).session(deletedAdmin)).andExpect(redirectedUrl("/admin/login"));
        assertThat(deletedAdmin.isInvalid()).isTrue();

        MockHttpSession deletedAdminForPost = new MockHttpSession();
        deletedAdminForPost.setAttribute("loginAdminId", Long.MAX_VALUE);
        mvc.perform(post(path(target)).session(deletedAdminForPost).with(csrf())
                        .param("newPassword", NEXT).param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/admin/login"));
        assertThat(deletedAdminForPost.isInvalid()).isTrue();
        assertThat(stored(target)).isEqualTo(originalHash);
    }

    @Test
    void missingMemberAndMissingCsrfDoNotChangePassword() throws Exception {
        Member target = member();
        MockHttpSession admin = adminSession();
        String originalHash = stored(target);
        String missingPath = "/admin/members/" + Long.MAX_VALUE + "/password";

        mvc.perform(get(missingPath).session(admin)).andExpect(redirectedUrl("/admin/members"));
        mvc.perform(post(missingPath).session(admin).with(csrf())
                        .param("newPassword", NEXT).param("confirmPassword", NEXT))
                .andExpect(redirectedUrl("/admin/members"));
        mvc.perform(post(path(target)).session(admin)
                        .param("newPassword", NEXT).param("confirmPassword", NEXT))
                .andExpect(status().isForbidden());
        assertThat(stored(target)).isEqualTo(originalHash);
    }

    @Test
    void invalidPasswordsLeaveHashUnchangedAndAreNeverEchoed() throws Exception {
        Member target = member();
        MockHttpSession admin = adminSession();
        String originalHash = stored(target);
        String[][] invalid = {
                {"Short12", "Short12", "8자 이상"},
                {"가".repeat(25), "가".repeat(25), "72바이트 이하"},
                {NEXT, "DifferentPass123!", "확인이 일치하지"},
                {CURRENT, CURRENT, "기존 비밀번호와 다른"}
        };
        for (String[] values : invalid) {
            String html = mvc.perform(post(path(target)).session(admin).with(csrf())
                            .param("newPassword", values[0]).param("confirmPassword", values[1]))
                    .andExpect(status().isOk()).andExpect(view().name("admin/member-password-reset"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains(values[2]).doesNotContain(values[0], values[1], originalHash);
            assertThat(stored(target)).isEqualTo(originalHash);
        }
    }

    @Test
    void participantProfileEditStillWorksWithoutChangingPassword() throws Exception {
        Member target = member();
        MockHttpSession admin = adminSession();
        String originalHash = stored(target);
        mvc.perform(post("/admin/members/" + target.getMemberId() + "/edit")
                        .session(admin).with(csrf())
                        .param("participantNo", target.getParticipantNo().toString())
                        .param("groupNo", "2")
                        .param("loginId", target.getLoginId())
                        .param("name", "수정된 참가자")
                        .param("phone", "010-1234-5678"))
                .andExpect(redirectedUrl("/admin/members/" + target.getMemberId()));
        Member updated = members.findById(target.getMemberId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("수정된 참가자");
        assertThat(updated.getPhone()).isEqualTo("01012345678");
        assertThat(stored(target)).isEqualTo(originalHash);
    }
}
