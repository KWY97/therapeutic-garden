const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

let sequence = 0;
function node(tag) {
    return {tag, children: [], style: {}, textContent: '', className: '', hidden: false,
        append(...children) { this.children.push(...children); },
        replaceChildren(...children) { this.children = children; }};
}
const context = {window: {}, document: {createElement(tag) { return node(tag + (++sequence)); }}};
const source = fs.readFileSync('src/main/resources/static/js/home-survey-analysis.js', 'utf8');
vm.createContext(context);
vm.runInContext(source, context);
const api = context.window.HomeSurvey;
const metric = (validCount, improvedCount, rate, display) => ({validCount, improvedCount,
    improvementRate: rate, improvementRateDisplay: display,
    improvementCountDisplay: validCount ? `${validCount}회 중 ${improvedCount}회 개선` : '측정 없음'});
const measured = {spotCode: 'HS1', spotName: '호스타 정원', hasMeasurement: true,
    stress: metric(17, 15, 88.235294117647, '88.2%'),
    emotional: metric(17, 10, 58.823529411765, '58.8%'),
    stressParticipantCount: 11, emotionalParticipantCount: 10};
const zeroValid = {spotCode: 'HS2', spotName: '곶자왈원', hasMeasurement: true,
    stress: metric(0, 0, null, '측정 없음'), emotional: metric(0, 0, null, '측정 없음'),
    stressParticipantCount: 0, emotionalParticipantCount: 0};
const preparing = {spotCode: 'HS3', spotName: '가든 위스퍼스', hasMeasurement: true,
    stress: null, emotional: null, stressParticipantCount: 3, emotionalParticipantCount: 3};
const missing = {spotCode: 'HS4', spotName: '콜로네이드 가든', hasMeasurement: false,
    stress: null, emotional: null};

test('uses server-formatted count-backed rates and never recalculates display percentages', () => {
    assert.equal(api.metricValue(measured, 'stress'), 88.235294117647);
    assert.equal(api.metricDisplay(measured, 'stress'), '88.2%');
    assert.equal(api.metricCountDisplay(measured, 'stress'), '17회 중 15회 개선');
    assert.equal(api.getMetricDisplay(measured, 'stress').numberLabel, '88.2%');
    assert.equal(api.getMetricDisplay(measured, 'stress').unitLabel, '개선');
    assert.equal(api.semanticState(88.2, 'stress'), 'good');
    assert.equal(api.semanticState(0, 'emotional'), 'neutral');
    assert.equal(api.semanticState(null, 'stress'), 'missing');
});

test('zero valid measurements and missing count data remain honest unavailable states', () => {
    assert.equal(api.metricDisplay(zeroValid, 'stress'), '측정 없음');
    assert.equal(api.metricCountDisplay(zeroValid, 'stress'), '');
    assert.equal(api.metricDisplay(preparing, 'stress'), '데이터 준비 중');
    assert.equal(api.metricDisplay(missing, 'stress'), '데이터 준비 중');
    assert.equal(api.getSpotDisplay('HS2', [zeroValid]).numberLabel, '측정 없음');
    assert.equal(api.getSpotDisplay('HS3', [preparing]).numberLabel, '데이터 준비 중');
});

test('halo scale maps 0 to low and 100 to high without negative or worsening semantics', () => {
    assert.deepEqual(JSON.parse(JSON.stringify(api.HALO_SCALE)), {
        stress: {min: 0, max: 100}, emotional: {min: 0, max: 100}});
    assert.equal(api.normalizedImprovementScore({metric: 'stress', value: 0}), 0);
    assert.equal(api.normalizedImprovementScore({metric: 'stress', value: 50}), .5);
    assert.equal(api.normalizedImprovementScore({metric: 'stress', value: 100}), 1);
    assert.equal(api.normalizedImprovementScore({metric: 'stress', value: 200}), 1);
    assert.equal(api.haloColor(null, 'stress'), 'hsl(40 35% 82%)');
    assert.notEqual(api.haloColor(0, 'stress'), api.haloColor(100, 'stress'));
});

test('fixed representative metric policy covers HS1-HS6 and hover keeps both metrics', () => {
    assert.deepEqual(JSON.parse(JSON.stringify(api.SPOT_METRIC)), {
        HS1: 'stress', HS2: 'emotional', HS3: 'stress',
        HS4: 'emotional', HS5: 'stress', HS6: 'emotional'});
    assert.equal(api.getSpotDisplay('HS1', [measured]).metricLabel, '스트레스');
    assert.equal(api.getSpotDisplay('HS1', [measured]).countLabel, '17회 중 15회 개선');
    const rows = api.getSpotMetricRows('HS1', [measured]);
    assert.deepEqual(JSON.parse(JSON.stringify(rows.map(row => row.metric))), ['stress', 'emotional']);
    assert.deepEqual(JSON.parse(JSON.stringify(rows.map(row => row.metricLabel))), ['스트레스', '정서 안정성']);
    assert.deepEqual(JSON.parse(JSON.stringify(rows.map(row => row.countLabel))),
        ['17회 중 15회 개선', '17회 중 10회 개선']);
});

