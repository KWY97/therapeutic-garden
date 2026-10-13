const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('Spot modal switches fixed histories, ignores stale responses, and reopens cleanly', async () => {
    const elements = {};
    const node = id => elements[id] ||= {
        id, hidden: id === 'spotDetailModal', textContent: '', style: {}, children: [], events: {},
        querySelector: () => node('dialog'), replaceChildren() { this.children = []; },
        append(...children) { this.children.push(...children); },
        addEventListener(name, listener) { this.events[name] = listener; },
        setAttribute() {}, removeAttribute() {}, focus() { document.activeElement = this; }
    };
    const previousFocus = node('previousFocus');
    const document = {activeElement: previousFocus, body: {style: {overflow: ''}},
        getElementById: node, querySelector: selector => node(selector),
        createElement: tag => node('created-' + tag + '-' + Math.random()),
        addEventListener() {}};
    const calls = {survey: [], history: []}, responses = [];
    const history = [
        {spotCode: 'HS1', records: [{measurementDate: '2026-09-02'}]},
        {spotCode: 'HS2', records: [{measurementDate: '2026-09-04'}]}
    ];
    const context = {document, window: {
        HomeSurvey: {indexByCode: effects => new Map(effects.map(effect => [effect.spotCode, effect])),
            renderSpot: (container, options) => calls.survey.push({container, options})},
        MeasurementHistory: {renderSpot: (container, data, code, options) =>
            calls.history.push({container, data, code, options})},
        memberMeasurementHistory: history, memberMonitoringEffects: [], memberDisplayName: '참가자'
    }, fetch: () => new Promise(resolve => responses.push(resolve))};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/member-spot-detail.js', 'utf8'), context);
    const detail = context.window.MemberSpotDetail;
    const hs1 = {spotId: 1, code: 'HS1', name: '첫 스팟'};
    const hs2 = {spotId: 2, code: 'HS2', name: '둘째 스팟'};
    const hs3 = {spotId: 3, code: 'HS3', name: '기록 없는 스팟'};
    const first = detail.open(hs1);
    assert.equal(calls.history.at(-1).code, 'HS1');
    assert.equal(calls.history.at(-1).container, node('spotMeasurementHistory'));
    assert.equal(calls.history.at(-1).options.participant, true);
    assert.equal(calls.history.at(-1).options.compact, true);
    assert.deepEqual(Array.from(calls.survey.at(-1).options.measurementDates), ['2026-09-02']);
    const second = detail.open(hs2);
    assert.equal(calls.history.at(-1).code, 'HS2');
    assert.deepEqual(Array.from(calls.survey.at(-1).options.measurementDates), ['2026-09-04']);
    responses[0]({ok: true, json: async () => ({...hs1, images: []})});
    await first;
    assert.equal(calls.history.at(-1).code, 'HS2');
    node('closeSpotDetailButton').events.click();
    responses[1]({ok: true, json: async () => ({...hs2, images: []})});
    await second;
    assert.equal(node('spotDetailModal').hidden, true);
    assert.equal(document.activeElement, previousFocus);
    const reopened = detail.open(hs1);
    assert.equal(calls.history.at(-1).code, 'HS1');
    responses[2]({ok: false});
    await reopened;
    assert.equal(node('spotDetailModal').hidden, false);
    const empty = detail.open(hs3);
    assert.equal(calls.history.at(-1).code, 'HS3');
    assert.equal(calls.survey.at(-1).options.effect, null);
    assert.deepEqual(Array.from(calls.survey.at(-1).options.measurementDates), []);
    responses[3]({ok: false});
    await empty;
});
