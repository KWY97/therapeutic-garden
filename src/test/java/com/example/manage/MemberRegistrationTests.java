package com.example.manage;

import com.example.manage.domain.Member;
import com.example.manage.dto.MemberCreateForm;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemberRegistrationTests {

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberService memberService;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(LegacyAuthenticatedSession.hydrate(context))).build();
    }

    @Test
    void createsMemberWithNextNumberAndEncodedPasswordWithoutChangingExistingMembers() {
        Member existing = members.save(new Member(27, 1, "registration-existing", encoder.encode("old-secret")));
        MemberCreateForm form = form("registration-new", "initial-secret");
        form.setPhone("010-1234-5678");

        Member created = memberService.createMember(form);

        assertThat(created.getParticipantNo()).isEqualTo(28);
        assertThat(created.getGroupNo()).isEqualTo(2);
        assertThat(created.getPhone()).isEqualTo("01012345678");
        assertThat(created.getPassword()).isNotEqualTo("initial-secret");
        assertThat(encoder.matches("initial-secret", created.getPassword())).isTrue();
        assertThat(memberService.login("registration-new", "initial-secret")).isNotNull();
        assertThat(members.findById(existing.getMemberId()).orElseThrow().getParticipantNo()).isEqualTo(27);
        assertThat(encoder.matches("old-secret", members.findById(existing.getMemberId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void startsParticipantNumbersAtOneWhenNoMembersExist() {
        Member created = memberService.createMember(form("registration-first", "initial-secret"));
        assertThat(created.getParticipantNo()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateLoginIdBeforeSaving() {
        members.save(new Member(6, 1, "registration-duplicate", encoder.encode("old-secret")));
        assertThat(memberService.createMember(form("registration-duplicate", "initial-secret"))).isNull();
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void registrationPageIsAdminProtectedAndRendersForm() throws Exception {
        mvc.perform(get("/admin/members/new")).andExpect(redirectedUrl("/admin/login"));
        String html = mvc.perform(get("/admin/members/new").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-create"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/admin/members", "id=\"groupNo\"", "id=\"loginId\"",
                "id=\"password\"", "id=\"name\"", "id=\"phone\"")
                .doesNotContain("participantNo");
    }

    @Test
    void postCreatesMemberAndRedirectsToDetail() throws Exception {
        MockHttpSession session = adminSession();
        var result = mvc.perform(post("/admin/members").session(session)
                        .param("groupNo", "3").param("loginId", "registration-controller")
                        .param("password", "initial-secret").param("name", "테스트 참가자")
                        .param("phone", "010-1111-2222"))
                .andExpect(status().is3xxRedirection()).andReturn();
        Member member = members.findByLoginId("registration-controller").orElseThrow();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin/members/" + member.getMemberId());
        assertThat(member.getParticipantNo()).isEqualTo(1);
        assertThat(encoder.matches("initial-secret", member.getPassword())).isTrue();
        mvc.perform(get(result.getResponse().getRedirectedUrl()).session(session))
                .andExpect(status().isOk()).andExpect(model().attributeExists("successMessage"));
    }

    @Test
    void postValidationFailureReturnsFormWithoutEchoingPassword() throws Exception {
        String html = mvc.perform(post("/admin/members").session(adminSession())
                        .param("groupNo", "9").param("loginId", " ").param("password", " "))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-create"))
                .andExpect(model().attributeHasFieldErrors("memberCreateForm", "groupNo", "loginId", "password"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("그룹은 3 이하여야 합니다.", "로그인 아이디를 입력해 주세요.", "초기 비밀번호를 입력해 주세요.")
                .containsPattern("<input[^>]*id=\"password\"[^>]*value=\"\"");
        assertThat(members.count()).isZero();
    }

    @Test
    void postDuplicateLoginIdShowsFieldError() throws Exception {
        members.save(new Member(1, 1, "registration-duplicate-post", encoder.encode("old-secret")));
        String html = mvc.perform(post("/admin/members").session(adminSession())
                        .param("groupNo", "1").param("loginId", "registration-duplicate-post")
                        .param("password", "initial-secret"))
                .andExpect(status().isOk()).andExpect(view().name("admin/member-create"))
                .andExpect(model().attributeHasFieldErrors("memberCreateForm", "loginId"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("이미 사용 중인 로그인 아이디입니다.")
                .doesNotContain("initial-secret");
        assertThat(members.count()).isEqualTo(1);
    }

    private MemberCreateForm form(String loginId, String password) {
        MemberCreateForm form = new MemberCreateForm();
        form.setGroupNo(2);
        form.setLoginId(loginId);
        form.setPassword(password);
        return form;
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginAdminId", 1L);
        return session;
    }
}
