/* Actual dated observations only. Shared by authenticated member-data and Monitoring views. */
window.MeasurementHistory = (() => {
    const element = (tag, text, className) => {
        const el = document.createElement(tag);
        if (text != null) el.textContent = text;
        if (className) el.className = className;
        return el;
    };
    const number = value => value == null ? '측정 없음' : String(value);
    const participantChange = value => {
        const text = String(value == null ? '' : value).trim();
        const match = /^([+-]?)(\d+)(?:\.(\d*))?$/.exec(text);
        if (!match) return value;
        const fraction = (match[3] || '').padEnd(3, '0');
        let hundredths = BigInt(match[2]) * 100n + BigInt(fraction.slice(0, 2));
        if (fraction[2] >= '5') hundredths += 1n;
        const absolute = hundredths.toString().padStart(3, '0');
        const sign = match[1] === '-' && hundredths !== 0n ? '-' : '';
        return sign + absolute.slice(0, -2) + '.' + absolute.slice(-2);
    };
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
    function participantGraph(records, metric, name) {
        const width = Math.max(660, records.length * 88 + 100);
        const svg = svgElement('svg', {viewBox: `0 0 ${width} 230`, role: 'img',
            'aria-label': name + ' 측정일별 Baseline 및 ' + name + ' 값 그룹형 막대와 연결선',
            class: 'measurement-grouped-chart', style: `min-width:${width}px`});
        const values = records.flatMap(r => [r[metric].baseline, r[metric].post]).filter(v => v != null);
        const max = Math.max(1, ...values.map(Number));
        const plotLeft = 58, plotRight = width - 42, plotBottom = 180;
        const firstGroupX = plotLeft + 40, lastGroupX = plotRight - 40;
        const x = i => records.length === 1 ? (firstGroupX + lastGroupX) / 2
            : firstGroupX + (lastGroupX - firstGroupX) * i / (records.length - 1);
        const y = value => plotBottom - Number(value) / max * 155;
        [0, .5, 1].forEach(fraction => {
            svg.append(svgElement('line', {x1:plotLeft,x2:plotRight,y1:y(max*fraction),y2:y(max*fraction),stroke:'#e5e7eb'}));
            svg.append(svgElement('text', {x:plotLeft-8,y:y(max*fraction)+4,'text-anchor':'end'},(max*fraction).toFixed(2)));
        });
        records.forEach((record,index) => svg.append(svgElement('text', {x:x(index),y:205,'text-anchor':'middle'},record.measurementDate.slice(5))));
        const series = [
            {field:'baseline',barColor:'#94a3b8',lineColor:'#64748b',offset:-10,label:'Baseline'},
            {field:'post',barColor:'#5f9875',lineColor:'#347553',offset:10,label:name+' 값'}
        ];
        series.forEach(({field,barColor,offset,label}) => {
            records.forEach((record,index) => {
                const value = record[metric][field];
                if (value == null) return;
                const top = y(value);
                const bar = svgElement('rect', {x:x(index)+offset-8,y:top,width:16,height:Math.max(0,plotBottom-top),
                    rx:2,fill:barColor,'fill-opacity':.72,class:'measurement-bar measurement-bar-'+field});
                bar.append(svgElement('title',{},`${record.measurementDate} ${label} ${value}`));
                svg.append(bar);
            });
        });
        records.forEach((record,index) => {
            const baseline = record[metric].baseline, post = record[metric].post;
            if (baseline == null || post == null) return;
            svg.append(svgElement('line',{x1:x(index)-10,y1:y(baseline),x2:x(index)+10,y2:y(post),
                stroke:'#64748b','stroke-width':2,class:'measurement-pair-line'}));
        });
        series.forEach(({field,lineColor,offset}) => {
            records.forEach((record,index) => {
                const value = record[metric][field];
                if (value == null) return;
                svg.append(svgElement('circle',{cx:x(index)+offset,cy:y(value),r:3.5,fill:lineColor,class:'measurement-point measurement-point-'+field}));
            });
        });
        return svg;
    }
    function legend(name) {
        const node = element('div',null,'measurement-legend');
        [['baseline','Baseline'],['post',name+' 값']].forEach(([kind,label]) => {
            const item=element('span',null,'measurement-legend-item');
            item.append(element('i',null,'measurement-legend-swatch is-'+kind),element('span',label));
            node.append(item);
        });
        return node;
    }
    function metricSection(records, metric, name, options) {
        const section=element('section',null,'measurement-metric');
        if (options.participant) {
            const heading=element('div',null,'measurement-metric-heading');
            heading.append(element('h4',name+' 측정 기록'),legend(name));
            section.append(heading);
            const chartScroll=element('div',null,'measurement-chart-scroll');
            chartScroll.append(participantGraph(records,metric,name));
            section.append(chartScroll);
        } else {
            section.append(element('h4',name+' 측정 기록'),element('p','Baseline · 회색 / Spot 측정값 · 녹색','survey-note'));
            section.append(graph(records,metric,name));
        }
        const wrapper=element('div',null,'measurement-table-scroll');
        const table=element('table');
        const head=element('thead'), headings=element('tr');
        const measuredValueLabel = options.participant ? name + ' 값' : 'Spot 측정값';
        ['측정일','Baseline',measuredValueLabel,'변화량 (Post − Baseline)','증감률'].forEach(label => headings.append(element('th',label)));
        head.append(headings); table.append(head);
        const body=element('tbody');
        records.forEach(r => {
            const m=r[metric], row=element('tr');
            const changeDisplay = options.participant ? participantChange(m.changeDisplay) : m.changeDisplay;
            [r.measurementDate,number(m.baseline),number(m.post),changeDisplay,m.rateDisplay].forEach(value => row.append(element('td',value)));
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
            content.append(metricSection(selected.records,'stress','스트레스',options),metricSection(selected.records,'emotional','정서 안정성',options));
        }
        select.addEventListener('change',update); update();
    }
    return {render};
})();
const memberHistoryContainer = document.getElementById('memberMeasurementHistory');
if (memberHistoryContainer) window.MeasurementHistory.render(memberHistoryContainer, window.memberMeasurementHistory || []);
