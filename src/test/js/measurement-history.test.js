const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function node(tag) {
    return {tag, children: [], attrs: {}, events: {}, textContent: '',
        append(...children) {this.children.push(...children);},
        replaceChildren(...children) {this.children=children;},
        setAttribute(k,v) {this.attrs[k]=v;},
        addEventListener(k,v) {this.events[k]=v;}};
}
const context={window:{},document:{createElement:node,createElementNS:(_,t)=>node(t),getElementById:()=>null}};
vm.createContext(context);
vm.runInContext(fs.readFileSync('src/main/resources/static/js/measurement-history.js','utf8'),context);
const render=context.window.MeasurementHistory.render;
const all=n=>[n,...n.children.flatMap(all)];
const metric=(baseline,post)=>({baseline,post,changeDisplay:baseline==null?'계산 불가':String(post-baseline),rateDisplay:'25.0% 감소'});
const record=(date,baseline,post)=>({measurementDate:date,stress:metric(baseline,post),emotional:metric(baseline,post)});
test('actual history graphs and table switch Spot and clear stale Member values',()=>{
    const container=node('div');
    render(container,[{spotCode:'HS1',spotName:'정원',records:[]},{spotCode:'HS2',spotName:'원',records:[record('2026-08-14',20,15),record('2026-08-19',null,12)]}]);
    const select=all(container).find(n=>n.tag==='select');
    assert.equal(select.value,'');
    assert.equal(select.children[0].textContent,'HS 선택');
    assert.equal(all(container).filter(n=>n.tag==='svg').length,0);
    assert.equal(all(container).filter(n=>n.tag==='table').length,0);
    select.value='HS2';select.events.change();
    assert.equal(all(container).filter(n=>n.tag==='svg').length,2);
    assert.ok(all(container).some(n=>n.tag==='td'&&n.textContent==='2026-08-14'));
    assert.ok(all(container).some(n=>n.tag==='td'&&n.textContent==='측정 없음'));
    select.value='HS1';select.events.change();
    assert.equal(all(container).filter(n=>n.tag==='svg').length,0);
    assert.ok(all(container).some(n=>n.textContent==='측정 기록 없음'));
    render(container,[{spotCode:'HS1',spotName:'정원',records:[record('2026-09-04',30,20)]}]);
    assert.equal(all(container).filter(n=>n.tag==='svg').length,0);
    const next=all(container).find(n=>n.tag==='select');next.value='HS1';next.events.change();
    assert.ok(!all(container).some(n=>n.textContent==='2026-08-14'));
    assert.ok(all(container).some(n=>n.textContent==='2026-09-04'));
});
test('missing baseline produces no fabricated dot and missing history no graph',()=>{
    const container=node('div');
    render(container,[{spotCode:'HS1',spotName:'정원',records:[record('2026-08-14',null,0)]}]);
    const select=all(container).find(n=>n.tag==='select');select.value='HS1';select.events.change();
    assert.equal(all(container).filter(n=>n.tag==='circle').length,2);
    render(container,[]);
    assert.equal(all(container).filter(n=>n.tag==='svg').length,0);
});
test('optional default Spot renders immediately and keeps a missing HS1 selected',()=>{
    const container=node('div');
    render(container,[{spotCode:'HS1',spotName:'호스타 정원',records:[record('2026-09-04',30,20)]},
        {spotCode:'HS2',spotName:'곶자왈원',records:[]}],{defaultSpotCode:'HS1'});
    const select=all(container).find(n=>n.tag==='select');
    assert.equal(select.value,'HS1');
    assert.equal(select.children[1].textContent,'HS1 · 호스타 정원');
    assert.equal(all(container).filter(n=>n.tag==='svg').length,2);

    render(container,[{spotCode:'HS1',spotName:'호스타 정원',records:[]},
        {spotCode:'HS2',spotName:'곶자왈원',records:[record('2026-09-05',20,10)]}],{defaultSpotCode:'HS1'});
    const missingSelect=all(container).find(n=>n.tag==='select');
    assert.equal(missingSelect.value,'HS1');
    assert.equal(all(container).filter(n=>n.tag==='svg').length,0);
    assert.ok(all(container).some(n=>n.textContent==='측정 기록 없음'));
    missingSelect.value='HS2'; missingSelect.events.change();
    assert.equal(all(container).filter(n=>n.tag==='svg').length,2);
});

