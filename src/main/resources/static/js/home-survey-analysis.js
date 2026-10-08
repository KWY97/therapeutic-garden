/* Healing Effect Summary presentation. No round/date/visit values are derived here. */
window.HomeSurvey = (() => {
    'use strict';
    const metrics = {
        stress: {name: '스트레스 변화', rate: 'stressReductionRate', display: 'stressChangeDisplay'},
        emotional: {name: '정서 안정성 변화', rate: 'emotionalIncreaseRate', display: 'emotionalChangeDisplay'}
    };
    const SPOT_METRIC = Object.freeze({
        HS1: 'stress', HS2: 'emotional', HS3: 'stress',
        HS4: 'emotional', HS5: 'emotional', HS6: 'stress'
    });
    const METRIC_ORDER = Object.freeze(['stress', 'emotional']);
    const HALO_SCALE = Object.freeze({
        stress: Object.freeze({neutral: 0, min: -20, max: 20}),
        emotional: Object.freeze({neutral: 0, min: -100, max: 100})
    });
    const HALO_TONES = Object.freeze({
        neutral: Object.freeze({h: 40, s: 35, l: 82}),
        good: Object.freeze({h: 150, s: 45, l: 40}),
        bad: Object.freeze({h: 8, s: 58, l: 50})
    });
    const element = (tag, text, className) => {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        if (className) node.className = className;
        return node;
    };
    function valueRow(label, value, state) {
        const row = element('div', null, 'spot-information-item');
        row.append(element('span', label), element('strong', value, state ? 'effect-' + state : ''));
        return row;
    }
    function formatDirection(value, metric) {
        if (value == null || !Number.isFinite(Number(value))) return '측정 없음';
        const number = Number(value);
        if (number === 0) return '0.0% 변화 없음';
        const direction = metric === 'stress'
            ? (number > 0 ? '감소' : '증가')
            : (number > 0 ? '증가' : '감소');
        return Math.abs(number).toFixed(1) + '% ' + direction;
    }
    function metricValue(effect, metric) {
        if (!effect || !effect.hasMeasurement || !metrics[metric]) return null;
        const value = Number(effect[metrics[metric].rate]);
        return Number.isFinite(value) ? value : null;
    }
    function metricDisplay(effect, metric) {
        if (!effect || !effect.hasMeasurement) return '측정 없음';
        return effect[metrics[metric].display] || formatDirection(metricValue(effect, metric), metric);
    }
    function indexByCode(effects) {
        return new Map((Array.isArray(effects) ? effects : []).map(effect => [effect.spotCode, effect]));
    }
    // Single read boundary for the fixed Spot metric presentation. Tomorrow's data semantic change belongs here.
    function getSpotDisplay(spotId, effects) {
        const metric = SPOT_METRIC[spotId];
        const lookup = effects instanceof Map ? effects : indexByCode(effects);
        const effect = metric ? lookup.get(spotId) : null;
        const display = getMetricDisplay(effect, metric);
        return {
            metric: metric || null,
            metricLabel: display.metricLabel,
            value: display.value,
            numberLabel: display.numberLabel,
            unitLabel: display.unitLabel,
            status: display.status,
            color: display.value == null || display.value === 0 ? 'hsl(40 35% 82%)' : haloColor(display.value, metric)
        };
    }
    function getMetricDisplay(effect, metric) {
        const value = metrics[metric] ? metricValue(effect, metric) : null;
        const status = semanticState(value, metric);
        return {
            metric,
            metricLabel: metric === 'stress' ? '스트레스' : metric === 'emotional' ? '정서 안정성' : '',
            value,
            numberLabel: value == null ? '데이터 없음' : Math.abs(value).toFixed(1) + '%',
            unitLabel: value == null ? '' : status === 'good' ? '개선' : status === 'bad' ? '악화' : '변화 없음',
            status
        };
    }
    function getSpotMetricRows(spotCode, effects) {
        const lookup = effects instanceof Map ? effects : indexByCode(effects);
        const effect = lookup.get(spotCode);
        return METRIC_ORDER.map(metric => getMetricDisplay(effect, metric));
    }
    function normalizedImprovementScore(display) {
        if (!display || display.value == null || !HALO_SCALE[display.metric]) return null;
        const scale = HALO_SCALE[display.metric], value = display.value;
        if (value >= scale.neutral) {
            const range = scale.max - scale.neutral;
            return .5 + .5 * (range ? Math.min(1, (value - scale.neutral) / range) : 0);
        }
        const range = scale.neutral - scale.min;
        return .5 - .5 * (range ? Math.min(1, (scale.neutral - value) / range) : 0);
    }
    function courseImprovementScore(course, getDisplay) {
        const scores = (course && Array.isArray(course.spots) ? course.spots : [])
            .map(spot => normalizedImprovementScore(getDisplay(spot.code)))
            .filter(score => score != null && Number.isFinite(score));
        return scores.length ? scores.reduce((sum, score) => sum + score, 0) / scores.length : null;
    }
    function scoreColor(score) {
        if (score == null || !Number.isFinite(Number(score))) return 'hsl(40 35% 82%)';
        const clamped = Math.max(0, Math.min(1, Number(score)));
        const improving = clamped >= .5;
        const ratio = Math.abs(clamped - .5) * 2;
        const target = improving ? HALO_TONES.good : HALO_TONES.bad;
        const mix = key => HALO_TONES.neutral[key] + (target[key] - HALO_TONES.neutral[key]) * ratio;
        return `hsl(${mix('h').toFixed(1)} ${mix('s').toFixed(1)}% ${mix('l').toFixed(1)}%)`;
    }
    function haloColor(value, metric) {
        const scale = HALO_SCALE[metric];
        if (!scale || value == null || !Number.isFinite(Number(value))) return 'hsl(40 35% 82%)';
        const number = Number(value), improving = number >= scale.neutral;
        const limit = improving ? scale.max : scale.min;
        const range = Math.abs(limit - scale.neutral);
        const ratio = range ? Math.min(1, Math.abs(number - scale.neutral) / range) : 0;
        const target = improving ? HALO_TONES.good : HALO_TONES.bad;
        const mix = key => HALO_TONES.neutral[key] + (target[key] - HALO_TONES.neutral[key]) * ratio;
        return `hsl(${mix('h').toFixed(1)} ${mix('s').toFixed(1)}% ${mix('l').toFixed(1)}%)`;
    }
    function selectEffects(data, siteId) {
        const site = data && data[String(siteId)];
        return Array.isArray(site) ? site : [];
    }
    function semanticState(value, metric) {
        if (!metrics[metric] || value == null || !Number.isFinite(Number(value))) return 'missing';
        // These fields encode reduction for stress and increase for emotional stability.
        const direction = metric === 'stress'
            ? (Number(value) > 0 ? 'decrease' : 'increase')
            : (Number(value) > 0 ? 'increase' : 'decrease');
        if (Number(value) === 0) return 'neutral';
        return direction === (metric === 'stress' ? 'decrease' : 'increase') ? 'good' : 'bad';
    }
    function metricColor(value, metric) {
        return {good: '#347553', bad: '#b4534b', neutral: '#86743f', missing: '#9ca3af'}[semanticState(value, metric)];
    }
    function renderSpot(container, {participant, participantLabel, effect}) {
        container.replaceChildren();
        if (!effect || !effect.hasMeasurement) {
            container.append(element('p', '측정 없음', 'survey-empty'));
            return;
        }
        if (participant === 'all') {
            const stressCount = effect.stressParticipantCount;
            const emotionalCount = effect.emotionalParticipantCount;
            const audience = stressCount === emotionalCount
                ? stressCount + '명'
                : '스트레스 ' + stressCount + '명 · 정서 안정성 ' + emotionalCount + '명';
            container.append(
                valueRow('측정 인원', audience),
                valueRow('평균 스트레스 증감률', metricDisplay(effect, 'stress'), semanticState(metricValue(effect, 'stress'), 'stress')),
                valueRow('평균 정서 안정성 증감률', metricDisplay(effect, 'emotional'), semanticState(metricValue(effect, 'emotional'), 'emotional'))
            );
        } else {
            container.append(
                element('p', participantLabel, 'spot-measurement-audience'),
                valueRow('스트레스 유효 측정', effect.stressValidSessionCount + '회'),
                valueRow('평균 스트레스 증감률', metricDisplay(effect, 'stress'), semanticState(metricValue(effect, 'stress'), 'stress')),
                valueRow('정서 안정성 유효 측정', effect.emotionalValidSessionCount + '회'),
                valueRow('평균 정서 안정성 증감률', metricDisplay(effect, 'emotional'), semanticState(metricValue(effect, 'emotional'), 'emotional'))
            );
        }
    }
    function renderMetricSection(metric, effects) {
        const section = element('section', null, 'survey-analysis-section');
        section.append(element('h3', metrics[metric].name));
        const grid = element('div', null, 'survey-summary-grid');
        const values = effects.map(effect => metricValue(effect, metric)).filter(value => value != null);
        const scale = Math.max(1, ...values.map(Math.abs));
        effects.forEach(effect => {
            const card = element('article', null, 'survey-summary-card');
            card.append(element('strong', [effect.spotCode, effect.spotName].filter(Boolean).join(' · ')));
            const value = metricValue(effect, metric);
            const state = semanticState(value, metric);
            card.append(element('p', metricDisplay(effect, metric), 'effect-' + state));
            if (value != null) {
                const track = element('div', null, 'survey-summary-track');
                const bar = element('span', null, 'effect-' + state);
                bar.style.width = Math.abs(value) / scale * 100 + '%';
                track.append(bar);
                card.append(track);
            }
            grid.append(card);
        });
        if (!effects.length) grid.append(element('p', '측정 없음', 'survey-empty'));
        section.append(grid);
        return section;
    }
    function renderSummary(container, effects) {
        container.replaceChildren();
        container.append(renderMetricSection('stress', effects), renderMetricSection('emotional', effects));
    }
    function bestImprovement(effects, metric) {
        return effects.filter(effect => effect && effect.hasMeasurement && metricValue(effect, metric) > 0)
            .reduce((best, effect) => !best || metricValue(effect, metric) > metricValue(best, metric) ? effect : best, null);
    }
    function renderHighlights(container, effects) {
        container.replaceChildren();
        const section = element('section', null, 'survey-analysis-section survey-highlight-section');
        section.append(element('h3', '핵심 변화'));
        const grid = element('div', null, 'survey-highlight-grid');
        [['stress', '스트레스 가장 큰 개선'], ['emotional', '정서 안정성 가장 큰 개선']].forEach(([metric, title]) => {
            const card = element('article', null, 'survey-highlight-card');
            card.append(element('span', title, 'survey-highlight-label'));
            const best = bestImprovement(effects, metric);
            if (best) {
                card.append(element('strong', [best.spotCode, best.spotName].filter(Boolean).join(' · ')));
                card.append(element('p', metricDisplay(best, metric), 'effect-good'));
            } else card.append(element('p', '개선된 스팟 없음', 'effect-neutral'));
            grid.append(card);
        });
        section.append(grid); container.append(section);
    }
    return {metrics, SPOT_METRIC, METRIC_ORDER, HALO_SCALE, formatDirection, metricValue, metricDisplay, metricColor, semanticState, indexByCode,
        getMetricDisplay, getSpotMetricRows, getSpotDisplay, normalizedImprovementScore, courseImprovementScore, scoreColor,
        haloColor, selectEffects, renderSpot, renderSummary, renderModal: renderSummary, bestImprovement, renderHighlights};
})();
