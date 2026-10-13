package com.example.manage.repository;

import com.example.manage.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

// Entity가 Member, PK는 Long
public interface MemberRepository extends JpaRepository<Member, Long> {

    // Serialize deletion with the one-time import's participant mapping locks.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.memberId = :id")
    Optional<Member> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

    // 로그인 시 사용
    Optional<Member> findByLoginId(String loginId);

    // 참가자 번호로 조회
    Optional<Member> findByParticipantNo(Integer participantNo);

    Optional<Member> findTopByTestAccountFalseOrderByParticipantNoDesc();

    List<Member> findByTestAccountFalse();

    boolean existsByAnalysisReferenceMemberId(Long analysisReferenceMemberId);
}