test('participant graphs use grouped bars, matching lines and metric-specific legends without fabricating missing values',()=>{
    const container=node('div');
    render(container,[{spotCode:'HS1',spotName:'호스타 정원',records:[
        record('2026-08-14',20,15),record('2026-08-19',null,12)
    ]}],{defaultSpotCode:'HS1',participant:true});
    const nodes=all(container), svgs=nodes.filter(n=>n.tag==='svg');
    assert.equal(svgs.length,2);
    assert.equal(nodes.filter(n=>n.tag==='rect').length,6);
    assert.equal(nodes.filter(n=>n.tag==='circle').length,6);
    assert.equal(nodes.filter(n=>n.tag==='path').length,0);
    const pairLines=nodes.filter(n=>n.tag==='line'&&n.attrs.class==='measurement-pair-line');
    assert.equal(pairLines.length,2);
    assert.ok(pairLines.every(line=>Number(line.attrs.x2)-Number(line.attrs.x1)===20));
    assert.equal(nodes.filter(n=>n.tag==='rect'&&n.attrs.class.includes('baseline')).length,2);
    assert.ok(nodes.some(n=>n.textContent==='스트레스 값'));
    assert.ok(nodes.some(n=>n.textContent==='정서 안정성 값'));
    assert.ok(!nodes.some(n=>n.textContent==='Baseline · 회색 / Spot 측정값 · 녹색'));
    assert.deepEqual(svgs[0].children.filter(n=>n.tag==='text'&&/^08-/.test(n.textContent)).map(n=>n.textContent),['08-14','08-19']);
    const dateLabels=svgs[0].children.filter(n=>n.tag==='text'&&/^08-/.test(n.textContent));
    assert.deepEqual(dateLabels.map(n=>Number(n.attrs.x)),[98,578]);
});

test('participant date groups keep balanced inner margins for one and many dates',()=>{
    const positions = records => {
        const container=node('div');
        render(container,[{spotCode:'HS1',spotName:'정원',records}],{defaultSpotCode:'HS1',participant:true});
        const svg=all(container).find(n=>n.tag==='svg');
        return svg.children.filter(n=>n.tag==='text'&&/^09-/.test(n.textContent)).map(n=>Number(n.attrs.x));
    };
    assert.deepEqual(positions([record('2026-09-02',20,10)]),[338]);
    assert.deepEqual(positions([record('2026-09-02',20,10),record('2026-09-04',18,12),record('2026-09-08',16,14)]),[98,338,578]);
});

test('administrator rendering keeps the existing line graph and generic Spot column',()=>{
    const container=node('div');
    render(container,[{spotCode:'HS1',spotName:'호스타 정원',records:[record('2026-08-14',20,15)]}],{defaultSpotCode:'HS1'});
    const nodes=all(container);
    assert.equal(nodes.filter(n=>n.tag==='rect').length,0);
    assert.ok(nodes.some(n=>n.textContent==='Spot 측정값'));
    assert.ok(nodes.some(n=>n.textContent==='Baseline · 회색 / Spot 측정값 · 녹색'));
});

test('participant change column uses two-decimal HALF_UP formatting without converting missing values',()=>{
    const container=node('div');
    const records=[
        record('2026-09-01',10,10),record('2026-09-02',10,9.86),record('2026-09-03',10,12.256),
        record('2026-09-04',10,10),record('2026-09-05',10,10),record('2026-09-06',null,4)
    ];
    records[0].stress.changeDisplay='0';
    records[1].stress.changeDisplay='-0.14000000000000002';
    records[2].stress.changeDisplay='2.256';
    records[3].stress.changeDisplay='-0.004';
    records[4].stress.changeDisplay='-0.005';
    records[5].stress.changeDisplay='계산 불가';
    records.forEach(record=>record.emotional.changeDisplay=record.stress.changeDisplay);
    render(container,[{spotCode:'HS1',spotName:'정원',records}],{defaultSpotCode:'HS1',participant:true});
    const stressTable=all(container).filter(n=>n.tag==='table')[0];
    const changes=stressTable.children.find(n=>n.tag==='tbody').children.map(row=>row.children[3].textContent);
    assert.deepEqual(changes,['0.00','-0.14','2.26','0.00','-0.01','계산 불가']);
    const emotionalTable=all(container).filter(n=>n.tag==='table')[1];
    const emotionalChanges=emotionalTable.children.find(n=>n.tag==='tbody').children.map(row=>row.children[3].textContent);
    assert.deepEqual(emotionalChanges,changes);

    const admin=node('div');
    render(admin,[{spotCode:'HS1',spotName:'정원',records}],{defaultSpotCode:'HS1'});
    const adminTable=all(admin).filter(n=>n.tag==='table')[0];
    assert.equal(adminTable.children.find(n=>n.tag==='tbody').children[0].children[3].textContent,'0');
});
