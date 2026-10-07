/* Participant-only improvement analysis. Data is supplied by the authenticated /member controller. */
window.MemberHealingAnalysis = (() => {
    'use strict';
    const effects = Array.isArray(window.memberHealingEffects) ? window.memberHealingEffects : [];
    const overall = window.memberOverallImprovement || null;
    const history = Array.isArray(window.memberMeasurementHistory) ? window.memberMeasurementHistory : [];
    const metrics = {
        stress: {label: '스트레스 개선율'},
        emotional: {label: '정서적 안정성 개선율'}
    };
    const element = (tag, text, className) => {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        if (className) node.className = className;
        return node;
    };
    function display(metric) {
        if (!metric || metric.validCount === 0) return '측정 없음';
        return metric.improvementRateDisplay + ' 개선';
    }
    function countDisplay(metric) {
        return metric && metric.validCount > 0
            ? metric.improvedCount + ' / ' + metric.validCount + '회 개선'
            : '';
    }
    function renderHighlights(container, aggregate) {
        container.replaceChildren();
        const section = element('section', null, 'survey-analysis-section survey-highlight-section');
        section.append(element('h3', '핵심 변화'));
        const grid = element('div', null, 'survey-highlight-grid');
        Object.entries(metrics).forEach(([key, definition]) => {
            const metric = aggregate && aggregate[key];
            const card = element('article', null, 'survey-highlight-card');
            card.append(element('span', definition.label, 'survey-highlight-label'));
            card.append(element('p', display(metric), metric && metric.validCount > 0 ? 'effect-good' : 'effect-missing'));
            const helper = countDisplay(metric);
            if (helper) card.append(element('small', helper, 'improvement-count'));
            grid.append(card);
        });
        section.append(grid);
        container.append(section);
    }
    function renderMetricSection(key, spotEffects) {
        const section = element('section', null, 'survey-analysis-section');
        section.append(element('h3', metrics[key].label));
        const grid = element('div', null, 'survey-summary-grid');
        spotEffects.forEach(effect => {
            const metric = effect && effect[key];
            const card = element('article', null, 'survey-summary-card');
            card.append(element('strong', [effect.spotCode, effect.spotName].filter(Boolean).join(' · ')));
            card.append(element('p', display(metric), metric && metric.validCount > 0 ? 'effect-good' : 'effect-missing'));
            const helper = countDisplay(metric);
            if (helper) card.append(element('small', helper, 'improvement-count'));
            if (metric && metric.validCount > 0) {
                const track = element('div', null, 'survey-summary-track');
                const bar = element('span', null, 'effect-good');
                bar.style.width = metric.improvementRate + '%';
                track.append(bar);
                card.append(track);
            }
            grid.append(card);
        });
        if (!spotEffects.length) grid.append(element('p', '측정 없음', 'survey-empty'));
        section.append(grid);
        return section;
    }
    function renderSummary(container, spotEffects) {
        container.replaceChildren();
        container.append(renderMetricSection('stress', spotEffects), renderMetricSection('emotional', spotEffects));
    }

    renderHighlights(document.getElementById('memberAnalysisHighlights'), overall);
    renderSummary(document.getElementById('memberAnalysisSummary'), effects);
    window.MeasurementHistory.render(document.getElementById('memberAnalysisHistory'), history, {defaultSpotCode: 'HS1'});
    return {display, countDisplay, renderHighlights, renderSummary};
})();
