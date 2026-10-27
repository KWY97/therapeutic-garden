package com.example.manage.repository;

import com.example.manage.domain.Schedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    // memberId가 일치하는 참가자의 Schedule들을 scheduleDate 오름차순으로 조회한다.
    @EntityGraph(attributePaths = "member")
    List<Schedule> findByMemberMemberIdOrderByScheduleDateAsc(Long memberId);

    @EntityGraph(attributePaths = "member")
    Optional<Schedule> findByScheduleIdAndMemberMemberId(Long scheduleId, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.scheduleId = :scheduleId")
    Optional<Schedule> findLockedById(@Param("scheduleId") Long scheduleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.scheduleId = :scheduleId and s.member.memberId = :memberId")
    Optional<Schedule> findLockedByIdAndMemberId(@Param("scheduleId") Long scheduleId,
                                                 @Param("memberId") Long memberId);

    List<Schedule> findByMemberMemberIdAndScheduleDate(Long memberId, LocalDate scheduleDate);

    @Override
    @EntityGraph(attributePaths = "member")
    List<Schedule> findAll(Sort sort);
}
