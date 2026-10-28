package com.example.manage.service;

import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Member;
import com.example.manage.domain.Schedule;
import com.example.manage.domain.ScheduleSpot;
import com.example.manage.dto.MemberScheduleDetailResponse;
import com.example.manage.dto.ScheduleResponse;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.repository.ScheduleRepository;
import com.example.manage.repository.ScheduleSpotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ScheduleService {

    private final HealingSpotRepository healingSpotRepository;
    private final MemberRepository memberRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleSpotRepository scheduleSpotRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void createSchedule(
            Long memberId,
            LocalDate scheduleDate,
            Long siteId,
            Long courseId,
            Long firstSpotId,
            LocalTime firstStartTime,
            Long secondSpotId,
            LocalTime secondStartTime,
            String weather,
            Double temperature,
            Double humidity
    ) {

        Member member =
                memberRepository.findLockedById(memberId)
                        .orElse(null);

        if (member == null) {
            return;
        }
        if (member.isTestAccount())
            throw new IllegalArgumentException("테스트 계정에는 일정을 등록할 수 없습니다.");

        HealingSpot firstHealingSpot = findSelectedSpot(firstSpotId, siteId, courseId);
        HealingSpot secondHealingSpot = findSelectedSpot(secondSpotId, siteId, courseId);
        rejectDuplicate(null, memberId, scheduleDate, firstSpotId, firstStartTime,
                secondSpotId, secondStartTime);

        /*
         * 일정 생성
         */
        Schedule schedule = new Schedule(
                member,
                scheduleDate
        );

        /*
         * 날씨 정보 설정
         */
        schedule.updateWeather(
                weather,
                temperature,
                humidity
        );

        scheduleRepository.save(schedule);

        /*
         * 첫 번째 스팟
         */
        ScheduleSpot firstSpot = new ScheduleSpot(
                schedule,
                firstHealingSpot,
                firstStartTime,
                1
        );

        /*
         * 두 번째 스팟
         */
        ScheduleSpot secondSpot = new ScheduleSpot(
                schedule,
                secondHealingSpot,
                secondStartTime,
                2
        );

        scheduleSpotRepository.save(firstSpot);
        scheduleSpotRepository.save(secondSpot);
    }

    @Transactional(readOnly = true)
    public List<Schedule> findAllSchedules() {

        return scheduleRepository.findAllForAdminCalendar();
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> findAllSchedules(String sort) {

        Sort scheduleSort;

        if ("participant".equals(sort)) {

            /*
             * 참가자 번호 오름차순
             *
             * 같은 참가자라면
             * 일정 날짜 오름차순
             */
            scheduleSort = Sort.by(
                    Sort.Order.asc("member.participantNo"),
                    Sort.Order.asc("scheduleDate")
            );

        } else {
            /*
             * 기본값
             *
             * 일정 날짜 오름차순
             *
             * 같은 날짜라면
             * 참가자 번호 오름차순
             */
            scheduleSort = Sort.by(
                    Sort.Order.asc("scheduleDate"),
                    Sort.Order.asc("member.participantNo")
            );
        }

        return toResponses(scheduleRepository.findAll(scheduleSort));
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> findScheduleByMemberId(Long memberId) {

        return toResponses(scheduleRepository
                .findByMemberMemberIdOrderByScheduleDateAsc(memberId));
    }

    @Transactional(readOnly = true)
    public Schedule findSchedule(Long scheduleId) {

        return scheduleRepository
                .findById(scheduleId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ScheduleSpot> findScheduleSpots(Long scheduleId) {

        return scheduleSpotRepository
                .findByScheduleScheduleIdOrderBySequenceAsc(scheduleId);
    }

    @Transactional(readOnly = true)
    public MemberScheduleDetailResponse findMemberScheduleDetail(
            Long scheduleId,
            Long memberId
    ) {
        ScheduleResponse response = findMemberScheduleResponse(scheduleId, memberId);
        if (response == null)
            return null;
        return new MemberScheduleDetailResponse(
                response.getScheduleId(), response.getScheduleDate(),
                response.getSiteId(), response.getSiteName(), response.getCourseId(),
                response.getCourseCode(), response.getCourseName(),
                response.getMember().getGroupNo(), response.getWeather(), response.getSpots()
        );
    }

    @Transactional(readOnly = true)
    public ScheduleResponse findMemberScheduleResponse(Long scheduleId, Long memberId) {
        Schedule schedule = scheduleRepository.findByScheduleIdAndMemberMemberId(scheduleId, memberId)
                .orElse(null);
        return schedule == null ? null : new ScheduleResponse(schedule, findScheduleSpots(scheduleId));
    }

    @Transactional(readOnly = true)
    public ScheduleResponse findScheduleResponse(Long scheduleId) {
        Schedule schedule = findSchedule(scheduleId);
        return schedule == null ? null : new ScheduleResponse(schedule, findScheduleSpots(scheduleId));
    }

    private List<ScheduleResponse> toResponses(List<Schedule> schedules) {
        if (schedules.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ScheduleSpot>> spotsBySchedule = scheduleSpotRepository
                .findByScheduleScheduleIdInOrderBySequenceAsc(
                        schedules.stream().map(Schedule::getScheduleId).toList())
                .stream().collect(Collectors.groupingBy(spot -> spot.getSchedule().getScheduleId()));
        return schedules.stream().map(schedule -> new ScheduleResponse(schedule,
                spotsBySchedule.getOrDefault(schedule.getScheduleId(), List.of()))).toList();
    }

    private HealingSpot findSelectedSpot(Long spotId, Long siteId, Long courseId) {
        if (spotId == null || siteId == null || courseId == null) {
            throw new IllegalArgumentException("사이트, 코스와 스팟을 선택해 주세요.");
        }
        HealingSpot spot = healingSpotRepository.findById(spotId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스팟입니다."));
        if (!spot.getHealingCourse().getCourseId().equals(courseId)
                || !spot.getHealingCourse().getSite().getSiteId().equals(siteId)) {
            throw new IllegalArgumentException("선택한 사이트와 코스에 속한 스팟을 선택해 주세요.");
        }
        return spot;
    }

    private void rejectDuplicate(Long editingScheduleId, Long memberId, LocalDate scheduleDate,
            Long firstSpotId, LocalTime firstStartTime,
            Long secondSpotId, LocalTime secondStartTime) {
        List<Schedule> sameDay = scheduleRepository.findByMemberMemberIdAndScheduleDate(memberId, scheduleDate)
                .stream().filter(schedule -> !schedule.getScheduleId().equals(editingScheduleId)).toList();
        if (sameDay.isEmpty())
            return;

        Map<Long, List<ScheduleSpot>> spotsBySchedule = scheduleSpotRepository
                .findByScheduleScheduleIdInOrderBySequenceAsc(
                        sameDay.stream().map(Schedule::getScheduleId).toList())
                .stream().collect(Collectors.groupingBy(spot -> spot.getSchedule().getScheduleId()));

        for (Schedule schedule : sameDay) {
            List<ScheduleSpot> spots = spotsBySchedule.getOrDefault(schedule.getScheduleId(), List.of());
            if (spots.size() == 2
                    && sameVisit(spots.get(0), 1, firstSpotId, firstStartTime)
                    && sameVisit(spots.get(1), 2, secondSpotId, secondStartTime)) {
                throw new DuplicateScheduleException();
            }
        }
    }

    private boolean sameVisit(ScheduleSpot spot, int sequence, Long spotId, LocalTime startTime) {
        return spot.getSequence() == sequence
                && spot.getHealingSpot().getSpotId().equals(spotId)
                && spot.getStartTime().equals(startTime);
    }

    public static class DuplicateScheduleException extends IllegalArgumentException {
        public DuplicateScheduleException() {
            super("같은 참가자에게 동일한 날짜, 코스, 스팟 방문 순서와 시작 시간의 일정이 이미 등록되어 있습니다.");
        }
    }

    public static class MemberScheduleNotFoundException extends RuntimeException {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateSchedule(
            Long scheduleId,
            Long memberId,
            LocalDate scheduleDate,
            Long siteId,
            Long courseId,
            Long firstSpotId,
            LocalTime firstStartTime,
            Long secondSpotId,
            LocalTime secondStartTime,
            String weather,
            Double temperature,
            Double humidity
    ) {

        Member member = memberRepository.findLockedById(memberId).orElse(null);
        if (member == null)
            return;

        /*
         * 수정할 일정 조회
         */
        Schedule schedule =
                scheduleRepository.findLockedById(scheduleId)
                        .orElse(null);

        if (schedule == null) {
            return;
        }

        applyScheduleUpdate(schedule, member, scheduleDate, siteId, courseId,
                firstSpotId, firstStartTime, secondSpotId, secondStartTime,
                weather, temperature, humidity, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateMemberSchedule(Long scheduleId, Long memberId,
            LocalDate scheduleDate, Long siteId, Long courseId,
            Long firstSpotId, LocalTime firstStartTime,
            Long secondSpotId, LocalTime secondStartTime,
            String weather, Double temperature, Double humidity) {
        Member member = memberRepository.findLockedById(memberId)
                .orElseThrow(MemberScheduleNotFoundException::new);
        Schedule schedule = scheduleRepository.findLockedByIdAndMemberId(scheduleId, memberId)
                .orElseThrow(MemberScheduleNotFoundException::new);
        applyScheduleUpdate(schedule, member, scheduleDate, siteId, courseId,
                firstSpotId, firstStartTime, secondSpotId, secondStartTime,
                weather, temperature, humidity, false);
    }

    private void applyScheduleUpdate(Schedule schedule, Member member, LocalDate scheduleDate,
            Long siteId, Long courseId, Long firstSpotId,
            LocalTime firstStartTime, Long secondSpotId,
            LocalTime secondStartTime, String weather,
            Double temperature, Double humidity, boolean reassignMember) {
        HealingSpot firstHealingSpot = findSelectedSpot(firstSpotId, siteId, courseId);
        HealingSpot secondHealingSpot = findSelectedSpot(secondSpotId, siteId, courseId);

        if (member.isTestAccount())
            throw new IllegalArgumentException("테스트 계정에는 일정을 등록할 수 없습니다.");

        /*
         * 기존 ScheduleSpot 조회
         *
         * sequence 오름차순이므로
         * 0번 = 첫 번째 스팟
         * 1번 = 두 번째 스팟
         */
        List<ScheduleSpot> scheduleSpots =
                scheduleSpotRepository
                        .findByScheduleScheduleIdOrderBySequenceAsc(
                                schedule.getScheduleId()
                        );

        if (scheduleSpots.size() != 2
                || scheduleSpots.get(0).getSequence() != 1
                || scheduleSpots.get(1).getSequence() != 2) {
            throw new IllegalArgumentException("일정에는 순서가 지정된 두 개의 스팟이 필요합니다.");
        }

        rejectDuplicate(schedule.getScheduleId(), member.getMemberId(), scheduleDate, firstSpotId, firstStartTime,
                secondSpotId, secondStartTime);

        /*
         * 일정 기본 정보 수정
         */
        if (reassignMember)
            schedule.update(member, scheduleDate);
        else
            schedule.updateDate(scheduleDate);

        /*
         * 날씨 정보 수정
         */
        schedule.updateWeather(
                weather,
                temperature,
                humidity
        );


        /*
         * 첫 번째 스팟 수정
         */
        if (scheduleSpots.size() >= 1) {

            ScheduleSpot firstSpot =
                    scheduleSpots.get(0);

            firstSpot.update(
                    firstHealingSpot,
                    firstStartTime
            );
        }


        /*
         * 두 번째 스팟 수정
         */
        if (scheduleSpots.size() >= 2) {

            ScheduleSpot secondSpot =
                    scheduleSpots.get(1);

            secondSpot.update(
                    secondHealingSpot,
                    secondStartTime
            );
        }
    }

    public void deleteSchedule(Long scheduleId) {

        Schedule schedule =
                scheduleRepository.findLockedById(scheduleId)
                        .orElse(null);

        if (schedule == null) {
            return;
        }

        deleteScheduleAndSpots(schedule);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteMemberSchedule(Long scheduleId, Long memberId) {
        memberRepository.findLockedById(memberId)
                .orElseThrow(MemberScheduleNotFoundException::new);
        Schedule schedule = scheduleRepository.findLockedByIdAndMemberId(scheduleId, memberId)
                .orElseThrow(MemberScheduleNotFoundException::new);
        deleteScheduleAndSpots(schedule);
    }

    private void deleteScheduleAndSpots(Schedule schedule) {
        scheduleSpotRepository.deleteByScheduleScheduleId(schedule.getScheduleId());
        scheduleRepository.delete(schedule);
    }
}
