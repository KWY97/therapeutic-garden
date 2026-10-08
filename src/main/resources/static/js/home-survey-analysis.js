/* Count-backed Healing Effect Summary presentation. Percentages are calculated by the server. */
window.HomeSurvey = (() => {
    'use strict';
    const metrics = Object.freeze({
        stress: Object.freeze({name: '스트레스 개선율', label: '스트레스', field: 'stress'}),
        emotional: Object.freeze({name: '정서 안정성 개선율', label: '정서 안정성', field: 'emotional'})
    });
    const SPOT_METRIC = Object.freeze({
        HS1: 'stress', HS2: 'emotional', HS3: 'stress',
        HS4: 'emotional', HS5: 'stress', HS6: 'emotional'
    });
    const METRIC_ORDER = Object.freeze(['stress', 'emotional']);
    const HALO_SCALE = Object.freeze({
        stress: Object.freeze({min: 0, max: 100}),
        emotional: Object.freeze({min: 0, max: 100})
    });
    const HALO_TONES = Object.freeze({
        low: Object.freeze({h: 40, s: 35, l: 82}),
        high: Object.freeze({h: 150, s: 45, l: 40})
    });
    const element = (tag, text, className) => {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        if (className) node.className = className;
        return node;
    };
    function valueRow(label, value) {
        const row = element('div', null, 'spot-information-item');
        row.append(element('span', label), element('strong', value));
        return row;
    }
    function metricView(effect, metric) {
        if (!effect || !effect.hasMeasurement || !metrics[metric]) return null;
        const view = effect[metrics[metric].field];
        return view && typeof view === 'object' ? view : null;
    }
    function metricValue(effect, metric) {
        const view = metricView(effect, metric);
        if (!view || view.improvementRate == null) return null;
        const value = Number(view.improvementRate);
        return Number.isFinite(value) ? value : null;
    }
    function metricDisplay(effect, metric) {
        if (!effect || !effect.hasMeasurement) return '데이터 준비 중';
        const view = metricView(effect, metric);
        if (!view) return '데이터 준비 중';
        return view.improvementRateDisplay || '측정 없음';
    }
    function metricCountDisplay(effect, metric) {
        const view = metricView(effect, metric);
        return view && Number(view.validCount) > 0 && view.improvementCountDisplay
            ? view.improvementCountDisplay : '';
    }
    function metricRateWithUnit(effect, metric) {
        const value = metricValue(effect, metric);
        return metricDisplay(effect, metric) + (value == null ? '' : ' 개선');
    }
    function indexByCode(effects) {
        return new Map((Array.isArray(effects) ? effects : []).map(effect => [effect.spotCode, effect]));
    }
    function getMetricDisplay(effect, metric) {
        const value = metrics[metric] ? metricValue(effect, metric) : null;
        return {
            metric,
            metricLabel: metrics[metric] ? metrics[metric].label : '',
            value,
            numberLabel: metricDisplay(effect, metric),
            unitLabel: value == null ? '' : '개선',
            countLabel: metricCountDisplay(effect, metric),
            status: semanticState(value, metric)
        };
    }
    // Single read boundary for the fixed Spot representative metric policy.
    function getSpotDisplay(spotId, effects) {
        const metric = SPOT_METRIC[spotId];
        const lookup = effects instanceof Map ? effects : indexByCode(effects);
        const effect = metric ? lookup.get(spotId) : null;
        const display = getMetricDisplay(effect, metric);
        return {...display, color: haloColor(display.value, metric)};
    }
    function getSpotMetricRows(spotCode, effects) {
        const lookup = effects instanceof Map ? effects : indexByCode(effects);
        const effect = lookup.get(spotCode);
        return METRIC_ORDER.map(metric => getMetricDisplay(effect, metric));
    }
    function normalizedImprovementScore(display) {
        if (!display || display.value == null || !HALO_SCALE[display.metric]) return null;
        const scale = HALO_SCALE[display.metric];
        return Math.max(0, Math.min(1, (Number(display.value) - scale.min) / (scale.max - scale.min)));
    }
    function courseImprovementScore(course, getDisplay) {
        const scores = (course && Array.isArray(course.spots) ? course.spots : [])
            .map(spot => normalizedImprovementScore(getDisplay(spot.code)))
            .filter(score => score != null && Number.isFinite(score));
        return scores.length ? scores.reduce((sum, score) => sum + score, 0) / scores.length : null;
    }
    function scoreColor(score) {
        if (score == null || !Number.isFinite(Number(score))) return 'hsl(40 35% 82%)';
        const ratio = Math.max(0, Math.min(1, Number(score)));
        const mix = key => HALO_TONES.low[key] + (HALO_TONES.high[key] - HALO_TONES.low[key]) * ratio;
        return `hsl(${mix('h').toFixed(1)} ${mix('s').toFixed(1)}% ${mix('l').toFixed(1)}%)`;
    }
    function haloColor(value, metric) {
        if (!HALO_SCALE[metric] || value == null || !Number.isFinite(Number(value))) return 'hsl(40 35% 82%)';
        return scoreColor(normalizedImprovementScore({value: Number(value), metric}));
    }
    function selectEffects(data, siteId) {
        const site = data && data[String(siteId)];
        return Array.isArray(site) ? site : [];
    }
    function selectMaximums(data, siteId) {
        const site = data && data[String(siteId)];
        return site && typeof site === 'object' && !Array.isArray(site)
            ? site : {stressSpotCodes: [], emotionalSpotCodes: []};
    }
    function isMaximum(maximums, metric, spotCode) {
        const key = metric === 'stress' ? 'stressSpotCodes' : metric === 'emotional' ? 'emotionalSpotCodes' : null;
        return Boolean(key && maximums && Array.isArray(maximums[key]) && maximums[key].includes(spotCode));
    }
    function semanticState(value, metric) {
        if (!metrics[metric] || value == null || !Number.isFinite(Number(value))) return 'missing';
        return Number(value) === 0 ? 'neutral' : 'good';
    }
    function metricColor(value, metric) {
        return haloColor(value, metric);
    }
    function metricValueRow(label, effect, metric) {
        const row = element('div', null, 'spot-information-item');
        const output = element('span', null, 'spot-metric-output');
        const state = semanticState(metricValue(effect, metric), metric);
        output.append(element('strong', metricRateWithUnit(effect, metric), 'effect-' + state));
        const count = metricCountDisplay(effect, metric);
        if (count) output.append(element('small', count, 'spot-metric-count'));
        row.append(element('span', label), output);
        return row;
    }
    function renderSpot(container, {participant, participantLabel, effect}) {
        container.replaceChildren();
        if (!effect || !effect.hasMeasurement) {
            container.append(element('p', '데이터 준비 중', 'survey-empty'));
            return;
        }
        if (participant !== 'all' && participantLabel) {
            container.append(element('p', participantLabel, 'spot-measurement-audience'));
        } else {
            const stressCount = effect.stressParticipantCount;
            const emotionalCount = effect.emotionalParticipantCount;
            const audience = stressCount == null || emotionalCount == null ? '측정 없음'
                : stressCount === emotionalCount ? stressCount + '명'
                : '스트레스 ' + stressCount + '명 · 정서 안정성 ' + emotionalCount + '명';
            container.append(valueRow('측정 인원', audience));
        }
        container.append(
            metricValueRow('평균 스트레스 개선율', effect, 'stress'),
            metricValueRow('평균 정서 안정성 개선율', effect, 'emotional')
        );
    }
    function renderMetricSection(metric, effects) {
        const section = element('section', null, 'survey-analysis-section');
        section.append(element('h3', metrics[metric].name));
        const grid = element('div', null, 'survey-summary-grid');
        effects.forEach(effect => {
            const card = element('article', null, 'survey-summary-card');
            card.append(element('strong', [effect.spotCode, effect.spotName].filter(Boolean).join(' · ')));
            card.append(element('p', metricRateWithUnit(effect, metric), 'effect-' + semanticState(metricValue(effect, metric), metric)));
            const count = metricCountDisplay(effect, metric);
            if (count) card.append(element('small', count, 'spot-metric-count'));
            grid.append(card);
        });
        if (!effects.length) grid.append(element('p', '데이터 준비 중', 'survey-empty'));
        section.append(grid);
        return section;
    }
    function renderSummary(container, effects) {
        container.replaceChildren();
        container.append(renderMetricSection('stress', effects), renderMetricSection('emotional', effects));
    }
    return {metrics, SPOT_METRIC, METRIC_ORDER, HALO_SCALE, metricValue, metricDisplay, metricCountDisplay,
        metricColor, semanticState, indexByCode, getMetricDisplay, getSpotMetricRows, getSpotDisplay,
        normalizedImprovementScore, courseImprovementScore, scoreColor, haloColor, selectEffects, selectMaximums,
        isMaximum, renderSpot, renderSummary, renderModal: renderSummary};
})();
