package com.example.manage;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.SessionAuthentication;
import jakarta.servlet.http.HttpSession;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Gives older MVC fixtures a real account and fingerprint for their synthetic login IDs. */
final class LegacyAuthenticatedSession {
    private static final AtomicInteger PARTICIPANT_NO = new AtomicInteger(1_900_000_000);

    private LegacyAuthenticatedSession() {}

    static RequestPostProcessor hydrate(WebApplicationContext context) {
        return request -> {
            HttpSession session = request.getSession(false);
            if (session == null) return request;
            SessionAuthentication authentication = context.getBean(SessionAuthentication.class);
            AdminRepository admins = context.getBean(AdminRepository.class);
            MemberRepository members = context.getBean(MemberRepository.class);
            Object adminId = session.getAttribute("loginAdminId");
            Object memberId = session.getAttribute("loginMemberId");
            if (adminId instanceof Long id && memberId == null && session.getAttribute("loginAdminFingerprint") == null) {
                Admin admin = admins.findById(id).orElseGet(() -> admins.saveAndFlush(
                        new Admin("legacy-test-" + UUID.randomUUID(), "test-only-hash")));
                authentication.authenticateAdmin(session, admin);
            } else if (memberId instanceof Long id && adminId == null
                    && session.getAttribute("loginMemberFingerprint") == null) {
                Member member = members.findById(id).orElseGet(() -> members.saveAndFlush(
                        new Member(PARTICIPANT_NO.incrementAndGet(), 1,
                                "legacy-test-" + UUID.randomUUID(), "test-only-hash")));
                authentication.authenticateMember(session, member);
            }
            return request;
        };
    }
}
