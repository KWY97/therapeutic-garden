package com.example.manage.service;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
@RequiredArgsConstructor
public class SessionAuthentication {
    public enum Role { NONE, ADMIN, MEMBER }

    private static final String ADMIN_ID = "loginAdminId";
    private static final String MEMBER_ID = "loginMemberId";
    private static final String ADMIN_FINGERPRINT = "loginAdminFingerprint";
    private static final String MEMBER_FINGERPRINT = "loginMemberFingerprint";

    private final AdminRepository admins;
    private final MemberRepository members;

    public void authenticateAdmin(HttpSession session, Admin admin) {
        session.removeAttribute(MEMBER_ID);
        session.removeAttribute("loginMemberName");
        session.removeAttribute(MEMBER_FINGERPRINT);
        session.setAttribute(ADMIN_ID, admin.getAdminId());
        session.setAttribute(ADMIN_FINGERPRINT, fingerprint(admin.getPassword()));
    }

    public void authenticateMember(HttpSession session, Member member) {
        session.removeAttribute(ADMIN_ID);
        session.removeAttribute(ADMIN_FINGERPRINT);
        session.setAttribute(MEMBER_ID, member.getMemberId());
        session.setAttribute("loginMemberName", member.getName());
        session.setAttribute(MEMBER_FINGERPRINT, fingerprint(member.getPassword()));
    }

    public Role role(HttpSession session) {
        if (session == null) return Role.NONE;
        Object adminId = session.getAttribute(ADMIN_ID);
        Object memberId = session.getAttribute(MEMBER_ID);
        Object adminFingerprint = session.getAttribute(ADMIN_FINGERPRINT);
        Object memberFingerprint = session.getAttribute(MEMBER_FINGERPRINT);
        if (adminId == null && memberId == null && adminFingerprint == null && memberFingerprint == null) {
            return Role.NONE;
        }
        if (adminId instanceof Long id && memberId == null && memberFingerprint == null
                && matches(adminFingerprint, admins.findById(id).map(Admin::getPassword).orElse(null))) {
            return Role.ADMIN;
        }
        if (memberId instanceof Long id && adminId == null && adminFingerprint == null
                && matches(memberFingerprint, members.findById(id).map(Member::getPassword).orElse(null))) {
            return Role.MEMBER;
        }
        session.invalidate();
        return Role.NONE;
    }

    private boolean matches(Object storedFingerprint, String currentHash) {
        return storedFingerprint instanceof byte[] stored && stored.length == 32 && currentHash != null
                && MessageDigest.isEqual(stored, fingerprint(currentHash));
    }

    private byte[] fingerprint(String encodedPassword) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encodedPassword.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
