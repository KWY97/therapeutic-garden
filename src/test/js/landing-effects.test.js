const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/landing.js', 'utf8');

function element(dataset = {}) {
    const classes = new Set();
    return {dataset, style: {}, textContent: '', attrs: {}, events: {}, one: {}, many: {},
        classList: {toggle(name, active) { active ? classes.add(name) : classes.delete(name); },
            contains(name) { return classes.has(name); }, add(name) { classes.add(name); }, remove(name) { classes.delete(name); }},
        setAttribute(name, value) { this.attrs[name] = value; },
        addEventListener(name, fn) {
            const previous = this.events[name];
            this.events[name] = previous ? event => { previous(event); fn(event); } : fn;
        },
        contains(target) { return target === this || Object.values(this.one).includes(target); },
        querySelector(selector) { return this.one[selector] || null; },
        querySelectorAll(selector) { return this.many[selector] || []; }};
}
function boot({reduced = false, empty = false} = {}) {
    const changes = [];
    const motion = {matches: reduced, addEventListener(_, fn) { changes.push(fn); }};
    const page = element(); const chart = element(); const course = element();
    const stressRates = [100, 100, 33.333, 66.667, 0, 50];
    const emotionalRates = [100, 50, 33.333, 66.667, 0, 50];
    const spots = Array.from({length: 6}, (_, index) => {
        const n = element({personalSpot: `HS${index+1}`, name: `Spot ${index+1}`,
            stress: String(stressRates[index]), emotional: String(emotionalRates[index]),
            stressRateDisplay: stressRates[index].toFixed(1) + '%',
            emotionalRateDisplay: emotionalRates[index].toFixed(1) + '%',
            stressDisplay: stressRates[index].toFixed(1) + '% 개선',
            emotionalDisplay: emotionalRates[index].toFixed(1) + '% 개선',
            stressCount: '3회 중 2회 개선', emotionalCount: '3회 중 1회 개선',
            stressMaximum: String(index === 2 || index === 4 || index === 5),
            emotionalMaximum: String(index === 4 || index === 5)});
        n.one['[data-comparison-value]'] = element();
        n.one['[data-comparison-count]'] = element();
        n.one['.landing-comparison-track i'] = element();
        return n;
    });
    const metrics = ['stress', 'emotional'].map(personalMetric => element({personalMetric}));
    chart.many['[data-personal-spot]'] = spots;
    chart.many['[data-personal-metric]'] = metrics;
    for (const selector of ['[data-current-spot]', '[data-current-metric]', '[data-current-rate]', '[data-current-count]']) chart.one[selector] = element();
    page.one['[data-personal-change-chart]'] = empty ? null : chart;
    course.one['[data-selection-status]'] = element();
    chart.one['[data-selection-status]'] = element();
    const buttons = [element({code: 'HS1', name: 'one', stress: empty ? undefined : '88.2% 개선', emotional: empty ? undefined : '58.8% 개선'}),
        element({code: 'HS2', name: 'two', stress: empty ? undefined : '81.3% 개선', emotional: empty ? undefined : '68.8% 개선'})];
    course.many['.landing-course-spots button'] = buttons;
    for (const code of ['HS1', 'HS2']) course.one[`[data-spot-image="${code}"]`] = element();
    for (const field of ['stress-reduction', 'emotional-increase']) course.one[`[data-field="${field}"]`] = element();
    page.many['.landing-course-summary'] = [course];
    const document = element(); document.hidden = false; document.one['.landing-page'] = page;
    const window = {matchMedia() { return motion; }};
    vm.runInNewContext(source, {document, window});
    return {chart, course, spots, metrics, buttons, changes, motion, document,
        value: () => chart.one['[data-current-rate]'].textContent};
}

test('personal metric and six independent Spot selectors show server-formatted improvement rates', () => {
    const ui = boot();
    assert.equal(ui.value(), '100.0%');
    assert.equal(ui.chart.one['[data-current-count]'].textContent, '3회 중 2회 개선');
    assert.equal(ui.spots[0].one['.landing-comparison-track i'].style.bottom, '0');
    ui.metrics[1].events.click();
    assert.equal(ui.value(), '100.0%');
    assert.equal(ui.chart.dataset.activeMetric, 'emotional');
    ui.spots[5].events.click();
    assert.equal(ui.chart.one['[data-current-spot]'].textContent, 'HS6 · Spot 6');
    assert.equal(ui.spots[5].attrs['aria-pressed'], 'true');
    ui.metrics[0].events.click();
    assert.equal(ui.value(), '50.0%');
    assert.equal(ui.spots[5].one['[data-comparison-value]'].textContent, '50.0% 개선');
    assert.equal(ui.spots[5].one['[data-comparison-count]'].textContent, '3회 중 2회');
    assert.match(ui.spots[5].attrs['aria-label'], /최대 개선/);
    assert.doesNotMatch(ui.spots[5].attrs['aria-label'], /스트레스 최대 개선|정서 안정성 최대 개선/);
});
test('garden carousel changes image, active selector and both SSR effect displays together', () => {
    const ui = boot(); ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].textContent, '81.3% 개선');
    assert.equal(ui.course.one['[data-field="emotional-increase"]'].textContent, '68.8% 개선');
    assert.equal(ui.course.one['[data-spot-image="HS2"]'].attrs['aria-hidden'], 'false');
    assert.equal(ui.buttons[0].attrs['aria-pressed'], 'false');
    assert.equal(ui.buttons[1].attrs['aria-pressed'], 'true');
});
test('empty data never creates sample numbers', () => {
    const ui = boot({empty: true}); ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].textContent, '데이터 준비 중');
    assert.equal(ui.course.one['[data-field="emotional-increase"]'].textContent, '데이터 준비 중');
});
test('manual selectors work immediately with reduced motion and never require timer APIs', () => {
    const ui = boot({reduced: true});
    ui.spots[4].events.click(); ui.metrics[1].events.click();
    assert.equal(ui.value(), '0.0%');
    ui.motion.matches = false; ui.changes.forEach(fn => fn({matches: false}));
    ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].textContent, '81.3% 개선');
});

test('improvement rates use improvement semantics, and only manual choices announce', () => {
    const ui = boot();
    const rate = ui.chart.one['[data-current-rate]'];
    assert.equal(rate.dataset.effectStatus, 'improved');
    assert.equal(ui.chart.one['[data-selection-status]'].textContent, '');
    ui.metrics[1].events.click();
    assert.equal(rate.dataset.effectStatus, 'improved');
    ui.spots[4].events.click();
    assert.equal(rate.dataset.effectStatus, 'improved');
    assert.match(ui.chart.one['[data-selection-status]'].textContent, /HS5.*0.0% 개선/);
    ui.metrics[0].events.click();
    assert.equal(rate.dataset.effectStatus, 'improved');
    ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].dataset.effectStatus, 'improved');
    assert.match(ui.course.one['[data-selection-status]'].textContent, /HS2.*81.3% 개선/);
});
