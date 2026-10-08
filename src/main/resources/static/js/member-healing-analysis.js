/* Participant-only improvement analysis. Data is supplied by the authenticated /member controller. */
window.MemberHealingAnalysis = (() => {
    'use strict';
    const effects = Array.isArray(window.memberHealingEffects) ? window.memberHealingEffects : [];
    const overall = window.memberOverallImprovement || null;
    const maximums = window.memberMaximumImprovements || {stressSpotCodes: [], emotionalSpotCodes: []};
    const memberName = typeof window.memberDisplayName === 'string' && window.memberDisplayName.trim()
        ? window.memberDisplayName.trim() : '참가자';
    const history = Array.isArray(window.memberMeasurementHistory) ? window.memberMeasurementHistory : [];
    const metrics = {
        stress: {label: '스트레스 개선율'},
        emotional: {label: '정서 안정성 개선율'}
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
    const spotNamesText = names => !names.length ? '' : names.length < 2 ? `'${names[0]}'`
        : names.length === 2 ? `'${names[0]}'과 '${names[1]}'`
            : names.map(name => `'${name}'`).join(', ');
    const plain = text => element('span', text);
    function maximumState(key, spotEffects) {
        const codes = maximums[key + 'SpotCodes'] || [];
        const candidates = spotEffects.filter(effect => codes.includes(effect.spotCode))
            .sort((left, right) => left.spotCode.localeCompare(right.spotCode, undefined, {numeric:true}));
        return {
            hasValid: spotEffects.some(effect => effect[key] && effect[key].validCount > 0),
            names: candidates.map(effect => effect.spotName).filter(Boolean),
            improved: candidates.some(effect => effect[key] && effect[key].improvedCount > 0)
        };
    }
    function renderResultSummary(spotEffects) {
        const summary = element('p', null, 'participant-result-summary');
        summary.append(element('strong', memberName + '님', 'participant-result-name'), plain('의 측정 결과, '));
        const states = Object.keys(metrics).map(key => ({key, ...maximumState(key, spotEffects)}));
        const positive = states.filter(state => state.hasValid && state.improved && state.names.length);
        positive.forEach((state, index) => {
            if (index) summary.append(plain(', '));
            summary.append(element('strong', state.key === 'stress' ? '스트레스' : '정서 안정성',
                'participant-result-metric is-' + state.key));
            summary.append(plain(state.key === 'stress' ? '는 ' : '은 '),
                element('strong', spotNamesText(state.names), 'participant-result-spots'), plain('에서'));
        });
        if (positive.length) summary.append(plain(' 가장 높은 개선율을 보였습니다.'));
        const neutral = states.filter(state => state.hasValid && (!state.improved || !state.names.length));
        neutral.forEach((state, index) => {
            if (positive.length || index) summary.append(plain(' '));
            summary.append(plain('현재 '), element('strong', state.key === 'stress' ? '스트레스' : '정서 안정성',
                'participant-result-metric is-' + state.key), plain(' 개선이 확인된 스팟은 없습니다.'));
        });
        if (!positive.length && !neutral.length) summary.append(plain('유효한 측정 결과가 아직 없습니다.'));
        return summary;
    }
    function renderHighlights(container, aggregate, spotEffects) {
        container.replaceChildren();
        const section = element('section', null, 'survey-analysis-section survey-highlight-section');
        section.append(element('h3', '총 변화'));
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
        section.append(renderResultSummary(spotEffects));
        container.append(section);
    }
    function renderMetricSection(key, spotEffects) {
        const section = element('section', null, 'survey-analysis-section');
        section.append(element('h3', metrics[key].label));
        const grid = element('div', null, 'survey-summary-grid');
        spotEffects.forEach(effect => {
            const metric = effect && effect[key];
            const card = element('article', null, 'survey-summary-card');
            const maximumCodes = maximums[key + 'SpotCodes'] || [];
            const isMaximum = maximumCodes.includes(effect.spotCode);
            if (isMaximum) card.classList.add('is-maximum');
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
            if (isMaximum) {
                card.setAttribute('role', 'group');
                card.setAttribute('aria-label', [effect.spotCode, effect.spotName, metrics[key].label,
                    display(metric), helper, '최대 개선 스팟'].filter(Boolean).join(' · '));
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

    renderHighlights(document.getElementById('memberAnalysisHighlights'), overall, effects);
    renderSummary(document.getElementById('memberAnalysisSummary'), effects);
    window.MeasurementHistory.render(document.getElementById('memberAnalysisHistory'), history, {defaultSpotCode: 'HS1', participant: true});
    return {display, countDisplay, spotNamesText, renderHighlights, renderSummary};
})();
