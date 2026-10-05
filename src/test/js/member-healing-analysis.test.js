const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('participant analysis renders overall and Spot count-backed improvements and preserves History', () => {
    class Node {
        constructor(tag, id) { this.tag = tag; this.id = id; this.children = []; this.style = {}; this.textContent = ''; }
        append(...children) { this.children.push(...children); }
        replaceChildren(...children) { this.children = children; }
    }
    const elements = {};
    function node(id) { return elements[id] ||= new Node('div', id); }
    const calls = {};
    const document = {getElementById: node, createElement: tag => new Node(tag)};
    const metric = (validCount, improvedCount, rate, text) => ({validCount, improvedCount,
        improvementRate: rate, improvementRateDisplay: text});
    const effects = [
        {spotCode: 'HS1', spotName: '호스타 정원', stress: metric(2, 2, 100, '100.0%'), emotional: metric(2, 1, 50, '50.0%')},
        {spotCode: 'HS5', spotName: '블로썸 가든', stress: metric(1, 0, 0, '0.0%'), emotional: metric(0, 0, null, '측정 없음')}
    ];
    const overall = {stress: metric(13, 8, 61.538, '61.5%'), emotional: metric(13, 7, 53.846, '53.8%')};
    const history = [{spotCode: 'HS1', records: []}];
    const context = {document, window: {memberHealingEffects: effects, memberOverallImprovement: overall,
        memberMeasurementHistory: history,
        MeasurementHistory: {render(container, data, options) { calls.history = [container, data, options]; }}}};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/member-healing-analysis.js', 'utf8'), context);
    assert.equal(calls.history[1], history);
    assert.equal(calls.history[2].defaultSpotCode, 'HS1');
    const text = root => [root.textContent, ...root.children.flatMap(child => text(child))].join(' ');
    assert.match(text(elements.memberAnalysisHighlights), /스트레스 개선율.*61.5% 개선.*8 \/ 13회 개선/);
    assert.match(text(elements.memberAnalysisHighlights), /정서적 안정성 개선율.*53.8% 개선.*7 \/ 13회 개선/);
    assert.match(text(elements.memberAnalysisSummary), /HS1 · 호스타 정원.*100.0% 개선.*2 \/ 2회 개선/);
    assert.match(text(elements.memberAnalysisSummary), /HS5 · 블로썸 가든.*0.0% 개선.*0 \/ 1회 개선/);
    assert.match(text(elements.memberAnalysisSummary), /측정 없음/);
    assert.equal(elements.openMemberAnalysisButton, undefined);
    assert.equal(elements.memberAnalysisModal, undefined);
});

test('zero-valid is missing while zero-improved with valid observations is 0.0%', () => {
    class Node { constructor() { this.children=[]; this.style={}; this.textContent=''; }
        append(...children) { this.children.push(...children); } replaceChildren(...children) { this.children=children; } }
    const nodes = {};
    const context = {document: {getElementById: id => nodes[id] ||= new Node(), createElement: () => new Node()},
        window: {memberHealingEffects: [], memberOverallImprovement: null, memberMeasurementHistory: [],
            MeasurementHistory: {render() {}}}};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/member-healing-analysis.js', 'utf8'), context);
    assert.equal(context.window.MemberHealingAnalysis.display({validCount: 0, improvedCount: 0, improvementRateDisplay: '측정 없음'}), '측정 없음');
    assert.equal(context.window.MemberHealingAnalysis.display({validCount: 1, improvedCount: 0, improvementRateDisplay: '0.0%'}), '0.0% 개선');
});
