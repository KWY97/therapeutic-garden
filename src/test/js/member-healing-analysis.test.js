const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('participant analysis renders overall and Spot count-backed improvements and preserves History', () => {
    class Node {
        constructor(tag, id) { this.tag = tag; this.id = id; this.children = []; this.style = {}; this.textContent = ''; this.attrs = {};
            this.className = ''; this.classList = {add: name => this.className += (this.className ? ' ' : '') + name}; }
        append(...children) { this.children.push(...children); }
        replaceChildren(...children) { this.children = children; }
        setAttribute(name, value) { this.attrs[name] = value; }
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
        memberMaximumImprovements: {stressSpotCodes: ['HS1'], emotionalSpotCodes: ['HS1']},
        memberDisplayName: '김참가',
        memberMeasurementHistory: history,
        MeasurementHistory: {render(container, data, options) { calls.history = [container, data, options]; }}}};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/member-healing-analysis.js', 'utf8'), context);
    assert.equal(calls.history[1], history);
    assert.equal(calls.history[2].defaultSpotCode, 'HS1');
    assert.equal(calls.history[2].participant, true);
    const text = root => [root.textContent, ...root.children.flatMap(child => text(child))].join(' ');
    const compactText = root => root.textContent + root.children.map(child => compactText(child)).join('');
    assert.match(text(elements.memberAnalysisHighlights), /총 변화/);
    assert.doesNotMatch(text(elements.memberAnalysisHighlights), /핵심 변화|평균/);
    assert.match(text(elements.memberAnalysisHighlights), /김참가님.*의 측정 결과.*스트레스.*'호스타 정원'.*정서 안정성.*'호스타 정원'.*가장 높은 개선율/);
    assert.match(compactText(elements.memberAnalysisHighlights), /스트레스는 '호스타 정원'에서, 정서 안정성은 '호스타 정원'에서/);
    assert.doesNotMatch(compactText(elements.memberAnalysisHighlights), /정서 안정성는/);
    assert.match(text(elements.memberAnalysisHighlights), /스트레스 개선율.*61.5% 개선.*8 \/ 13회 개선/);
    assert.match(text(elements.memberAnalysisHighlights), /정서 안정성 개선율.*53.8% 개선.*7 \/ 13회 개선/);
    assert.match(text(elements.memberAnalysisSummary), /HS1 · 호스타 정원.*100.0% 개선.*2 \/ 2회 개선/);
    assert.match(text(elements.memberAnalysisSummary), /HS5 · 블로썸 가든.*0.0% 개선.*0 \/ 1회 개선/);
    assert.match(text(elements.memberAnalysisSummary), /측정 없음/);
    const summaryCards = elements.memberAnalysisSummary.children.flatMap(section => section.children)
        .flatMap(child => child.children || []).filter(child => child.className.includes('survey-summary-card'));
    const maximumCards = summaryCards.filter(card => card.className.includes('is-maximum'));
    assert.equal(maximumCards.length,2);
    assert.match(maximumCards[0].attrs['aria-label'],/HS1.*스트레스 개선율.*최대 개선 스팟/);
    assert.equal(context.window.MemberHealingAnalysis.spotNamesText(['하나']), "'하나'");
    assert.equal(context.window.MemberHealingAnalysis.spotNamesText(['하나','둘']), "'하나'과 '둘'");
    assert.equal(context.window.MemberHealingAnalysis.spotNamesText(['하나','둘','셋']), "'하나', '둘', '셋'");
    assert.equal(elements.openMemberAnalysisButton, undefined);
    assert.equal(elements.memberAnalysisModal, undefined);
});

test('zero-valid is missing while zero-improved with valid observations is 0.0%', () => {
    class Node { constructor() { this.children=[]; this.style={}; this.textContent=''; this.className=''; this.attrs={};
            this.classList={add:name=>this.className+=name}; }
        append(...children) { this.children.push(...children); } replaceChildren(...children) { this.children=children; }
        setAttribute(name,value) { this.attrs[name]=value; } }
    const nodes = {};
    const context = {document: {getElementById: id => nodes[id] ||= new Node(), createElement: () => new Node()},
        window: {memberHealingEffects: [], memberOverallImprovement: null, memberMeasurementHistory: [],
            MeasurementHistory: {render() {}}}};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/member-healing-analysis.js', 'utf8'), context);
    const text = root => [root.textContent, ...root.children.flatMap(child => text(child))].join(' ');
    assert.match(text(nodes.memberAnalysisHighlights), /참가자님.*의 측정 결과.*유효한 측정 결과가 아직 없습니다/);
    assert.equal(context.window.MemberHealingAnalysis.display({validCount: 0, improvedCount: 0, improvementRateDisplay: '측정 없음'}), '측정 없음');
    assert.equal(context.window.MemberHealingAnalysis.display({validCount: 1, improvedCount: 0, improvementRateDisplay: '0.0%'}), '0.0% 개선');
});

test('valid zero improvements use a neutral participant summary', () => {
    class Node { constructor() { this.children=[]; this.style={}; this.textContent=''; this.className=''; this.attrs={};
            this.classList={add:name=>this.className+=name}; }
        append(...children) { this.children.push(...children); } replaceChildren(...children) { this.children=children; }
        setAttribute(name,value) { this.attrs[name]=value; } }
    const nodes={};
    const zero={validCount:3,improvedCount:0,improvementRate:0,improvementRateDisplay:'0.0%'};
    const missing={validCount:0,improvedCount:0,improvementRate:null,improvementRateDisplay:'측정 없음'};
    const context={document:{getElementById:id=>nodes[id]||=new Node(),createElement:()=>new Node()},window:{
        memberHealingEffects:[{spotCode:'HS1',spotName:'호스타 정원',stress:zero,emotional:missing}],
        memberOverallImprovement:null,memberMaximumImprovements:{stressSpotCodes:['HS1'],emotionalSpotCodes:[]},
        memberDisplayName:'이참가',memberMeasurementHistory:[],MeasurementHistory:{render(){}}}};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/member-healing-analysis.js','utf8'),context);
    const text=root=>[root.textContent,...root.children.flatMap(child=>text(child))].join(' ');
    const summary=text(nodes.memberAnalysisHighlights);
    assert.match(summary,/이참가님.*의 측정 결과.*현재.*스트레스.*개선이 확인된 스팟은 없습니다/);
    assert.doesNotMatch(summary,/가장 높은 개선율/);
});

test('participant summary rendering never inserts names as HTML', () => {
    const source=fs.readFileSync('src/main/resources/static/js/member-healing-analysis.js','utf8');
    assert.doesNotMatch(source,/innerHTML|insertAdjacentHTML/);
});