test('HS1-HS6 representative cards select the expected server-provided rate and count', () => {
    const effects = [
        ['HS1', metric(17, 15, 88.235294117647, '88.2%'), metric(17, 10, 58.823529411765, '58.8%')],
        ['HS2', metric(16, 13, 81.25, '81.3%'), metric(16, 11, 68.75, '68.8%')],
        ['HS3', metric(22, 16, 72.727272727273, '72.7%'), metric(22, 15, 68.181818181818, '68.2%')],
        ['HS4', metric(23, 13, 56.521739130435, '56.5%'), metric(23, 16, 69.565217391304, '69.6%')],
        ['HS5', metric(17, 11, 64.705882352941, '64.7%'), metric(17, 11, 64.705882352941, '64.7%')],
        ['HS6', metric(18, 13, 72.222222222222, '72.2%'), metric(18, 16, 88.888888888889, '88.9%')]
    ].map(([spotCode, stress, emotional]) => ({spotCode, hasMeasurement: true, stress, emotional}));
    assert.deepEqual(effects.map(effect => {
        const display = api.getSpotDisplay(effect.spotCode, effects);
        return [display.metricLabel, display.numberLabel, display.countLabel];
    }), [
        ['스트레스', '88.2%', '17회 중 15회 개선'],
        ['정서 안정성', '68.8%', '16회 중 11회 개선'],
        ['스트레스', '72.7%', '22회 중 16회 개선'],
        ['정서 안정성', '69.6%', '23회 중 16회 개선'],
        ['스트레스', '64.7%', '17회 중 11회 개선'],
        ['정서 안정성', '88.9%', '18회 중 16회 개선']
    ]);
});

test('Course halo score averages representative count-backed improvement rates', () => {
    const effects = [
        {...measured, spotCode: 'HS1', stress: metric(10, 10, 100, '100.0%')},
        {...measured, spotCode: 'HS2', emotional: metric(10, 5, 50, '50.0%')}
    ];
    const lookup = api.indexByCode(effects);
    const score = api.courseImprovementScore({spots: [{code: 'HS1'}, {code: 'HS2'}]},
        code => api.getSpotDisplay(code, lookup));
    assert.equal(score, .75);
    assert.equal(api.scoreColor(null), 'hsl(40 35% 82%)');
});

test('site selection keeps effects and server-selected maximums separate', () => {
    const effectData = {'7': [measured], '8': {overall: [missing]}};
    const maximumData = {'7': {stressSpotCodes: ['HS1'], emotionalSpotCodes: ['HS6']}};
    assert.equal(api.selectEffects(effectData, 7)[0].stress.improvementRateDisplay, '88.2%');
    assert.equal(api.selectEffects(effectData, 8).length, 0);
    const maximums = api.selectMaximums(maximumData, 7);
    assert.equal(api.isMaximum(maximums, 'stress', 'HS1'), true);
    assert.equal(api.isMaximum(maximums, 'emotional', 'HS1'), false);
    assert.deepEqual(JSON.parse(JSON.stringify(api.selectMaximums(maximumData, 99))),
        {stressSpotCodes: [], emotionalSpotCodes: []});
});

test('Spot detail preserves participant counts and shows both improvement rates with counts', () => {
    const overall = node('div');
    api.renderSpot(overall, {participant: 'all', participantLabel: '전체 평균', effect: measured});
    assert.deepEqual(overall.children.map(row => row.children[0].textContent),
        ['측정 인원', '평균 스트레스 개선율', '평균 정서 안정성 개선율']);
    assert.equal(overall.children[0].children[1].textContent, '스트레스 11명 · 정서 안정성 10명');
    assert.equal(overall.children[1].children[1].children[0].textContent, '88.2% 개선');
    assert.equal(overall.children[1].children[1].children[1].textContent, '17회 중 15회 개선');
    assert.equal(overall.children[2].children[1].children[0].textContent, '58.8% 개선');
    const equal = node('div');
    api.renderSpot(equal, {participant: 'all', effect: {...measured, emotionalParticipantCount: 11}});
    assert.equal(equal.children[0].children[1].textContent, '11명');
});

test('Spot detail omits misleading zero counts and distinguishes unavailable data', () => {
    const zero = node('div');
    api.renderSpot(zero, {participant: 'all', effect: zeroValid});
    assert.equal(zero.children[1].children[1].children[0].textContent, '측정 없음');
    assert.equal(zero.children[1].children[1].children.length, 1);
    const empty = node('div');
    api.renderSpot(empty, {participant: 'all', effect: missing});
    assert.equal(empty.children[0].textContent, '데이터 준비 중');
});

test('summary remains count-backed and old average/max calculation fields are absent', () => {
    const container = node('div');
    api.renderSummary(container, [measured, missing]);
    assert.equal(container.children[0].children[0].textContent, '스트레스 개선율');
    assert.equal(container.children[1].children[0].textContent, '정서 안정성 개선율');
    assert.equal(api.bestImprovement, undefined);
    assert.doesNotMatch(source, /stressReductionRate|emotionalIncreaseRate|bestImprovement|평균 스트레스 증감률|악화/);
});
