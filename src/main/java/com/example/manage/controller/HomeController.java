package com.example.manage.controller;

import jakarta.servlet.http.HttpSession;
import com.example.manage.domain.Site;
import com.example.manage.service.SiteService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final SiteService siteService;
    private final com.example.manage.service.SiteImageService siteImages;
    private final com.example.manage.service.HealingEffectQueryService healingEffects;

    @Value("${kakao.maps.javascript-key}")
    private String kakaoMapsJavaScriptKey;

    @Value("${landing.personal-change-participant-no:6}")
    private Integer personalChangeParticipantNo;

    @GetMapping("/")
    public String landing(HttpSession session, Model model) {
        if (session.getAttribute("loginAdminId") != null) {
            return "redirect:/admin/monitoring";
        }
        if (session.getAttribute("loginMemberId") != null) {
            return "redirect:/member";
        }
        var overall = healingEffects.findPublishedOverallImprovements();
        model.addAttribute("healingEffects", overall);
        model.addAttribute("effectsBySpot", overall.stream().collect(java.util.stream.Collectors.toMap(
                com.example.manage.dto.HealingSpotImprovementView::spotCode, java.util.function.Function.identity())));
        model.addAttribute("overallMaximums", healingEffects.findMaximumImprovements(overall));
        var personal = healingEffects.findPublishedMemberImprovementsByParticipantNo(personalChangeParticipantNo);
        model.addAttribute("personalEffects", personal);
        model.addAttribute("personalMaximums", healingEffects.findMaximumImprovements(personal));
        return "landing";
    }

    @GetMapping("/admin/monitoring")
    public String home(Model model) {

        List<Site> sites = siteService.findAllSites();

        model.addAttribute(
                "kakaoMapsJavaScriptKey",
                kakaoMapsJavaScriptKey
        );
        model.addAttribute("sites", sites);
        var imageUrls = new java.util.HashMap<Long, String>();
        sites.forEach(site -> imageUrls.put(site.getSiteId(), siteImages.representativeReadUrl(site.getSiteId())));
        model.addAttribute("siteImageUrls", imageUrls);

        var monitoringEffects = new java.util.LinkedHashMap<String, java.util.List<com.example.manage.dto.MonitoringSpotEffectView>>();
        var monitoringMaximums = new java.util.LinkedHashMap<String, com.example.manage.dto.ImprovementMaximumView>();
        sites.forEach(site -> {
            var siteEffects = healingEffects.findMonitoringOverallForSite(site.getSiteId());
            var siteKey = String.valueOf(site.getSiteId());
            monitoringEffects.put(siteKey, siteEffects);
            monitoringMaximums.put(siteKey, healingEffects.findMonitoringMaximumImprovements(siteEffects));
        });
        model.addAttribute("monitoringEffects", monitoringEffects);
        model.addAttribute("monitoringMaximums", monitoringMaximums);

        return "home";
    }
}
