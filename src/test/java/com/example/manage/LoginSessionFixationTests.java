package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:session-fixation-test;MODE=MySQL;NON_KEYWORDS=SEQUENCE;DB_CLOSE_DELAY=-1",
        "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
class LoginSessionFixationTests {
    private static final String PASSWORD = "SessionTestPassword123!";
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*>");
    private static final Pattern VALUE = Pattern.compile("value=\"([^\"]+)\"");
    private static final Pattern SESSION_COOKIE = Pattern.compile("(?:^|;\\s*)JSESSIONID=([^;]+)");

    @Value("${local.server.port}") int port;
    @Autowired AdminRepository admins;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;

    private HttpClient client;
    private Admin admin;
    private Member member;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String suffix = UUID.randomUUID().toString();
        admin = admins.saveAndFlush(new Admin("session-admin-" + suffix, encoder.encode(PASSWORD)));
        member = members.saveAndFlush(new Member(700_000_000 + Math.abs(suffix.hashCode() % 100_000_000),
                1, "session-member-" + suffix, encoder.encode(PASSWORD), "세션 테스트", null));
    }

    @AfterEach
    void cleanUp() {
        members.deleteById(member.getMemberId());
        admins.deleteById(admin.getAdminId());
    }

    @Test
    void adminLoginChangesSessionIdAndOldIdCannotAccessAdmin() throws Exception {
        LoginForm form = form("/admin/login", null);
        HttpResponse<String> login = login("/admin/login", form, admin.getLoginId(), PASSWORD);
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").map(value -> URI.create(value).getPath()))
                .hasValue("/admin/monitoring");
        String authenticatedId = sessionId(login);
        assertThat(authenticatedId).isNotEqualTo(form.sessionId());

        assertRedirect(get("/admin/monitoring", form.sessionId()), "/admin/login");
        assertThat(get("/admin/monitoring", authenticatedId).statusCode()).isEqualTo(200);
        assertRedirect(get("/member/mypage", authenticatedId), "/member/login");
        assertThat(postPassword("/admin/password", authenticatedId, form.csrf()).statusCode()).isEqualTo(200);

        assertRedirect(get("/admin/logout", authenticatedId), "/");
        assertRedirect(get("/admin/monitoring", authenticatedId), "/admin/login");
    }

    @Test
    void memberLoginChangesSessionIdAndOldIdCannotAccessMember() throws Exception {
        LoginForm form = form("/member/login", null);
        HttpResponse<String> login = login("/member/login", form, member.getLoginId(), PASSWORD);
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").map(value -> URI.create(value).getPath()))
                .hasValue("/member");
        String authenticatedId = sessionId(login);
        assertThat(authenticatedId).isNotEqualTo(form.sessionId());

        assertRedirect(get("/member/mypage", form.sessionId()), "/member/login");
        assertThat(get("/member/mypage", authenticatedId).statusCode()).isEqualTo(200);
        assertRedirect(get("/admin/monitoring", authenticatedId), "/admin/login");
        assertThat(postPassword("/member/password", authenticatedId, form.csrf()).statusCode()).isEqualTo(200);

        assertRedirect(get("/member/logout", authenticatedId), "/");
        assertRedirect(get("/member/mypage", authenticatedId), "/member/login");
    }

    @Test
    void failedLoginsDoNotAuthenticateTheExistingSession() throws Exception {
        for (String role : new String[]{"admin", "member"}) {
            LoginForm form = form("/" + role + "/login", null);
            String loginId = role.equals("admin") ? admin.getLoginId() : member.getLoginId();
            HttpResponse<String> failed = login("/" + role + "/login", form, loginId, "wrong-password");
            assertThat(failed.statusCode()).isEqualTo(200);
            assertRedirect(get(role.equals("admin") ? "/admin/monitoring" : "/member/mypage",
                    form.sessionId()), "/" + role + "/login");
        }
    }

    @Test
    void loginStillRequiresCsrfToken() throws Exception {
        for (String role : new String[]{"admin", "member"}) {
            LoginForm form = form("/" + role + "/login", null);
            String loginId = role.equals("admin") ? admin.getLoginId() : member.getLoginId();
            assertThat(postLogin("/" + role + "/login", form.sessionId(), loginId, PASSWORD, null)
                    .statusCode()).isEqualTo(403);
            assertThat(postLogin("/" + role + "/login", form.sessionId(), loginId, PASSWORD, "invalid")
                    .statusCode()).isEqualTo(403);
            assertRedirect(get(role.equals("admin") ? "/admin/monitoring" : "/member/mypage",
                    form.sessionId()), "/" + role + "/login");
        }
    }

    @Test
    void switchingFromMemberToAdminRotatesAgainAndRemovesMemberRole() throws Exception {
        LoginForm memberForm = form("/member/login", null);
        String memberId = sessionId(login("/member/login", memberForm, member.getLoginId(), PASSWORD));
        LoginForm adminForm = form("/admin/login", memberId);
        assertThat(adminForm.sessionId()).isEqualTo(memberId);

        String adminId = sessionId(login("/admin/login", adminForm, admin.getLoginId(), PASSWORD));
        assertThat(adminId).isNotEqualTo(memberId);
        assertRedirect(get("/member/mypage", memberId), "/member/login");
        assertRedirect(get("/member/mypage", adminId), "/member/login");
        assertThat(get("/admin/monitoring", adminId).statusCode()).isEqualTo(200);
    }

    private LoginForm form(String path, String existingId) throws Exception {
        HttpResponse<String> response = get(path, existingId);
        assertThat(response.statusCode()).isEqualTo(200);
        var input = CSRF_INPUT.matcher(response.body());
        assertThat(input.find()).isTrue();
        var value = VALUE.matcher(input.group());
        assertThat(value.find()).isTrue();
        return new LoginForm(existingId == null ? sessionId(response) : existingId, value.group(1));
    }

    private HttpResponse<String> login(String path, LoginForm form, String loginId, String password)
            throws Exception {
        return postLogin(path, form.sessionId(), loginId, password, form.csrf());
    }

    private HttpResponse<String> postLogin(String path, String id, String loginId, String password, String csrf)
            throws Exception {
        String body = "loginId=" + encode(loginId) + "&password=" + encode(password)
                + (csrf == null ? "" : "&_csrf=" + encode(csrf));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(cookie(request, id).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String id) throws Exception {
        return client.send(cookie(HttpRequest.newBuilder(uri(path)).GET(), id).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postPassword(String path, String id, String csrf) throws Exception {
        String body = "currentPassword=wrong&newPassword=" + encode(PASSWORD)
                + "&confirmPassword=" + encode(PASSWORD) + "&_csrf=" + encode(csrf);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(cookie(request, id).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder cookie(HttpRequest.Builder request, String id) {
        return id == null ? request : request.header("Cookie", "JSESSIONID=" + id);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String sessionId(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(SESSION_COOKIE::matcher).filter(java.util.regex.Matcher::find)
                .map(matcher -> matcher.group(1)).findFirst().orElseThrow();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void assertRedirect(HttpResponse<?> response, String location) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").map(value -> URI.create(value).getPath()))
                .hasValue(location);
    }

    private record LoginForm(String sessionId, String csrf) {}
}
