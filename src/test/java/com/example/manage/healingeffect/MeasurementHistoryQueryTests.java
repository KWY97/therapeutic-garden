package com.example.manage.healingeffect;

import com.example.manage.domain.*;
import com.example.manage.repository.*;
import com.example.manage.service.MeasurementHistoryQueryService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeasurementHistoryQueryTests {
    @Test void groupsExactMemberSpotRecordsAndKeepsMissingSpots() {
        var repository=mock(HealingMeasurementRepository.class);
        var spots=mock(HealingSpotRepository.class);
        var batches=mock(HealingEffectImportBatchRepository.class);
        var hs1=mock(HealingSpot.class);var hs2=mock(HealingSpot.class);
        when(hs1.getSpotId()).thenReturn(11L);when(hs1.getCode()).thenReturn("HS1");when(hs1.getName()).thenReturn("호스타 정원");
        when(hs2.getSpotId()).thenReturn(12L);when(hs2.getCode()).thenReturn("HS2");when(hs2.getName()).thenReturn("곶자왈원");
        when(spots.findByHealingCourseSiteSiteId(1L)).thenReturn(List.of(hs2,hs1));
        when(repository.findMemberForSite(4L,1L)).thenReturn(List.of(
                new HealingMeasurementRecord(4L,11L,LocalDate.of(2026,8,14),"HC-A",new BigDecimal("20"),new BigDecimal("15.25"),new BigDecimal("10"),new BigDecimal("12.5")),
                new HealingMeasurementRecord(4L,11L,LocalDate.of(2026,8,19),"HC-A",null,new BigDecimal("12"),null,new BigDecimal("11"))));
        var personal=mock(PersonalEegMeasurementRepository.class);
        when(personal.findActiveMember(anyLong())).thenReturn(java.util.Optional.empty());
        var service=new MeasurementHistoryQueryService(repository,spots,batches,personal);
        var data=service.findMemberForSite(4L,1L);
        assertEquals("HS1",data.get(0).spotCode());assertEquals(2,data.get(0).records().size());
        assertEquals("2026-08-14",data.get(0).records().get(0).measurementDate());
        assertEquals(new BigDecimal("15.25"),data.get(0).records().get(0).stress().post());
        assertEquals("25.0% 증가",data.get(0).records().get(0).emotional().rateDisplay());
        assertNull(data.get(0).records().get(1).stress().rate());
        assertTrue(data.get(1).records().isEmpty());
        assertTrue(service.findMemberForSite(5L,1L).stream().allMatch(s->s.records().isEmpty()));
        verify(repository).findMemberForSite(4L,1L);verify(repository).findMemberForSite(5L,1L);
    }
}
