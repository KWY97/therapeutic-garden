(function () {
    'use strict';
    var spatial = window.HomeSpatial(function () {});
    if (window.memberMonitoringSiteId != null) {
        spatial.load({value: String(window.memberMonitoringSiteId)}, {layoutUrl: '/member/api/spatial-layout'});
    }
    spatial.setAnalysis(window.memberMonitoringEffects, window.memberMaximumImprovements);
})();
