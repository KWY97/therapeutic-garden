package com.example.manage;

import com.example.manage.domain.Member;
import com.example.manage.repository.HealingEffectImportBatchRepository;
import com.example.manage.repository.MemberRepository;
import com.example.manage.service.HealingEffectQueryService;
import com.example.manage.service.MeasurementHistoryQueryService;
import com.example.manage.service.MemberAnalysisModelService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MemberAnalysisReferenceTests {
    @Test void measurementHistoryAndBothSummaryQueriesUseValidatedReferenceOnlyForTest004() {
        var effects = mock(HealingEffectQueryService.class);
        var history = mock(MeasurementHistoryQueryService.class);
        var batches = mock(HealingEffectImportBatchRepository.class);
        var members = mock(MemberRepository.class);
        var service = new MemberAnalysisModelService(effects, history, batches, members);
        when(effects.findImportedMemberImprovements(anyLong())).thenReturn(List.of());
        when(effects.findImportedMemberOverallImprovement(anyLong())).thenReturn(Optional.empty());
        when(history.findImportedMember(anyLong())).thenReturn(List.of());
        var p004 = mock(Member.class);
        when(p004.getParticipantNo()).thenReturn(4);
        when(p004.getMemberId()).thenReturn(4L);
        var test = new Member(900004, 0, "test004", "unused");
        test.configureTestAnalysis(4L);
        when(members.findById(99L)).thenReturn(Optional.of(test));
        when(members.findById(4L)).thenReturn(Optional.of(p004));
        service.populateForLoggedInMember(99L, "테스트 참가자", new ExtendedModelMap());
        verify(effects).findImportedMemberImprovements(4L);
        verify(effects).findImportedMemberOverallImprovement(4L);
        verify(history).findImportedMember(4L);
        verify(effects, never()).findImportedMemberImprovements(99L);

        var ordinary = new Member(5, 1, "ordinary", "unused");
        when(members.findById(5L)).thenReturn(Optional.of(ordinary));
        service.populateForLoggedInMember(5L, "일반 참가자", new ExtendedModelMap());
        verify(effects).findImportedMemberImprovements(5L);
        verify(effects).findImportedMemberOverallImprovement(5L);
        verify(history).findImportedMember(5L);

        service.populate(99L, "관리자 분석", new ExtendedModelMap());
        verify(effects).findImportedMemberImprovements(99L);
        verify(history).findImportedMember(99L);
    }

    @Test void missingOrWrongTargetFailsBeforeAnyAnalysisQuery() {
        var effects = mock(HealingEffectQueryService.class);
        var history = mock(MeasurementHistoryQueryService.class);
        var members = mock(MemberRepository.class);
        var service = new MemberAnalysisModelService(effects, history,
                mock(HealingEffectImportBatchRepository.class), members);
        var test = new Member(900004, 0, "test004", "unused");
        test.configureTestAnalysis(4L);
        when(members.findById(99L)).thenReturn(Optional.of(test));
        assertThatThrownBy(() -> service.populateForLoggedInMember(99L, "테스트", new ExtendedModelMap()))
                .isInstanceOf(IllegalArgumentException.class);
        when(members.findById(4L)).thenReturn(Optional.of(new Member(6, 1, "wrong", "unused")));
        assertThatThrownBy(() -> service.populateForLoggedInMember(99L, "테스트", new ExtendedModelMap()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(effects, history);
    }
}
