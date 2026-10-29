package com.example.manage.dto;

import com.example.manage.domain.Site;
import lombok.Getter;

@Getter
public class SiteResponse {

    private final Long siteId;
    private final String name;

    public SiteResponse(Site site) {
        this.siteId = site.getSiteId();
        this.name = site.getName();
    }
}
