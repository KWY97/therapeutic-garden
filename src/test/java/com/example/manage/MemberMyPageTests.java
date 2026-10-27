package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.dto.MemberProfileView;
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
class MemberMyPageTests {
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(910_000_000);
    private static final String PASSWORD = "ParticipantPass123!";

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

    private Member member(String name, String phone) {
        return members.saveAndFlush(new Member(PARTICIPANT_NO.incrementAndGet(), 2,
                "mypage-" + UUID.randomUUID(), encoder.encode(PASSWORD), name, phone));
    }

    @Test
    void loginShowsMyPageImmediatelyBeforeLogoutAndOwnInformationOnly() throws Exception {
        Member own = member("본인 참가자", "01012345678");
        Member other = member("다른 참가자", "01099998888");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/member/login").with(csrf()).session(session)
                        .param("loginId", own.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/member"));

        String home = mvc.perform(get("/member").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String header = home.substring(home.indexOf("<header"), home.indexOf("</header>"));
        assertThat(header).contains("href=\"/member/mypage\">마이페이지</a>", "href=\"/member/logout\">로그아웃</a>")
                .doesNotContain("/admin/password");
        assertThat(header.indexOf("/member/mypage")).isLessThan(header.indexOf("/member/logout"));
        assertThat(home).doesNotContain("account-action-row", "account-password-link");

        var result = mvc.perform(get("/member/mypage").session(session)
                        .param("memberId", other.getMemberId().toString()))
                .andExpect(status().isOk()).andExpect(view().name("member/mypage"))
                .andExpect(model().attributeExists("member"))
                .andReturn();
        assertThat(result.getModelAndView().getModel().get("member")).isInstanceOf(MemberProfileView.class);
        String page = result.getResponse().getContentAsString();
        assertThat(page).contains("MY PAGE", "마이페이지", "member-detail-list",
                "P" + own.getParticipantNo(), own.getLoginId(), "본인 참가자", "010-1234-5678",
                "href=\"/member/password\">비밀번호 변경</a>");
        assertThat(page).doesNotContain(other.getLoginId(), "다른 참가자", "010-9999-8888",
                own.getPassword(), "선택한 참가자의 등록 정보를 확인하고 관리합니다.",
                "참가자 정보 수정", "데이터 보기", "참가자 목록으로", "참가자 삭제", "/admin/members");
        mvc.perform(get("/member/password").session(session)).andExpect(status().isOk());
    }

    @Test
    void anonymousAndAdminCannotReadMyPageOrMemberManagement() throws Exception {
        Member member = member("보호 대상", null);
        Admin admin = admins.saveAndFlush(new Admin("mypage-admin-" + UUID.randomUUID(), encoder.encode(PASSWORD)));
        MockHttpSession adminSession = new MockHttpSession();
        mvc.perform(post("/admin/login").session(adminSession).with(csrf())
                        .param("loginId", admin.getLoginId()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/admin/monitoring"));
        mvc.perform(get("/member/mypage")).andExpect(redirectedUrl("/member/login"));
        mvc.perform(get("/member/mypage").session(adminSession)).andExpect(redirectedUrl("/member/login"));
        String adminHeader = mvc.perform(get("/admin/members/" + member.getMemberId()).session(adminSession))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-detail"))
                .andReturn().getResponse().getContentAsString();
        assertThat(adminHeader).contains("member-detail-list", "참가자 정보 수정", "데이터 보기",
                        "참가자 목록으로", "참가자 삭제", member.getLoginId())
                .doesNotContain("href=\"/member/mypage\"", "MY PAGE");

        MockHttpSession memberSession = new MockHttpSession();
        authentication.authenticateMember(memberSession, member);
        mvc.perform(get("/admin/members/" + member.getMemberId() + "/edit").session(memberSession))
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/members/" + member.getMemberId() + "/delete")
                        .session(memberSession).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        assertThat(members.findById(member.getMemberId())).isPresent();
    }

    @Test
    void missingMemberInvalidatesStaleSession() throws Exception {
        MockHttpSession stale = new MockHttpSession();
        stale.setAttribute("loginMemberId", Long.MAX_VALUE);
        mvc.perform(get("/member/mypage").session(stale)).andExpect(redirectedUrl("/member/login"));
        assertThat(stale.isInvalid()).isTrue();
    }
}
