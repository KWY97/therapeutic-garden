(function () {
    'use strict';
    var spatial = window.HomeSpatial(spot => window.MemberSpotDetail.open(spot));
    window.MemberSpotDetail.connect(spatial);
    if (window.memberMonitoringSiteId != null) {
        spatial.load({value: String(window.memberMonitoringSiteId)}, {layoutUrl: '/member/api/spatial-layout'});
    }
    spatial.setAnalysis(window.memberMonitoringEffects, window.memberMaximumImprovements);
})();
