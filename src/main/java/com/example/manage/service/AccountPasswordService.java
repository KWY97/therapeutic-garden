package com.example.manage.service;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AccountPasswordService {
    public enum Result {
        SUCCESS, ACCOUNT_NOT_FOUND, ADMIN_NOT_FOUND, INCORRECT_CURRENT, TOO_SHORT, TOO_LONG, CONFIRMATION_MISMATCH, REUSED
    }

    private final MemberRepository members;
    private final AdminRepository admins;
    private final PasswordEncoder encoder;

    @Transactional
    public Result changeMember(Long memberId, String current, String next, String confirmation) {
        Member member = memberId == null ? null : members.findLockedById(memberId).orElse(null);
        if (member == null) return Result.ACCOUNT_NOT_FOUND;
        if (current == null || !encoder.matches(current, member.getPassword())) return Result.INCORRECT_CURRENT;
        Result validation = validateNewPassword(member.getPassword(), next, confirmation, 8);
        if (validation != Result.SUCCESS) return validation;
        member.changePassword(encoder.encode(next));
        return Result.SUCCESS;
    }

    @Transactional(readOnly = true)
    public boolean adminExists(Long adminId) {
        return adminId != null && admins.existsById(adminId);
    }

    @Transactional
    public Result resetMemberByAdmin(Long adminId, Long memberId, String next, String confirmation) {
        if (adminId == null || admins.findLockedById(adminId).isEmpty()) return Result.ADMIN_NOT_FOUND;
        Member member = memberId == null ? null : members.findLockedById(memberId).orElse(null);
        if (member == null) return Result.ACCOUNT_NOT_FOUND;
        Result validation = validateNewPassword(member.getPassword(), next, confirmation, 8);
        if (validation != Result.SUCCESS) return validation;
        member.changePassword(encoder.encode(next));
        return Result.SUCCESS;
    }

    @Transactional
    public Result changeAdmin(Long adminId, String current, String next, String confirmation) {
        Admin admin = adminId == null ? null : admins.findLockedById(adminId).orElse(null);
        if (admin == null) return Result.ACCOUNT_NOT_FOUND;
        if (current == null || !encoder.matches(current, admin.getPassword())) return Result.INCORRECT_CURRENT;
        Result validation = validateNewPassword(admin.getPassword(), next, confirmation, 12);
        if (validation != Result.SUCCESS) return validation;
        admin.changePassword(encoder.encode(next));
        return Result.SUCCESS;
    }

    private Result validateNewPassword(String stored, String next, String confirmation, int minimumLength) {
        if (next == null || next.codePointCount(0, next.length()) < minimumLength) return Result.TOO_SHORT;
        if (next.getBytes(StandardCharsets.UTF_8).length > 72) return Result.TOO_LONG;
        if (!next.equals(confirmation)) return Result.CONFIRMATION_MISMATCH;
        if (encoder.matches(next, stored)) return Result.REUSED;
        return Result.SUCCESS;
    }
}
