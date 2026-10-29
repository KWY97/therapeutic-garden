package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.SessionAuthentication;
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
class AdminMemberControllerTests {
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(970_000_000);

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthentication authentication;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private MockHttpSession adminSession() {
        Admin admin = admins.saveAndFlush(new Admin("member-controller-admin-" + UUID.randomUUID(), encoder.encode("secret")));
        MockHttpSession session = new MockHttpSession();
        authentication.authenticateAdmin(session, admin);
        return session;
    }

    private Member member() {
        return members.saveAndFlush(new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                "member-controller-" + UUID.randomUUID(), encoder.encode("secret"), "참가자", "01012345678"));
    }

    @Test
    void listDetailAndDataKeepViewsAndModelAttributes() throws Exception {
        Member member = member();
        MockHttpSession admin = adminSession();
        mvc.perform(get("/admin/members").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-list"))
                .andExpect(model().attributeExists("members"))
                .andExpect(model().attributeDoesNotExist("sites"));
        mvc.perform(get("/admin/members/" + member.getMemberId()).session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-detail"))
                .andExpect(model().attributeExists("member", "formattedPhone"))
                .andExpect(model().attributeDoesNotExist("sites"));
        mvc.perform(get("/admin/members/" + member.getMemberId() + "/data").session(admin))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-data"))
                .andExpect(model().attribute("analysisApiBase", "/admin/members/" + member.getMemberId() + "/data/api"));
        mvc.perform(get("/admin/members/" + Long.MAX_VALUE).session(admin))
                .andExpect(redirectedUrl("/admin/members"));
    }

    @Test
    void editValidationAndDeletionKeepExistingResponses() throws Exception {
        Member member = member();
        MockHttpSession admin = adminSession();
        String path = "/admin/members/" + member.getMemberId();
        mvc.perform(get(path + "/edit").session(admin))
                .andExpect(view().name("admin/member-edit"))
                .andExpect(model().attributeExists("memberEditForm", "memberId"))
                .andExpect(model().attributeDoesNotExist("sites"));
        mvc.perform(post(path + "/edit").session(admin).with(csrf()).param("loginId", " "))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-edit"))
                .andExpect(model().attributeHasErrors("memberEditForm"));
        assertThat(members.findById(member.getMemberId())).isPresent();
        mvc.perform(post(path + "/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/members"))
                .andExpect(flash().attribute("successMessage", "참가자를 삭제했습니다."));
        assertThat(members.findById(member.getMemberId())).isEmpty();
        mvc.perform(post(path + "/delete").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/members"))
                .andExpect(flash().attribute("successMessage", "참가자를 삭제했습니다."));
    }

    @Test
    void adminBoundaryAndCsrfStillProtectMemberChanges() throws Exception {
        Member member = member();
        String path = "/admin/members/" + member.getMemberId();
        MockHttpSession participant = new MockHttpSession();
        authentication.authenticateMember(participant, member);
        mvc.perform(get("/admin/members")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get(path).session(participant)).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post(path + "/delete").session(adminSession()))
                .andExpect(status().isForbidden());
        assertThat(members.findById(member.getMemberId())).isPresent();
    }
}
