package com.example.manage.service;

import com.example.manage.domain.Member;
import com.example.manage.domain.Schedule;
import com.example.manage.dto.MemberCreateForm;
import com.example.manage.dto.MemberProfileView;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.ScheduleRepository;
import com.example.manage.repository.ScheduleSpotRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberService {

    public static final class ReferencedMemberException extends RuntimeException {}

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginPasswordVerification loginPasswords;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleSpotRepository scheduleSpotRepository;
    private final com.example.manage.repository.MemberHealingSpotEffectSummaryRepository effectSummaries;

    /**
     * In-process concurrent registrations are serialized so they cannot select
     * the same next participant number. The database UNIQUE constraint remains
     * the final guard, including when multiple application instances are used.
     */
    public synchronized Member createMember(MemberCreateForm form) {
        if (memberRepository.findByLoginId(form.getLoginId()).isPresent()) {
            return null;
        }

        int participantNo = memberRepository.findTopByTestAccountFalseOrderByParticipantNoDesc()
                .map(member -> Math.addExact(member.getParticipantNo(), 1))
                .orElse(1);
        String encodedPassword = passwordEncoder.encode(form.getPassword());
        String phone = normalizePhone(form.getPhone());
        Member member = new Member(participantNo, form.getGroupNo(), form.getLoginId(),
                encodedPassword, form.getName(), phone);
        return memberRepository.save(member);
    }

    private String normalizePhone(String phone) {
        return phone == null ? null : phone.replace("-", "");
    }

    @Transactional(readOnly = true)
    public Member login(String loginId, String rawPassword) {

        Member member = memberRepository.findByLoginId(loginId).orElse(null);

        if (!loginPasswords.matches(rawPassword, member == null ? null : member.getPassword())) {
            return null;
        }

        return member;
    }

    @Transactional(readOnly = true)
    public List<Member> findAllMembers() {
        return memberRepository.findByTestAccountFalse();
    }

    @Transactional(readOnly = true)
    public Member findMember(Long memberId) {
        return memberRepository.findById(memberId).orElse(null);
    }

    @Transactional(readOnly = true)
    public MemberProfileView findProfile(Long memberId) {
        return memberRepository.findById(memberId).map(MemberProfileView::new).orElse(null);
    }

    public String updateMember(
            Long memberId,
            Integer participantNo,
            Integer groupNo,
            String loginId,
            String name,
            String phone
    ) {

        /*
         * 수정할 참가자 조회
         */
        Member member =
                memberRepository.findById(memberId)
                        .orElse(null);

        if (member == null) {
            return "MEMBER_NOT_FOUND";
        }

        /*
         * 참가자 번호 중복 확인
         * 같은 참가자 번호를 사용하는 Member가 존재하면서,
         * 그 Member가 현재 수정 중인 본인이 아니라면 수정하지 않는다.
         */
        Member participantNoMember =
                memberRepository.findByParticipantNo(participantNo)
                        .orElse(null);

        if (participantNoMember != null
                && !participantNoMember.getMemberId().equals(memberId)) {
            return "PARTICIPANT_NO_DUPLICATE";
        }

        /*
         * 로그인 아이디 중복 확인
         * 같은 로그인 아이디를 사용하는 Member가 존재하면서,
         * 그 Member가 현재 수정 중인 본인이 아니라면 수정하지 않는다.
         */
        Member loginIdMember =
                memberRepository.findByLoginId(loginId)
                        .orElse(null);

        if (loginIdMember != null
                && !loginIdMember.getMemberId().equals(memberId)) {
            return "LOGIN_ID_DUPLICATE";
        }

        /*
         * 전화번호 형식 통일
         *
         * 관리자가
         * 010-1234-5678
         * 형식으로 입력해도
         *
         * DB에는
         * 01012345678
         * 형식으로 저장한다.
         */
        phone = normalizePhone(phone);

        /*
         * 참가자 정보 수정
         * MemberService 클래스에 @Transactional이 적용되어 있으므로
         * 트랜잭션 종료 시 JPA 변경 감지로 UPDATE SQL이 실행된다.
         */
        member.update(
                participantNo,
                groupNo,
                loginId,
                name,
                phone
        );

        return "SUCCESS";
    }

    public void deleteMember(Long memberId) {

        Member member = memberRepository.findLockedById(memberId).orElse(null);

        if (member == null) {
            return;
        }

        if (memberRepository.existsByAnalysisReferenceMemberId(memberId)) {
            throw new ReferencedMemberException();
        }

        List<Schedule> schedules = scheduleRepository.findByMemberMemberIdOrderByScheduleDateAsc(memberId);

        for (Schedule schedule : schedules) {

            scheduleSpotRepository.deleteByScheduleScheduleId(schedule.getScheduleId());
        }

        scheduleRepository.deleteAll(schedules);

        effectSummaries.deleteByMemberMemberId(memberId);
        effectSummaries.flush();
        memberRepository.delete(member);
    }
}
