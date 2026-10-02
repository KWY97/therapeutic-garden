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
function boot({reduced = false, empty = false, controls = false} = {}) {
    const timers = new Map(); let sequence = 0;
    const changes = [];
    const motion = {matches: reduced, addEventListener(_, fn) { changes.push(fn); }};
    const page = element(); const chart = element(); const course = element();
    const spots = Array.from({length: 6}, (_, index) => {
        const n = element({personalSpot: `HS${index+1}`, name: `Spot ${index+1}`,
            stress: index ? '0' : '-38.6', emotional: index === 4 ? '-6.5' : '1423.513',
            stressDisplay: index ? '0.0% 변화 없음' : '38.6% 증가',
            emotionalDisplay: index === 4 ? '6.5% 감소' : '1423.5% 증가'});
        n.one['[data-comparison-value]'] = element();
        n.one['.landing-comparison-track i'] = element();
        return n;
    });
    const metrics = ['stress', 'emotional'].map(personalMetric => element({personalMetric}));
    chart.many['[data-personal-spot]'] = spots;
    chart.many['[data-personal-metric]'] = metrics;
    for (const selector of ['[data-current-spot]', '[data-current-metric]', '[data-current-rate]']) chart.one[selector] = element();
    page.one['[data-personal-change-chart]'] = empty ? null : chart;
    if (controls) {
        course.one['[data-rotation-toggle]'] = element();
        chart.one['[data-rotation-toggle]'] = element();
        course.one['[data-selection-status]'] = element();
        chart.one['[data-selection-status]'] = element();
    }
    const buttons = [element({code: 'HS1', name: 'one', stress: empty ? undefined : '19.5% 감소', emotional: empty ? undefined : '54.3% 증가'}),
        element({code: 'HS2', name: 'two', stress: empty ? undefined : '10.8% 감소', emotional: empty ? undefined : '29.2% 증가'})];
    course.many['.landing-course-spots button'] = buttons;
    for (const code of ['HS1', 'HS2']) course.one[`[data-spot-image="${code}"]`] = element();
    for (const field of ['stress-reduction', 'emotional-increase']) course.one[`[data-field="${field}"]`] = element();
    page.many['.landing-course-summary'] = [course];
    const document = element(); document.hidden = false; document.one['.landing-page'] = page;
    const window = {matchMedia() { return motion; }, clearTimeout(id) { timers.delete(id); },
        setTimeout(fn, delay) { const id = ++sequence; timers.set(id, {fn, delay}); return id; }};
    vm.runInNewContext(source, {document, window});
    return {chart, course, spots, metrics, buttons, timers, changes, motion, document,
        value: () => chart.one['[data-current-rate]'].textContent};
}

test('personal metric and six independent Spot selectors preserve raw sign for bars and show directional text', () => {
    const ui = boot();
    assert.equal(ui.value(), '38.6% 증가');
    assert.equal(ui.spots[0].one['.landing-comparison-track i'].style.top, '50%');
    ui.metrics[1].events.click();
    assert.equal(ui.value(), '1423.5% 증가');
    assert.equal(ui.chart.dataset.activeMetric, 'emotional');
    ui.spots[5].events.click();
    assert.equal(ui.chart.one['[data-current-spot]'].textContent, 'HS6 · Spot 6');
    assert.equal(ui.spots[5].attrs['aria-pressed'], 'true');
    ui.metrics[0].events.click();
    assert.equal(ui.value(), '0.0% 변화 없음');
    assert.equal(ui.spots[5].one['[data-comparison-value]'].textContent, '0.0% 변화 없음');
    assert.ok([...ui.timers.values()].some(t => t.delay === 8500));
});
test('garden carousel changes image, active selector and both SSR effect displays together', () => {
    const ui = boot(); ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].textContent, '10.8% 감소');
    assert.equal(ui.course.one['[data-field="emotional-increase"]'].textContent, '29.2% 증가');
    assert.equal(ui.course.one['[data-spot-image="HS2"]'].attrs['aria-hidden'], 'false');
    assert.equal(ui.buttons[0].attrs['aria-pressed'], 'false');
    assert.equal(ui.buttons[1].attrs['aria-pressed'], 'true');
});
test('empty data never creates sample numbers', () => {
    const ui = boot({empty: true}); ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].textContent, '데이터 준비 중');
    assert.equal(ui.course.one['[data-field="emotional-increase"]'].textContent, '데이터 준비 중');
});
test('reduced motion stops auto browsing and still allows immediate selections', () => {
    const ui = boot({reduced: true});
    assert.equal(ui.timers.size, 0);
    ui.spots[4].events.click(); ui.metrics[1].events.click();
    assert.equal(ui.value(), '6.5% 감소'); assert.equal(ui.timers.size, 0);
    ui.motion.matches = false; ui.changes.forEach(fn => fn({matches: false}));
    assert.equal(ui.timers.size, 2);
    ui.motion.matches = true; ui.changes.forEach(fn => fn({matches: true}));
    assert.equal(ui.timers.size, 0);
});
test('automatic browsing advances only the selected Spot, keeping the chosen metric', () => {
    const ui = boot();
    const [id, timer] = [...ui.timers].find(([, t]) => t.delay === 6500);
    ui.timers.delete(id); timer.fn();
    assert.equal(ui.spots[1].attrs['aria-pressed'], 'true');
    assert.equal(ui.chart.dataset.activeMetric, 'stress');
});

test('explicit pause persists through pointer, focus, visibility and motion changes', () => {
    const ui = boot({controls: true});
    const pause = ui.course.one['[data-rotation-toggle]'];
    pause.events.click();
    assert.equal(pause.attrs['aria-pressed'], 'true');
    assert.equal(ui.timers.size, 1);
    ui.course.events.pointerenter();
    ui.course.events.pointerleave();
    ui.document.hidden = true; ui.document.events.visibilitychange();
    assert.equal(ui.timers.size, 0);
    ui.document.hidden = false; ui.document.events.visibilitychange();
    assert.equal(ui.timers.size, 1);
    ui.motion.matches = true; ui.changes.forEach(fn => fn({matches: true}));
    assert.equal(pause.disabled, true); assert.equal(ui.timers.size, 0);
    ui.motion.matches = false; ui.changes.forEach(fn => fn({matches: false}));
    assert.equal(ui.timers.size, 1);
    pause.events.click();
    assert.equal(ui.timers.size, 2);
    ui.course.events.focusin();
    assert.equal(ui.timers.size, 1);
    ui.course.events.focusout({relatedTarget: null});
    assert.equal(ui.timers.size, 2);
});

test('semantic colors distinguish metric directions, and only manual choices announce', () => {
    const ui = boot({controls: true});
    const rate = ui.chart.one['[data-current-rate]'];
    assert.equal(rate.dataset.effectStatus, 'worsened');
    assert.equal(ui.chart.one['[data-selection-status]'].textContent, '');
    ui.metrics[1].events.click();
    assert.equal(rate.dataset.effectStatus, 'improved');
    ui.spots[4].events.click();
    assert.equal(rate.dataset.effectStatus, 'worsened');
    assert.match(ui.chart.one['[data-selection-status]'].textContent, /HS5.*6.5% 감소/);
    ui.metrics[0].events.click();
    assert.equal(rate.dataset.effectStatus, 'neutral');
    ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].dataset.effectStatus, 'improved');
    assert.match(ui.course.one['[data-selection-status]'].textContent, /HS2.*10.8% 감소/);
});
