/* Actual dated observations only. Shared by authenticated member-data and Monitoring views. */
window.MeasurementHistory = (() => {
    const element = (tag, text, className) => {
        const el = document.createElement(tag);
        if (text != null) el.textContent = text;
        if (className) el.className = className;
        return el;
    };
    const number = value => value == null ? '측정 없음' : String(value);
    const svgElement = (tag, attributes, text) => {
        const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
        Object.entries(attributes).forEach(([key, value]) => el.setAttribute(key, value));
        if (text != null) el.textContent = text;
        return el;
    };
    function graph(records, metric, name) {
        const svg = svgElement('svg', {viewBox: '0 0 660 230', role: 'img', 'aria-label': name + ' 측정일별 Baseline 및 Spot 측정값'});
        const values = records.flatMap(r => [r[metric].baseline, r[metric].post]).filter(v => v != null);
        const max = Math.max(1, ...values.map(Number));
        const dates = records.map(r => Date.parse(r.measurementDate + 'T00:00:00Z'));
        const first = Math.min(...dates), last = Math.max(...dates);
        const x = i => first === last ? 330 : 50 + (dates[i] - first) / (last - first) * 560;
        const y = v => 180 - Number(v) / max * 155;
        [0, .5, 1].forEach(f => {
            svg.append(svgElement('line', {x1: 50, x2: 610, y1: y(max*f), y2: y(max*f), stroke: '#e5e7eb'}));
            svg.append(svgElement('text', {x: 42, y: y(max*f)+4, 'text-anchor':'end'}, (max*f).toFixed(2)));
        });
        records.forEach((r,i) => svg.append(svgElement('text', {x:x(i),y:205,'text-anchor':'middle'},r.measurementDate.slice(5))));
        [['baseline','#64748b'],['post','#347553']].forEach(([field,color]) => {
            let path = '', connected = false;
            records.forEach((r,i) => {
                const value=r[metric][field];
                if (value==null) { connected=false; return; }
                path += `${connected?'L':'M'}${x(i)},${y(value)} `;
                connected=true;
            });
            svg.append(svgElement('path',{d:path,fill:'none',stroke:color,'stroke-width':2}));
            records.forEach((r,i) => {
                if (r[metric][field]==null) return;
                const dot=svgElement('circle',{cx:x(i),cy:y(r[metric][field]),r:4,fill:color});
                dot.append(svgElement('title',{},`${r.measurementDate} ${field==='baseline'?'Baseline':'Spot'} ${r[metric][field]}`));
                svg.append(dot);
            });
        });
        return svg;
    }
    function metricSection(records, metric, name) {
        const section=element('section',null,'measurement-metric');
        section.append(element('h4',name+' 측정 기록'),element('p','Baseline · 회색 / Spot 측정값 · 녹색','survey-note'));
        section.append(graph(records,metric,name));
        const wrapper=element('div',null,'measurement-table-scroll');
        const table=element('table');
        const head=element('thead'), headings=element('tr');
        ['측정일','Baseline','Spot 측정값','변화량 (Post − Baseline)','증감률'].forEach(label => headings.append(element('th',label)));
        head.append(headings); table.append(head);
        const body=element('tbody');
        records.forEach(r => {
            const m=r[metric], row=element('tr');
            [r.measurementDate,number(m.baseline),number(m.post),m.changeDisplay,m.rateDisplay].forEach(value => row.append(element('td',value)));
            body.append(row);
        });
        table.append(body); wrapper.append(table); section.append(wrapper);
        return section;
    }
    function render(container, histories, options = {}) {
        container.replaceChildren();
        container.append(element('h3','측정 기록'));
        if (!histories || !histories.length) { container.append(element('p','측정 기록 없음')); return; }
        const label=element('label','Healing Spot '), select=element('select');
        select.setAttribute('aria-label','측정 기록 Healing Spot');
        const placeholder=element('option','HS 선택');
        const defaultSpotCode = histories.some(h => h.spotCode === options.defaultSpotCode) ? options.defaultSpotCode : '';
        placeholder.value=''; placeholder.disabled=true; placeholder.selected=!defaultSpotCode;
        select.append(placeholder);
        histories.forEach(h => {
            const option=element('option',h.spotCode+' · '+h.spotName);
            option.value=h.spotCode;
            option.selected=h.spotCode===defaultSpotCode;
            select.append(option);
        });
        label.append(select); container.append(label);
        const content=element('div'); container.append(content);
        select.value=defaultSpotCode;
        function update() {
            content.replaceChildren();
            if (!select.value) return;
            const selected=histories.find(h=>h.spotCode===select.value);
            if (!selected || !selected.records.length) { content.append(element('p','측정 기록 없음')); return; }
            content.append(element('p','변화량과 증감률은 같은 측정일의 Baseline과 비교합니다. Baseline이 없거나 0이면 증감률을 계산할 수 없습니다.','survey-note'));
            content.append(metricSection(selected.records,'stress','스트레스'),metricSection(selected.records,'emotional','정서 안정성'));
        }
        select.addEventListener('change',update); update();
    }
    return {render};
})();
const memberHistoryContainer = document.getElementById('memberMeasurementHistory');
if (memberHistoryContainer) window.MeasurementHistory.render(memberHistoryContainer, window.memberMeasurementHistory || []);
