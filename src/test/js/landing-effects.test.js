const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/landing.js', 'utf8');
const renderedText = target => target.children.length ? target.children.at(-1).textContent : target.textContent;

let activeDocument;
function element(dataset = {}) {
    const classes = new Set();
    return {dataset, style: {}, textContent: '', attrs: {}, events: {}, one: {}, many: {}, children: [],
        hidden: false, tabIndex: 0, className: '', src: '', currentSrc: '', alt: '', scrollTop: 0,
        classList: {toggle(name, active) { active ? classes.add(name) : classes.delete(name); },
            contains(name) { return classes.has(name); }, add(name) { classes.add(name); }, remove(name) { classes.delete(name); }},
        setAttribute(name, value) { this.attrs[name] = value; },
        addEventListener(name, fn) {
            const previous = this.events[name];
            this.events[name] = previous ? event => { previous(event); fn(event); } : fn;
        },
        append(...children) { this.children.push(...children); },
        focus() { activeDocument.activeElement = this; },
        contains(target) { return target === this || Object.values(this.one).includes(target); },
        querySelector(selector) { return this.one[selector] || null; },
        querySelectorAll(selector) { return this.many[selector] || []; }};
}

function boot({reduced = false, empty = false} = {}) {
    const changes = [];
    const motion = {matches: reduced, addEventListener(_, fn) { changes.push(fn); }};
    const page = element();
    const course = element();
    course.one['[data-selection-status]'] = element();
    const buttons = [element({code: 'HS1', name: 'one', stress: empty ? undefined : '88.2%', emotional: empty ? undefined : '58.8%'}),
        element({code: 'HS2', name: 'two', stress: empty ? undefined : '81.3%', emotional: empty ? undefined : '68.8%'})];
    course.many['.landing-course-spots button'] = buttons;
    for (const code of ['HS1', 'HS2']) course.one[`[data-spot-image="${code}"]`] = element();
    for (const field of ['stress-reduction', 'emotional-increase']) course.one[`[data-field="${field}"]`] = element();
    page.many['.landing-course-summary'] = [course];

    const tabList = element();
    const tabs = [element({reportTab: 'wellbeing'}), element({reportTab: 'eeg'})];
    tabList.many['[data-report-tab]'] = tabs;
    const panels = [element({reportPanel: 'wellbeing'}), element({reportPanel: 'eeg'})];
    panels[1].hidden = true;
    page.one['[data-report-tabs]'] = tabList;
    page.many['[data-report-panel]'] = panels;

    const reportImages = [
        Object.assign(element(), {src: '/summary.png', currentSrc: '/summary-versioned.png', alt: '뇌파 요약 예시'}),
        Object.assign(element(), {src: '/detail.png', currentSrc: '/detail-versioned.png', alt: '뇌파 상세 예시'})
    ];
    const previews = [
        element({reportPreviewTitle: '스트레스', reportPreviewKind: 'chart', reportPreviewSrc: '/stress-full.png', reportPreviewAlt: '스트레스 전체 예시'}),
        element({reportPreviewTitle: '정서 안정성', reportPreviewKind: 'chart', reportPreviewSrc: '/emotional-full.png', reportPreviewAlt: '정서 안정성 전체 예시'}),
        element({reportPreviewTitle: '뇌파 분석 요약', reportPreviewKind: 'report', reportPreviewSrc: '/summary-versioned.png', reportPreviewAlt: '뇌파 요약 예시'}),
        element({reportPreviewTitle: '뇌파 상세 분석', reportPreviewKind: 'report', reportPreviewSrc: '/detail-versioned.png', reportPreviewAlt: '뇌파 상세 예시'})
    ];
    page.many['[data-report-preview]'] = previews;

    const modal = element(); modal.hidden = true;
    const dialog = element(); const modalBody = element(); const modalTitle = element(); const close = element();
    dialog.many['button, [href], [tabindex]'] = [close];
    modal.one['[data-report-modal-dialog]'] = dialog;
    modal.one['[data-report-modal-body]'] = modalBody;
    modal.one['[data-report-modal-title]'] = modalTitle;
    modal.one['[data-report-modal-close]'] = close;
    page.one['[data-report-modal]'] = modal;

    const documentEvents = {};
    const document = element();
    document.hidden = false;
    document.one['.landing-page'] = page;
    document.body = {style: {overflow: 'auto'}};
    document.activeElement = null;
    document.createElement = () => element();
    document.createTextNode = text => ({textContent: text});
    document.addEventListener = (name, fn) => { (documentEvents[name] ||= []).push(fn); };
    activeDocument = document;
    const window = {matchMedia() { return motion; }};
    vm.runInNewContext(source, {document, window});
    return {course, buttons, changes, motion, document, documentEvents, tabs, tabList, panels,
        previews, reportImages, modal, modalBody, modalTitle, close,
        key(event) { for (const fn of documentEvents.keydown || []) fn(event); }};
}

