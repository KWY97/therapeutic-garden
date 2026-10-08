const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

let sequence = 0;
function node(tag) {
    return {tag, children: [], style: {}, textContent: '', className: '',
        append(...children) { this.children.push(...children); },
        replaceChildren(...children) { this.children = children; }};
}
const context = {window: {}, document: {createElement(tag) { return node(tag + (++sequence)); }}};
vm.createContext(context);
vm.runInContext(fs.readFileSync('src/main/resources/static/js/home-survey-analysis.js', 'utf8'), context);
const api = context.window.HomeSurvey;
const measured = {spotCode: 'HS1', spotName: '호스타 정원', hasMeasurement: true,
    stressReductionRate: 19.5, stressChangeDisplay: '19.5% 감소',
    emotionalIncreaseRate: -6.5, emotionalChangeDisplay: '6.5% 감소',
    stressParticipantCount: 11, emotionalParticipantCount: 10,
    stressValidSessionCount: 4, emotionalValidSessionCount: 3};
const missing = {spotCode: 'HS2', spotName: '곶자왈원', hasMeasurement: false,
    stressReductionRate: null, emotionalIncreaseRate: null};

test('semantic result colors match metric meaning and existing neutral palette', () => {
    for (const metric of ['stress','emotional']) {
        assert.equal(api.semanticState(24.8,metric),'good');
        assert.equal(api.semanticState(-13.3,metric),'bad');
        assert.equal(api.semanticState(0,metric),'neutral');
        assert.equal(api.semanticState(null,metric),'missing');
        assert.equal(api.metricColor(0,metric),'#86743f');
    }
    const container=node('div');
    api.renderSpot(container,{participant:'all',effect:measured});
    const rows=container.children.filter(n=>n.children.length);
    assert.equal(rows[1].children[1].className,'effect-good');
    assert.equal(rows[2].children[1].className,'effect-bad');
    assert.equal(rows[1].children[0].className,'');
});

test('direction formatter preserves raw sign and distinguishes zero from missing', () => {
    assert.equal(api.formatDirection(19.5, 'stress'), '19.5% 감소');
    assert.equal(api.formatDirection(-38.6, 'stress'), '38.6% 증가');
    assert.equal(api.formatDirection(54.3, 'emotional'), '54.3% 증가');
    assert.equal(api.formatDirection(-6.5, 'emotional'), '6.5% 감소');
    assert.equal(api.formatDirection(0, 'stress'), '0.0% 변화 없음');
    assert.equal(api.formatDirection(null, 'stress'), '측정 없음');
});

test('halo scale is continuous, clamped and configurable around neutral', () => {
    assert.deepEqual(JSON.parse(JSON.stringify(api.HALO_SCALE)),{
        stress:{neutral:0,min:-20,max:20},emotional:{neutral:0,min:-100,max:100}});
    assert.equal(api.haloColor(null,'stress'),'hsl(40 35% 82%)');
    assert.equal(api.haloColor(0,'stress'),'hsl(40.0 35.0% 82.0%)');
    assert.equal(api.haloColor(20,'stress'),api.haloColor(200,'stress'));
    assert.equal(api.haloColor(-20,'stress'),api.haloColor(-200,'stress'));
    assert.notEqual(api.haloColor(10,'stress'),api.haloColor(-10,'stress'));
});

test('fixed Spot metric display is the only field-selection and direction boundary', () => {
    const effects = [
        {...measured, spotCode:'HS1', stressReductionRate:12, emotionalIncreaseRate:-90},
        {...measured, spotCode:'HS2', stressReductionRate:-80, emotionalIncreaseRate:-15},
        {...measured, spotCode:'HS3', stressReductionRate:0},
        {...missing, spotCode:'HS4'}
    ];
    assert.deepEqual(JSON.parse(JSON.stringify(api.SPOT_METRIC)), {
        HS1:'stress',HS2:'emotional',HS3:'stress',HS4:'emotional',HS5:'emotional',HS6:'stress'});
    assert.deepEqual(JSON.parse(JSON.stringify(api.getSpotDisplay('HS1', effects))).value, 12);
    assert.equal(api.getSpotDisplay('HS1', effects).unitLabel, '개선');
    assert.equal(api.getSpotDisplay('HS2', effects).value, -15);
    assert.equal(api.getSpotDisplay('HS2', effects).unitLabel, '악화');
    assert.equal(api.getSpotDisplay('HS3', effects).unitLabel, '변화 없음');
    assert.equal(api.getSpotDisplay('HS4', effects).numberLabel, '데이터 없음');
    const rows=api.getSpotMetricRows('HS2',effects);
    assert.deepEqual(JSON.parse(JSON.stringify(rows.map(row=>row.metric))),['stress','emotional']);
    assert.deepEqual(JSON.parse(JSON.stringify(rows.map(row=>row.metricLabel))),['스트레스','정서 안정성']);
    assert.ok(rows.every(row=>!Object.hasOwn(row,'representative')));
});

