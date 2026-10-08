package com.example.manage;

import com.example.manage.domain.*;
import com.example.manage.dto.HealingEffectView;
import com.example.manage.dto.HealingSpotImprovementView;
import com.example.manage.dto.ImprovementMaximumView;
import com.example.manage.dto.MonitoringSpotEffectView;
import com.example.manage.repository.*;
import com.example.manage.service.HealingEffectQueryService;
import com.example.manage.healingeffect.HealingEffectExcelParser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HealingEffectUiTests {
    @Autowired WebApplicationContext context;
    @Autowired SiteRepository sites;
    @Autowired HealingCourseRepository courses;
    @Autowired HealingSpotRepository spots;
    @Autowired MemberRepository members;
    @Autowired HealingSpotEffectSummaryRepository overall;
    @Autowired MemberHealingSpotEffectSummaryRepository personal;
    @Autowired HealingEffectImportBatchRepository batches;
    @Autowired HealingEffectQueryService service;
    MockMvc mvc;
    Member partial, firstComplete, laterComplete, landingParticipant;

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    void fixture() {
        var site = sites.save(new Site("effect-ui-site", "synthetic", 37.0, 127.0, 3));
        partial = members.save(new Member(1, 1, "secret-login-one", "unused", "비공개이름하나", "01012345678"));
        // Insertion/PK order intentionally differs from participantNo order.
        laterComplete = members.save(new Member(4, 1, "secret-login-four", "unused", "비공개이름넷", "01044445555"));
        firstComplete = members.save(new Member(2, 1, "secret-login-two", "unused", "비공개이름둘", "01022223333"));
        landingParticipant = members.save(new Member(6, 1, "secret-login-six", "unused", "비공개이름육", "01066667777"));
        var courseNames = List.of("회복 코스", "감각 코스", "힐링 코스");
        int[] overallValid = {17, 16, 22, 23, 17, 18};
        int[] overallStressImproved = {15, 13, 16, 13, 11, 13};
        int[] overallEmotionalImproved = {10, 11, 15, 16, 11, 16};
        int[] personalValid = {2, 2, 3, 3, 1, 2};
        int[] personalStressImproved = {2, 2, 1, 2, 0, 1};
        int[] personalEmotionalImproved = {2, 1, 1, 2, 0, 1};
        int[] landingValid = {1, 1, 3, 3, 3, 3};
        int[] landingStressImproved = {1, 1, 3, 1, 3, 3};
        int[] landingEmotionalImproved = {1, 1, 2, 1, 3, 3};
        HealingCourse course = null;
        for (int i = 0; i < 6; i++) {
            if (i % 2 == 0) course = courses.save(new HealingCourse(site, "HC-"+(char)('A'+i/2), courseNames.get(i/2), null,null,null));
            var spot = spots.save(new HealingSpot(course, "HS"+(i+1), HealingEffectExcelParser.NAMES.get(i),37.0,127.0));
            BigDecimal stress = new BigDecimal(i == 0 ? "19.500108327673271" : i == 5 ? "21.213120597090558" : "10.25");
            BigDecimal emotional = new BigDecimal(i == 0 ? "54.267121115714403" : i == 5 ? "200.422124968168790" : "30.25");
            var overallSummary = new HealingSpotEffectSummary(spot, 3, 5, stress, 3, 5, emotional);
            overallSummary.updateImprovementCounts(3, overallValid[i], overallValid[i], overallStressImproved[i],
                    overallValid[i], overallEmotionalImproved[i]);
            overall.save(overallSummary);
            BigDecimal personalStress = new BigDecimal(i == 1 ? "-38.6" : i == 2 ? "0" : "10.5");
            BigDecimal personalEmotional = new BigDecimal(i == 4 ? "-6.5" : i == 3 ? "0" : "100.4");
            var exampleSummary = new MemberHealingSpotEffectSummary(firstComplete,spot,2,personalStress,2,personalEmotional);
            exampleSummary.updateImprovementCounts(personalValid[i], personalValid[i], personalStressImproved[i],
                    personalValid[i], personalEmotionalImproved[i]);
            personal.save(exampleSummary);
            var laterSummary = new MemberHealingSpotEffectSummary(laterComplete,spot,2,new BigDecimal("33.33"),2,new BigDecimal("44.44"));
            laterSummary.updateImprovementCounts(2, 2, 1, 2, 1);
            personal.save(laterSummary);
            var landingSummary = new MemberHealingSpotEffectSummary(landingParticipant, spot,
                    landingValid[i], BigDecimal.ZERO, landingValid[i], BigDecimal.ZERO);
            landingSummary.updateImprovementCounts(landingValid[i], landingValid[i], landingStressImproved[i],
                    landingValid[i], landingEmotionalImproved[i]);
            personal.save(landingSummary);
            if(i < 4) {
                var partialSummary = new MemberHealingSpotEffectSummary(partial,spot,2,BigDecimal.ZERO,2,new BigDecimal("-4.25"));
                partialSummary.updateImprovementCounts(2, 2, 0, 2, 1);
                personal.save(partialSummary);
            }
        }
        batches.save(new HealingEffectImportBatch("a".repeat(64), "synthetic-ui.xlsx", LocalDateTime.now(), 6, 22, site.getSiteId()));
        batches.flush();
    }

    @Test void homePublishesOverallAndConfiguredP006WithoutIdentity() throws Exception {
        fixture();
        var result = mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(model().attributeExists("healingEffects", "effectsBySpot", "overallMaximums",
                        "personalEffects", "personalMaximums"))
                .andReturn();
        var model = result.getModelAndView().getModel();
        @SuppressWarnings("unchecked") var data = (List<HealingSpotImprovementView>) model.get("healingEffects");
        assertThat(data).extracting(HealingSpotImprovementView::spotCode).containsExactly("HS1","HS2","HS3","HS4","HS5","HS6");
        assertThat(data.getFirst().stress().improvementRateDisplay()).isEqualTo("88.2%");
        assertThat(data.getFirst().emotional().improvementRateDisplay()).isEqualTo("58.8%");
        assertThat(data.getLast().stress().improvementRateDisplay()).isEqualTo("72.2%");
        assertThat(data.getLast().emotional().improvementRateDisplay()).isEqualTo("88.9%");
        assertThat(data).extracting(view -> view.stress().improvementRateDisplay())
                .containsExactly("88.2%", "81.3%", "72.7%", "56.5%", "64.7%", "72.2%");
        assertThat(data).extracting(view -> view.emotional().improvementRateDisplay())
                .containsExactly("58.8%", "68.8%", "68.2%", "69.6%", "64.7%", "88.9%");
        @SuppressWarnings("unchecked") var personalData = (List<HealingSpotImprovementView>) model.get("personalEffects");
        assertThat(personalData).extracting(view -> view.stress().improvementRateDisplay())
                .containsExactly("100.0%", "100.0%", "100.0%", "33.3%", "100.0%", "100.0%");
        assertThat(personalData).extracting(view -> view.emotional().improvementRateDisplay())
                .containsExactly("100.0%", "100.0%", "66.7%", "33.3%", "100.0%", "100.0%");
        assertThat(personalData).extracting(view -> view.stress().validCount())
                .containsExactly(1, 1, 3, 3, 3, 3);
        assertThat(personalData).extracting(view -> view.emotional().validCount())
                .containsExactly(1, 1, 3, 3, 3, 3);
        assertThat(personalData).extracting(view -> view.stress().improvedCount())
                .containsExactly(1, 1, 3, 1, 3, 3);
        assertThat(personalData).extracting(view -> view.emotional().improvedCount())
                .containsExactly(1, 1, 2, 1, 3, 3);
        var overallMaximums = (ImprovementMaximumView) model.get("overallMaximums");
        assertThat(overallMaximums.stressSpotCodes()).containsExactly("HS1");
        assertThat(overallMaximums.emotionalSpotCodes()).containsExactly("HS6");
        var personalMaximums = (ImprovementMaximumView) model.get("personalMaximums");
        assertThat(personalMaximums.stressSpotCodes()).containsExactlyInAnyOrder("HS3", "HS5", "HS6");
        assertThat(personalMaximums.emotionalSpotCodes()).containsExactlyInAnyOrder("HS5", "HS6");
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("데이터 변화", "data-stress=\"88.2% 개선\"", "data-emotional=\"88.9% 개선\"",
                        "data-stress-display=\"100.0% 개선\"", "data-emotional-display=\"100.0% 개선\"",
                        "data-stress-count=\"3회 중 3회 개선\"", "data-emotional-count=\"3회 중 2회 개선\"",
                        "스트레스 개선율", "정서 안정성 개선율", "PERSON / HEALING SPOT",
                        "스트레스 최대 개선", "정서 안정성 최대 개선")
                .doesNotContain("P001", "P002", "P004", "participantNo", "memberId", "participantCode", "loginId",
                        "secret-login-one", "secret-login-two", "secret-login-four", "secret-login-six", "비공개이름하나", "비공개이름둘", "비공개이름넷", "비공개이름육",
                        "01012345678", "01022223333", "01044445555", "01066667777", "010-1234-5678", "010-2222-3333", "010-4444-5555", "010-6666-7777",
                        "참가자 예시", "익명 참가자의 측정 결과", "19.5% 감소", "54.3% 증가",
                        "평균 스트레스 증감률", "평균 정서 안정성 증감률", "정서적 안정성",
                        "Baseline", "BEFORE &amp; AFTER", "stress-baseline", "stress-followup", "66.7% 증가", "landing-chart-line");

        assertMapCard(html, "HS1", "스트레스", "88.2%", "17회 중 15회 개선", "스트레스 최대 개선");
        assertMapCard(html, "HS2", "정서 안정성", "68.8%", "16회 중 11회 개선");
        assertMapCard(html, "HS3", "스트레스", "72.7%", "22회 중 16회 개선");
        assertMapCard(html, "HS4", "정서 안정성", "69.6%", "23회 중 16회 개선");
        assertMapCard(html, "HS5", "스트레스", "64.7%", "17회 중 11회 개선");
        assertMapCard(html, "HS6", "정서 안정성", "88.9%", "18회 중 16회 개선", "정서 안정성 최대 개선");
        String personalSection = html.substring(html.indexOf("landing-person-scene"), html.indexOf("landing-connection"));
        assertThat(personalSection).contains(">최대 개선</span>")
                .doesNotContain("스트레스 최대 개선", "정서 안정성 최대 개선");
    }

    @Test void publishedPersonalChangeUsesP006EvenWhenSmallerCompleteParticipantsExist() {
        fixture();
        assertThat(landingParticipant.getMemberId()).isNotEqualTo(6L);
        var selected = service.findPublishedMemberImprovementsByParticipantNo(6);
        assertThat(selected).hasSize(6);
        assertThat(selected).extracting(view -> view.stress().improvementCountDisplay())
                .containsExactly("1회 중 1회 개선", "1회 중 1회 개선", "3회 중 3회 개선",
                        "3회 중 1회 개선", "3회 중 3회 개선", "3회 중 3회 개선");
        assertThat(service.findPublishedMemberImprovementsByParticipantNo(999)).isEmpty();
    }

    @Test void incompleteP006RendersPersonalDataPreparingState() throws Exception {
        fixture();
        var summaries = personal.findByMemberMemberIdOrderByHealingSpotCodeAsc(landingParticipant.getMemberId());
        personal.delete(summaries.getFirst());
        personal.flush();
        assertThat(service.findPublishedMemberImprovementsByParticipantNo(6)).isEmpty();
        var result = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var personalData = (List<HealingSpotImprovementView>)
                result.getModelAndView().getModel().get("personalEffects");
        assertThat(personalData).isEmpty();
        assertThat(result.getResponse().getContentAsString()).contains("데이터 준비 중")
                .doesNotContain("data-personal-spot=");
    }

    @Test void zeroValidP006MetricShowsMeasurementMissingWithoutZeroCounts() throws Exception {
        fixture();
        var summary = personal.findByMemberMemberIdOrderByHealingSpotCodeAsc(landingParticipant.getMemberId()).getFirst();
        summary.updateImprovementCounts(summary.getTotalExperienceCount(), 0, 0, 0, 0);
        personal.flush();
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-stress-display=\"측정 없음\"", "data-emotional-display=\"측정 없음\"")
                .doesNotContain("0회 중 0회 개선");
    }

    @Test void anonymousSelectionIsCompleteStableAndOrderedByParticipantNumber() {
        fixture();
        var example = service.findAnonymousExample();
        assertThat(example).hasSize(6).allSatisfy(e -> {
            assertThat(e.hasMeasurement()).isTrue();
            assertThat(e.stressChangeDisplay()).matches("10.5% 감소|38.6% 증가|0.0% 변화 없음");
            assertThat(e.emotionalChangeDisplay()).matches("100.4% 증가|6.5% 감소|0.0% 변화 없음");
        });
        assertThat(service.findAnonymousExample()).isEqualTo(example);
        personal.deleteByMemberMemberId(firstComplete.getMemberId()); personal.flush();
        assertThat(service.findAnonymousExample()).hasSize(6).allSatisfy(e->assertThat(e.stressReductionDisplay()).isEqualTo("33.3%"));
        personal.deleteByMemberMemberId(laterComplete.getMemberId()); personal.flush();
        assertThat(service.findAnonymousExample()).hasSize(6);
        personal.deleteByMemberMemberId(landingParticipant.getMemberId()); personal.flush();
        assertThat(service.findAnonymousExample()).isEmpty();
    }

    @Test void anonymousImprovementExampleUsesCountBackedP004RegressionValues() {
        fixture();
        var example = service.findAnonymousExampleImprovements();
        assertThat(example).hasSize(6);
        assertThat(example.get(0).stress().improvementRateDisplay()).isEqualTo("100.0%");
        assertThat(example.get(0).emotional().improvementRateDisplay()).isEqualTo("100.0%");
        assertThat(example.get(4).stress().improvementRateDisplay()).isEqualTo("0.0%");
        assertThat(example.get(4).emotional().improvementRateDisplay()).isEqualTo("0.0%");
        assertThat(example).extracting(view -> view.stress().improvementRateDisplay())
                .containsExactly("100.0%", "100.0%", "33.3%", "66.7%", "0.0%", "50.0%");
        assertThat(example).extracting(view -> view.emotional().improvementRateDisplay())
                .containsExactly("100.0%", "50.0%", "33.3%", "66.7%", "0.0%", "50.0%");
        var overallView = service.findParticipantOverallImprovement(
                exampleSiteId(), firstComplete.getMemberId()).orElseThrow();
        assertThat(overallView.stress().improvedCount()).isEqualTo(8);
        assertThat(overallView.stress().validCount()).isEqualTo(13);
        assertThat(overallView.stress().improvementRateDisplay()).isEqualTo("61.5%");
        assertThat(overallView.emotional().improvedCount()).isEqualTo(7);
        assertThat(overallView.emotional().validCount()).isEqualTo(13);
        assertThat(overallView.emotional().improvementRateDisplay()).isEqualTo("53.8%");
    }

    private Long exampleSiteId() {
        return sites.findAll().stream().filter(site -> site.getName().equals("effect-ui-site"))
                .findFirst().orElseThrow().getSiteId();
    }

    @Test void adminMonitoringUsesOnlyOverallSummariesAndSpotSelection() throws Exception {
        fixture();
        var result = mvc.perform(get("/admin/monitoring").sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("monitoringEffects", "monitoringMaximums"))
                .andExpect(model().attributeDoesNotExist("monitoringParticipants", "monitoringHistory"))
                .andReturn();
        @SuppressWarnings("unchecked")
        var bySite = (Map<String, List<MonitoringSpotEffectView>>) result.getModelAndView().getModel().get("monitoringEffects");
        var data = bySite.values().iterator().next();
        assertThat(data).hasSize(6);
        assertThat(data).extracting(view -> view.stress().improvementRateDisplay())
                .containsExactly("88.2%", "81.3%", "72.7%", "56.5%", "64.7%", "72.2%");
        assertThat(data).extracting(view -> view.emotional().improvementRateDisplay())
                .containsExactly("58.8%", "68.8%", "68.2%", "69.6%", "64.7%", "88.9%");
        assertThat(data).extracting(view -> view.stress().improvementCountDisplay())
                .containsExactly("17회 중 15회 개선", "16회 중 13회 개선", "22회 중 16회 개선",
                        "23회 중 13회 개선", "17회 중 11회 개선", "18회 중 13회 개선");
        assertThat(data).extracting(view -> view.emotional().improvementCountDisplay())
                .containsExactly("17회 중 10회 개선", "16회 중 11회 개선", "22회 중 15회 개선",
                        "23회 중 16회 개선", "17회 중 11회 개선", "18회 중 16회 개선");
        @SuppressWarnings("unchecked")
        var maximumsBySite = (Map<String, ImprovementMaximumView>) result.getModelAndView().getModel()
                .get("monitoringMaximums");
        var maximums = maximumsBySite.values().iterator().next();
        assertThat(maximums.stressSpotCodes()).containsExactly("HS1");
        assertThat(maximums.emotionalSpotCodes()).containsExactly("HS6");

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("스팟 설정", "HS 선택", "88.2%", "68.8%", "88.9%",
                        "17회 중 15회 개선", "16회 중 11회 개선", "18회 중 16회 개선",
                        "개선율 낮음", "개선율 높음", "유효 측정 중 개선된 횟수의 비율",
                        "monitoringMaximums", "상세 분석 보기", "측정 데이터")
                .doesNotContain("정서적 안정성")
                .doesNotContain("19.5% 감소", "54.3% 증가", "200.4% 증가", "stressReductionRate",
                        "emotionalIncreaseRate", "스트레스: 낮아질수록 개선", "정서 안정성: 높아질수록 개선")
                .doesNotContain("Demo 데이터", "시연용 데이터", "id=\"metricSelect\"", "id=\"hcStress\"", "id=\"hcEmotional\"", ">ISI<", ">PSS<",
                        "P001", "P002", "P004", "participantSelect", "분석 대상", "monitoringHistory", "analysisHistorySection",
                        "1차", "2차", "3차", "4차", "5차", "방문 횟수", "<details id=\"analysisHistorySection\"");
    }

    @Test void adminMonitoringDoesNotFallbackWhenCountsAreMissingOrValidCountIsZero() {
        fixture();
        var summaries = overall.findAll().stream()
                .sorted(Comparator.comparing(summary -> summary.getHealingSpot().getCode())).toList();
        summaries.getFirst().updateImprovementCounts(3, 17, 0, 0, 0, 0);
        overall.delete(summaries.get(1));
        overall.flush();
        var spot = summaries.get(1).getHealingSpot();
        overall.save(new HealingSpotEffectSummary(spot, 3, 16, new BigDecimal("999.9"),
                3, 16, new BigDecimal("999.9")));
        overall.flush();

        var views = service.findMonitoringOverallForSite(exampleSiteId());
        assertThat(views.getFirst().stress().improvementRate()).isNull();
        assertThat(views.getFirst().stress().improvementRateDisplay()).isEqualTo("측정 없음");
        assertThat(views.getFirst().stress().improvementCountDisplay()).isEqualTo("측정 없음");
        assertThat(views.get(1).stress()).isNull();
        assertThat(views.get(1).emotional()).isNull();
        var maximums = service.findMonitoringMaximumImprovements(views);
        assertThat(maximums.stressSpotCodes()).doesNotContain("HS1", "HS2");
        assertThat(maximums.emotionalSpotCodes()).doesNotContain("HS1", "HS2");
    }

    @Test void participantHomeUsesOnlyAuthenticatedMembersSummaryAndHistory() throws Exception {
        fixture();
        var result = mvc.perform(get("/member").sessionAttr("loginMemberId", partial.getMemberId()))
                .andExpect(status().isOk()).andExpect(view().name("member/home"))
                .andExpect(model().attributeExists("spotEffects", "overallImprovement", "measurementHistory", "schedules"))
                .andReturn();
        @SuppressWarnings("unchecked") var effects = (List<HealingSpotImprovementView>) result.getModelAndView().getModel().get("spotEffects");
        assertThat(effects).extracting(HealingSpotImprovementView::spotCode).containsExactly("HS1", "HS2", "HS3", "HS4", "HS5", "HS6");
        assertThat(effects.getFirst().stress().improvementRateDisplay()).isEqualTo("0.0%");
        assertThat(effects.getFirst().stress().validCount()).isEqualTo(2);
        assertThat(effects.get(4).stress().improvementRateDisplay()).isEqualTo("측정 없음");
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("나의 치유 분석", "나의 공간별 치유 효과와 측정 변화를 확인합니다.",
                        "Spot별 변화 / 상세 분석", "측정 기록",
                        "memberAnalysisSummary", "memberAnalysisHistory", "memberHealingEffects",
                        "memberOverallImprovement", "memberMeasurementHistory", "member-healing-analysis.js", "나의 달력",
                        "\"prev,next\"", "\"title\"", "\"today\"", "calendar-toolbar-grid")
                .doesNotContain("상세 분석 보기", "openMemberAnalysisButton", "memberAnalysisModal",
                        "aria-modal=", "\"prev,next today\"", "home-survey-analysis.js", "P002", "P004", "secret-login-two", "secret-login-four");
        assertThat(html.indexOf("나의 치유 분석")).isLessThan(html.indexOf("나의 달력"));
        assertThat(html.indexOf("memberAnalysisHighlights")).isLessThan(html.indexOf("memberAnalysisSummary"));
        assertThat(html.indexOf("memberAnalysisSummary")).isLessThan(html.indexOf("memberAnalysisHistory"));
    }

    @Test void emptyDatabaseRendersHonestEmptyStates() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("데이터 준비 중", "데이터 변화")
                .doesNotContain("data-stress=", "data-personal-spot=", "35% 감소", "29.2% 감소", "66.7% 증가");
    }

    @Test void absentOverallRowDoesNotFallBackToSample() throws Exception {
        fixture();
        overall.deleteAll(); overall.flush();
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("데이터 준비 중").doesNotContain("data-stress=\"35\"", "35% 감소");
    }

    @Test void removesRequestedHeroCopyEvenAcrossMarkupAndWhitespace() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String normalized = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        assertThat(normalized).doesNotContain("공간별 평균 변화와 한 참가자의 측정 전후를 함께 살펴봅니다.",
                "생체신호와 공간별 반응을 함께 살피며 치유효과를 모니터링합니다.",
                "모니터링한 생체신호와 공간별 치유효과를 바탕으로 개인에게 맞는 힐링코스를 제안합니다.");
    }

    @Test void adminMemberDetailShowsBasicInformationAndDataLinkWithoutEffects() throws Exception {
        fixture();
        String html = mvc.perform(get("/admin/members/" + partial.getMemberId()).sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("member", "formattedPhone"))
                .andExpect(model().attributeDoesNotExist("spotEffects"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("P001", "secret-login-one", "비공개이름하나", "010-1234-5678",
                        "href=\"/admin/members/" + partial.getMemberId() + "/data\"", "데이터 보기",
                        "참가자 정보 수정", "참가자 삭제", "참가자 목록으로")
                .doesNotContain("스팟별 치유 효과", "member-effect-spot", "HS1 ·");
    }

    @Test void adminMemberDataShowsActualMemberAndSixSpotsIncludingMissingAndZero() throws Exception {
        fixture();
        var result = mvc.perform(get("/admin/members/" + partial.getMemberId() + "/data")
                        .sessionAttr("loginAdminId", 1L))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/member-data"))
                .andExpect(model().attributeExists("member", "spotEffects", "measurementHistory"))
                .andReturn();
        @SuppressWarnings("unchecked") var views=(List<HealingEffectView>)result.getModelAndView().getModel().get("spotEffects");
        assertThat(views).extracting(HealingEffectView::spotCode).containsExactly("HS1","HS2","HS3","HS4","HS5","HS6");
        assertThat(views.getFirst().stressReductionDisplay()).isEqualTo("0.0%");
        assertThat(views.get(4).hasMeasurement()).isFalse();
        assertThat(views.get(5).stressReductionDisplay()).isEqualTo("측정 없음");
        String html=result.getResponse().getContentAsString();
        assertThat(html).contains("P001", "비공개이름하나", "참가자 데이터", "스팟별 치유 효과",
                        "평균 스트레스 증감률", "평균 정서 안정성 증감률",
                        "0.0% 변화 없음", "측정 없음", "4.3% 감소", "memberMeasurementHistory", "measurement-history.js",
                        "href=\"/admin/members/" + partial.getMemberId() + "\"", "← 참가자 상세로")
                .doesNotContain("secret-login-one", "010-1234-5678", "-4.3% 증가");
        assertThat(html.indexOf("HS1 ·")).isLessThan(html.indexOf("HS6 ·"));
    }

    @Test void adminEffectDetailRemainsProtected() throws Exception {
        fixture();
        for (String path : List.of(
                "/admin/members/" + partial.getMemberId(),
                "/admin/members/" + partial.getMemberId() + "/data")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/admin/login"));
            mvc.perform(get(path).sessionAttr("loginMemberId", partial.getMemberId()))
                    .andExpect(redirectedUrl("/admin/login"));
        }
    }

    @Test void metricSpecificDisplayFormattingPreservesRawSigns() {
        var positive = HealingEffectView.measured("HS1", "Spot", new BigDecimal("10.5"),
                new BigDecimal("100.4"), 1, 1);
        var negative = HealingEffectView.measured("HS2", "Spot", new BigDecimal("-38.6"),
                new BigDecimal("-6.5"), 1, 1);
        var zero = HealingEffectView.measured("HS3", "Spot", BigDecimal.ZERO, BigDecimal.ZERO, 1, 1);
        var missing = HealingEffectView.missing("HS4", "Spot");
        assertThat(positive.stressChangeDisplay()).isEqualTo("10.5% 감소");
        assertThat(positive.emotionalChangeDisplay()).isEqualTo("100.4% 증가");
        assertThat(negative.stressReductionRate()).isEqualByComparingTo("-38.6");
        assertThat(negative.emotionalIncreaseRate()).isEqualByComparingTo("-6.5");
        assertThat(negative.stressChangeDisplay()).isEqualTo("38.6% 증가");
        assertThat(negative.emotionalChangeDisplay()).isEqualTo("6.5% 감소");
        assertThat(zero.stressChangeDisplay()).isEqualTo("0.0% 변화 없음");
        assertThat(zero.emotionalChangeDisplay()).isEqualTo("0.0% 변화 없음");
        assertThat(missing.stressChangeDisplay()).isEqualTo("측정 없음");
        assertThat(missing.emotionalChangeDisplay()).isEqualTo("측정 없음");
    }

    private static void assertMapCard(String html, String spotCode, String... expected) {
        String marker = "landing-preview-spot landing-preview-spot-" + spotCode.toLowerCase(Locale.ROOT);
        int start = html.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = html.indexOf("landing-preview-spot landing-preview-spot-", start + marker.length());
        String card = html.substring(start, end < 0 ? html.indexOf("</figure>", start) : end);
        assertThat(card).contains(expected);
    }
}