test('report tabs switch both preview panels and support roving keyboard focus', () => {
    const ui = boot();
    ui.tabs[1].events.click();
    assert.equal(ui.tabs[0].attrs['aria-selected'], 'false');
    assert.equal(ui.tabs[1].attrs['aria-selected'], 'true');
    assert.equal(ui.panels[0].hidden, true);
    assert.equal(ui.panels[1].hidden, false);
    let prevented = 0;
    ui.tabList.events.keydown({target: ui.tabs[1], key: 'ArrowLeft', preventDefault() { prevented++; }});
    assert.equal(prevented, 1);
    assert.equal(ui.panels[0].hidden, false);
    assert.equal(ui.document.activeElement, ui.tabs[0]);
});

test('EEG expand button opens its report, closes with ESC, and restores focus and scrolling', () => {
    const ui = boot();
    ui.previews[2].focus();
    ui.previews[2].events.click();
    const modalImage = ui.modalBody.children[0];
    assert.equal(ui.modal.hidden, false);
    assert.equal(ui.modal.dataset.previewKind, 'report');
    assert.equal(ui.modalTitle.textContent, '뇌파 분석 요약');
    assert.equal(modalImage.src, '/summary-versioned.png');
    assert.equal(modalImage.alt, '뇌파 요약 예시');
    assert.equal(ui.document.body.style.overflow, 'hidden');
    assert.equal(ui.document.activeElement, ui.close);
    ui.key({key: 'Escape', preventDefault() {}});
    assert.equal(ui.modal.hidden, true);
    assert.equal(ui.document.body.style.overflow, 'auto');
    assert.equal(ui.document.activeElement, ui.previews[2]);
});

test('EEG report images stay inert while both header expand buttons open the modal', () => {
    const ui = boot();
    assert.equal(ui.reportImages[0].events.click, undefined);
    assert.equal(ui.reportImages[1].events.click, undefined);
    ui.previews[3].events.click();
    const modalImage = ui.modalBody.children[0];
    assert.equal(ui.modal.hidden, false);
    assert.equal(ui.modalTitle.textContent, '뇌파 상세 분석');
    assert.equal(modalImage.src, '/detail-versioned.png');
    assert.equal(modalImage.alt, '뇌파 상세 예시');
});

test('chart expand control opens its original source without requiring a nested preview image', () => {
    const ui = boot();
    ui.previews[0].one.img = null;
    ui.previews[0].events.click();
    const modalImage = ui.modalBody.children[0];
    assert.equal(ui.modal.hidden, false);
    assert.equal(modalImage.src, '/stress-full.png');
    assert.equal(modalImage.alt, '스트레스 전체 예시');
});

test('report modal traps focus and closes from its backdrop', () => {
    const ui = boot();
    ui.previews[0].events.click();
    let prevented = 0;
    ui.key({key: 'Tab', shiftKey: false, preventDefault() { prevented++; }});
    assert.equal(prevented, 1);
    assert.equal(ui.document.activeElement, ui.close);
    ui.modal.events.click({target: ui.modal});
    assert.equal(ui.modal.hidden, true);
});

test('garden carousel changes image, active selector and both SSR effect displays together', () => {
    const ui = boot(); ui.buttons[1].events.click();
    assert.equal(renderedText(ui.course.one['[data-field="stress-reduction"]']), '81.3%');
    assert.equal(renderedText(ui.course.one['[data-field="emotional-increase"]']), '68.8%');
    assert.equal(ui.course.one['[data-spot-image="HS2"]'].attrs['aria-hidden'], 'false');
    assert.equal(ui.buttons[0].attrs['aria-pressed'], 'false');
    assert.equal(ui.buttons[1].attrs['aria-pressed'], 'true');
});

test('empty operational data never creates sample numbers in the garden carousel', () => {
    const ui = boot({empty: true}); ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].textContent, '데이터 준비 중');
    assert.equal(ui.course.one['[data-field="emotional-increase"]'].textContent, '데이터 준비 중');
});

test('manual controls work immediately with reduced motion and never require timer APIs', () => {
    const ui = boot({reduced: true});
    ui.tabs[1].events.click();
    ui.buttons[1].events.click();
    assert.equal(ui.panels[1].hidden, false);
    assert.equal(renderedText(ui.course.one['[data-field="stress-reduction"]']), '81.3%');
    ui.motion.matches = false; ui.changes.forEach(fn => fn({matches: false}));
});

test('only manual garden choices announce their updated values', () => {
    const ui = boot();
    assert.equal(ui.course.one['[data-selection-status]'].textContent, '');
    ui.buttons[1].events.click();
    assert.equal(ui.course.one['[data-field="stress-reduction"]'].dataset.effectStatus, 'improved');
    assert.match(ui.course.one['[data-selection-status]'].textContent, /HS2.*81.3%/);
});