test('Course halo score averages normalized mixed-metric Spot improvements once', () => {
    const effects = [
        {...measured, spotCode:'HS1', stressReductionRate:20},
        {...measured, spotCode:'HS2', emotionalIncreaseRate:-100}
    ];
    const lookup = api.indexByCode(effects);
    const score = api.courseImprovementScore({spots:[{code:'HS1'},{code:'HS2'}]},
        code => api.getSpotDisplay(code, lookup));
    assert.equal(score, .5);
    assert.equal(api.scoreColor(score), 'hsl(40.0 35.0% 82.0%)');
});

test('site selection accepts only the overall Monitoring Summary array', () => {
    const data = {'7': [measured], '8': {overall: [missing], members: {'3': [missing]}}};
    assert.equal(api.selectEffects(data, 7)[0].stressReductionRate, 19.5);
    assert.equal(api.selectEffects(data, 8).length, 0);
    assert.equal(api.selectEffects(data, 99).length, 0);
});

test('overall and member Spot detail keep count semantics and missing state', () => {
    const overall = node('div');
    api.renderSpot(overall, {participant: 'all', participantLabel: '전체 평균', effect: measured});
    assert.deepEqual(overall.children.filter(child => child.children.length).map(row => row.children[0].textContent),
        ['측정 인원', '평균 스트레스 증감률', '평균 정서 안정성 증감률']);
    assert.equal(overall.children[0].children[1].textContent,'스트레스 11명 · 정서 안정성 10명');
    const equal = node('div');
    api.renderSpot(equal, {participant: 'all', effect: {...measured, emotionalParticipantCount: 11}});
    assert.equal(equal.children[0].children[1].textContent,'11명');
    const member = node('div');
    api.renderSpot(member, {participant: '3', participantLabel: 'P003', effect: measured});
    assert.equal(member.children[0].textContent, 'P003');
    assert.equal(member.children[1].children[0].textContent, '스트레스 유효 측정');
    assert.equal(member.children[3].children[0].textContent, '정서 안정성 유효 측정');
    api.renderSpot(member, {participant: '3', participantLabel: 'P003', effect: missing});
    assert.equal(member.children[0].textContent, '측정 없음');
});

test('detail analysis renders two categorical Summary sections without line charts', () => {
    const container = node('div');
    api.renderSummary(container, [measured, missing]);
    assert.equal(container.children.length, 2);
    assert.equal(container.children[0].children[0].textContent, '스트레스 변화');
    assert.equal(container.children[1].children[0].textContent, '정서 안정성 변화');
    assert.doesNotMatch(fs.readFileSync('src/main/resources/static/js/home-survey-analysis.js', 'utf8'), /<svg|1차|ISI|PSS/);
});

test('personal highlights select only the largest positive improvement', () => {
    const effects=[measured,
        {...measured,spotCode:'HS3',spotName:'가든',stressReductionRate:24.8,stressChangeDisplay:'24.8% 감소',emotionalIncreaseRate:1423.5,emotionalChangeDisplay:'1423.5% 증가'},
        {...measured,spotCode:'HS4',stressReductionRate:-85.1,stressChangeDisplay:'85.1% 증가',emotionalIncreaseRate:0,emotionalChangeDisplay:'0.0% 변화 없음'},missing];
    assert.equal(api.bestImprovement(effects,'stress').spotCode,'HS3');
    assert.equal(api.bestImprovement(effects,'emotional').spotCode,'HS3');
    const container=node('div');api.renderHighlights(container,effects);
    const cards=container.children[0].children[1].children;
    assert.equal(cards.length,2);
    assert.equal(cards[0].children[1].textContent,'HS3 · 가든');
    assert.equal(cards[0].children[2].className,'effect-good');
});

test('personal highlights use neutral honest state when no Spot improved', () => {
    const effects=[{...measured,stressReductionRate:0,emotionalIncreaseRate:-1},missing];
    assert.equal(api.bestImprovement(effects,'stress'),null);
    assert.equal(api.bestImprovement(effects,'emotional'),null);
    const container=node('div');api.renderHighlights(container,effects);
    const cards=container.children[0].children[1].children;
    assert.equal(cards[0].children[1].textContent,'개선된 스팟 없음');
    assert.equal(cards[0].children[1].className,'effect-neutral');
});
