const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('src/main/resources/static/js/home-map.js', 'utf8');
const spatialScript = fs.readFileSync('src/main/resources/static/js/home-spatial.js', 'utf8');
function setup(options = {}) {
    const elements = {};
    function element(id) {
        return elements[id] ||= {hidden: true, dataset: {}, style: {}, children: [],
            classList: {add() {}, remove() {}},
            setAttribute(name, value) { this[name] = value; },
            append(...children) { this.children.push(...children); children.forEach(child => child.parent = this); },
            replaceChildren(...children) { this.children = children; },
            remove() { this.parent.children = this.parent.children.filter(child => child !== this); },
            focus() { context.document.activeElement = this; if (this.onfocus) this.onfocus(); },
            addEventListener(event, fn) { this[event === 'focus' ? 'onfocus' : event] = fn; }, add() {},
            removeAttribute(name) { delete this[name]; }, querySelector() { return element('dialog'); }};
    }
    const select = element('siteSelect');
    select.options = [1, 2, 3].map(id => ({value: String(id), dataset: {name: 'Site ' + id,
        address: '주소', latitude: String(36 + id), longitude: '127', mapLevel: String(id + 2),
        representativeImageUrl: id < 3 ? '/authenticated/site-' + id : ''}}));
    select.selectedIndex = 0;
    const maps = [], circles = [], markers = [], frames = [], windowEvents = {}, documentEvents = {};
    let elementSequence = 0;
    const kakao = {maps: {
        LatLng: function(lat, lng) { this.lat = lat; this.lng = lng; },
        Map: function(el, options) { Object.assign(this, options); this.setCenter = x => this.center = x; this.setLevel = x => this.level = x; this.getCenter = () => this.center; this.relayout = () => this.relayouts = (this.relayouts || 0) + 1; assert.equal(element('mapModal').hidden, false); maps.push(this); },
        Circle: function(options) { Object.assign(this, options); this.setMap = m => this.map = m; this.setOptions = x => Object.assign(this, x); circles.push(this); },
        Marker: function(options) { Object.assign(this, options); this.setMap = m => this.map = m; markers.push(this); },
        event: {addListener(target, event, fn) { target[event] = fn; }}
    }};
    const metric = (validCount, improvedCount, rate) => ({validCount, improvedCount, improvementRate: rate,
        improvementRateDisplay: rate.toFixed(1) + '%',
        improvementCountDisplay: validCount + '회 중 ' + improvedCount + '회 개선'});
    const effect = (stress, emotional) => ({spotCode: 'HS1', spotName: '정원', hasMeasurement: true,
        stress: metric(17, 15, stress), emotional: metric(17, 10, emotional),
        stressParticipantCount: 3, emotionalParticipantCount: 2});
    const monitoringEffects = {
        '1': [effect(88.2, 58.8)],
        '2': [effect(72.2, 88.9)],
        '3': []
    };
    const monitoringMaximums = {
        '1': {stressSpotCodes: ['HS1'], emotionalSpotCodes: []},
        '2': {stressSpotCodes: [], emotionalSpotCodes: ['HS1']},
        '3': {stressSpotCodes: [], emotionalSpotCodes: []}
    };
    const context = {document: {getElementById: element, querySelector: element,
            createElementNS(ns, tag) { return this.createElement(tag); },
            createElement(tag) { return Object.assign(element('created' + ++elementSequence), {tag}); },
            addEventListener(event, fn) { (documentEvents[event] ||= []).push(fn); }, body: {style: {}}},
        window: {kakao: options.noSdk ? null : kakao, monitoringEffects, monitoringMaximums,
            location: {search: options.search || ''},
            requestAnimationFrame(fn) { frames.push(fn); },
            addEventListener(event, fn) { const previous = windowEvents[event]; windowEvents[event] = (...args) => { if (previous) previous(...args); fn(...args); }; }}, kakao, Option: function() {},
        fetch: options.fetch || (async url => ({ok: true, json: async () => url.endsWith('/data')
            ? {siteId: Number(url.split('/')[3]), name: 'Site', image: {readUrl: '/spatial/' + url.split('/')[3]},
                spots: [{spotId: 1, code: 'HS1', name: '정원', course: 'HC1 · 코스', courseId: 17, courseCode: 'HC1', courseName: '코스', readUrl: '/authenticated/hs-1', xPercent: 20, yPercent: 70}]}
            : url.endsWith('/courses')
            ? [{centerLatitude: 37, centerLongitude: 127, radius: 10}]
            : /\/spots\/\d+$/.test(url)
            ? {spotId: 1, siteId: Number(url.split('/')[3]), siteName: '상세 Site', siteAddress: '상세 주소', code: 'HS1', name: '정원', courseCode: 'HC1', courseName: '코스',
                images: [{imageId: 1, displayOrder: 1, representative: true, readUrl: '/authenticated/hs-1'}]}
            : [{spotId: 1, code: 'HS1', name: '정원', courseCode: 'HC1', courseName: '코스', latitude: 37, longitude: 127,
                representativeImageUrl: '/authenticated/hs-1', images: [{imageId: 1, displayOrder: 1, representative: true, readUrl: '/authenticated/hs-1'}]}]}))};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/home-survey-analysis.js', 'utf8'), context);
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/home-course-overlay.js', 'utf8'), context);
    vm.runInContext(spatialScript, context); vm.runInContext(script, context);
    return {elements, maps, circles, markers, context, windowEvents,
        key(event) { event.stopImmediatePropagation = () => { event.stopped = true; }; for (const fn of documentEvents.keydown) { fn(event); if (event.stopped) break; } },
        open() { elements.openMapButton.click(); frames.splice(0).forEach(fn => fn()); },
        change(index) { select.selectedIndex = index; select.change(); }};
}
const flush = () => new Promise(resolve => setImmediate(resolve));
test('Monitoring Spot selector targets shared detail without changing overall map Summary', async () => {
    const ui=setup();await flush();
    assert.equal(ui.elements.openSpotDetailButton.disabled,true);
    assert.equal(ui.elements.healingSpotSelect.children[0].textContent,'HS 선택');
    assert.equal(ui.elements.healingSpotSelect.children[1].textContent,'HS1 · 정원');
    const overallBefore=ui.context.currentEffects()[0].stress.improvementRate;
    ui.elements.healingSpotSelect.value='1';ui.elements.healingSpotSelect.change();
    assert.equal(ui.elements.openSpotDetailButton.disabled,false);
    assert.equal(ui.context.currentEffects()[0].stress.improvementRate,overallBefore);
    ui.elements.openSpotDetailButton.click();await flush();
    assert.equal(ui.elements.spotDetailModal.hidden,false);
    assert.equal(ui.elements.spotDetailTitle.textContent,'HS1 · 정원');
});
test('Site selection replaces representative image and keeps map center/level movement', async () => {
    const ui = setup(); await flush(); ui.open();
    assert.equal(ui.elements.siteImage.src, '/authenticated/site-1');
    ui.elements.siteImage.onload(); assert.equal(ui.elements.siteImageEmpty.hidden, true);
    ui.change(1); await flush();
    assert.equal(ui.elements.siteImage.src, '/authenticated/site-2');
    assert.equal(ui.maps.length, 1); assert.equal(ui.maps[0].center.lat, 38); assert.equal(ui.maps[0].level, 4);
    assert.equal(ui.circles[0].map, null); assert.equal(ui.markers[0].map, null);
    assert.equal(ui.circles.at(-1).map, ui.maps[0]); assert.equal(ui.markers.at(-1).map, ui.maps[0]);
});
test('missing Site representative and image load failure use placeholder without static fallback', () => {
    const ui = setup(); ui.elements.siteImage.onerror();
    assert.equal(ui.elements.siteImage.hidden, true); assert.equal(ui.elements.siteImageEmpty.hidden, false);
    ui.change(2);
    assert.equal(ui.elements.siteImage.src, undefined); assert.equal(ui.elements.siteImageEmpty.hidden, false);
    assert.match(ui.elements.siteImageEmpty.textContent, /Site 3/);
});
test('HS marker loads API gallery and never reports no images while the request is pending', async () => {
    const ui = setup(); await flush(); ui.open(); ui.markers[0].click();
    assert.doesNotMatch(ui.elements.spotImageEmpty.textContent, /등록된 이미지가 없습니다/);
    await flush(); assert.equal(ui.elements.spotImage.src, '/authenticated/hs-1');
});
test('Monitoring runtime has no filename inference or static Site/HS image mapping', () => {
    assert.doesNotMatch(script, /\/images\/site|objectKey|toLowerCase\(\)|\.jpeg/);
});

const canvas = ui => ui.elements.monitoringCanvas;
const image = ui => canvas(ui).children[0];
const hotspots = ui => canvas(ui).children[5].children;
function layoutFetch(layout) {
    return async url => ({ok: true, json: async () => url.endsWith('/data') ? layout : []});
}
const baseLayout = () => ({siteId: 1, name: 'Site', image: {readUrl: '/storage/spatial'}, spots: [
    {spotId: 9, code: 'GARDEN', name: '정원', course: 'HC · 코스', xPercent: 0, yPercent: 100, readUrl: '/storage/representative'}
]});
test('halo debug values are opt-in and exercise the count-backed 0-100 color range', async () => {
    const layout = {siteId: 1, name: 'Site', image: {readUrl: '/storage/spatial'}, spots:
        [20, -15, 0, 8, -5, 25].map((value, index) => ({spotId: index + 1, code: 'HS' + (index + 1),
            name: 'Spot ' + (index + 1), courseId: Math.floor(index / 2) + 1,
            courseCode: 'HC-' + String.fromCharCode(65 + Math.floor(index / 2)), courseName: '코스',
            xPercent: 15 + index * 13, yPercent: 20 + Math.floor(index / 2) * 30}))};
    const ui = setup({search: '?haloDebug=on', fetch: layoutFetch(layout)}); await flush(); image(ui).load();
    assert.deepEqual(hotspots(ui).map(button => button.children[1].children[0].textContent),
        ['스트레스', '정서 안정성', '스트레스', '정서 안정성', '스트레스', '정서 안정성']);
    assert.deepEqual(hotspots(ui).map(button => button.children[1].children[1].children.map(child => child.textContent).join(' ')),
        ['100.0% 개선', '75.0% 개선', '0.0% 개선', '50.0% 개선', '25.0% 개선', '90.0% 개선']);
    assert.equal(hotspots(ui)[2].children[0].style['--spot-data-color'], 'hsl(40.0 35.0% 82.0%)');
    assert.equal(halos(ui).length, 3);
});
test('right image is spatial; hotspot percentages and representative photo open the shared detail modal', async () => {
    const ui = setup(); await flush();
    assert.equal(image(ui).src, '/spatial/1');
    assert.notEqual(image(ui).src, ui.elements.siteImage.src);
    image(ui).load(); assert.equal(canvas(ui).hidden, false);
    const hotspot = hotspots(ui)[0];
    assert.equal(hotspot.style.left, '20%'); assert.equal(hotspot.style.top, '70%');
    assert.equal(hotspot.children[0].children[0].src, '/authenticated/hs-1');
    hotspot.click(); await flush();
    assert.equal(ui.elements.spotImage.src, '/authenticated/hs-1');
    assert.equal(ui.elements.spotCourse.textContent, 'HC1 · 코스');
    assert.equal(hotspot['aria-pressed'], 'true');
    ui.context.closeSpotDetail(); assert.equal(hotspot['aria-pressed'], 'false');
    ui.windowEvents.resize();
    assert.equal(hotspot.style.left, '20%'); assert.equal(hotspot.style.top, '70%');
});
test('Site switch clears previous photo immediately and replaces the layout; old image events are ignored', async () => {
    const ui = setup(); await flush(); const old = image(ui);
    ui.change(1); assert.equal(canvas(ui).hidden, true); assert.equal(canvas(ui).children.length, 0);
    await flush(); assert.equal(image(ui).src, '/spatial/2');
    old.load(); assert.equal(canvas(ui).hidden, true);
    image(ui).load(); assert.equal(canvas(ui).hidden, false);
});
test('missing HS image and broken representative photo preserve a labeled circular placeholder', async () => {
    const layout = baseLayout(); layout.spots.push({...layout.spots[0], spotId: 10, readUrl: null});
    const ui = setup({fetch: layoutFetch(layout)}); await flush();
    const circles = hotspots(ui).map(button => button.children[0]);
    circles[0].children[0].error();
    circles.forEach(circle => { assert.equal(circle.children.length, 0); assert.equal(circle.textContent, 'GARDEN'); });
    assert.equal(hotspots(ui)[0].style.left, '0%'); assert.equal(hotspots(ui)[0].style.top, '100%');
});
test('missing spatial image never substitutes the Site representative', async () => {
    const layout = baseLayout(); layout.image = null;
    const ui = setup({fetch: layoutFetch(layout)}); await flush();
    assert.equal(canvas(ui).hidden, true); assert.equal(canvas(ui).children.length, 0);
    assert.match(ui.elements.monitoringStatus.textContent, /모니터링 이미지가 아직/);
    assert.equal(ui.elements.monitoringSettings.href, '/admin/sites/1/spatial-layout');
    assert.equal(ui.elements.monitoringSettings.hidden, false);
});
test('unplaced and invalid positions do not hide a valid spatial image', async () => {
    const layout = baseLayout(); layout.spots = [null, '', -1, 101, 'NaN'].map(x => ({...layout.spots[0], xPercent: x}));
    const ui = setup({fetch: layoutFetch(layout)}); await flush(); image(ui).load();
    assert.equal(canvas(ui).hidden, false); assert.equal(hotspots(ui).length, 0);
    assert.equal(ui.elements.monitoringStatus.textContent, '설정된 HS 위치가 없습니다.');
});
test('spatial image error hides hotspots and provides recovery without affecting map data', async () => {
    const ui = setup(); await flush(); image(ui).error();
    assert.equal(canvas(ui).hidden, true); assert.match(ui.elements.monitoringStatus.textContent, /불러오지 못했습니다/);
    ui.open(); assert.equal(ui.markers.length, 1); assert.equal(ui.circles.length, 1);
});
test('out-of-order spatial requests cannot overwrite the latest Site', async () => {
    let resolveFirst;
    const ui = setup({fetch: url => url === '/admin/sites/1/spatial-layout/data'
        ? new Promise(resolve => { resolveFirst = resolve; })
        : layoutFetch({...baseLayout(), siteId: 2, image: {readUrl: '/spatial/second'}})(url)});
    ui.change(1); await flush();
    resolveFirst({ok: true, json: async () => baseLayout()}); await flush();
    assert.equal(image(ui).src, '/spatial/second');
    assert.equal(ui.elements.monitoringSettings.href, '/admin/sites/2/spatial-layout');
    assert.equal(ui.elements.monitoringStatus.textContent, '공간 정보를 불러오는 중입니다.');
    image(ui).load(); assert.equal(canvas(ui).hidden, false);
});
test('spatial request failure does not prevent existing HC / HS map rendering', async () => {
    const ui = setup(); await flush();
    ui.context.fetch = async () => { throw new Error('offline'); };
    await ui.context.spatial.load(ui.context.selectedSite);
    assert.match(ui.elements.monitoringStatus.textContent, /불러오지 못했습니다/);
    ui.open(); assert.equal(ui.circles.length, 1); assert.equal(ui.markers.length, 1);
});
test('map is lazy, relayout preserves center on resize, and Course color stays neutral', async () => {
    const ui = setup(); await flush(); image(ui).load();
    const photo = image(ui);
    assert.equal(ui.maps.length, 0);
    ui.open(); assert.equal(ui.maps.length, 1); assert.equal(ui.elements.mapModal.hidden, false);
    assert.equal(ui.circles[0].fillColor, '#749d80'); assert.equal(ui.elements.surveyLegendTitle.textContent, 'Healing Spot 개선');
    ui.maps[0].setCenter({lat: 39, lng: 128}); ui.windowEvents.resize();
    assert.equal(ui.maps[0].center.lat, 39); assert.equal(ui.maps[0].relayouts, 1);
    ui.elements.closeMapButton.click(); assert.equal(ui.elements.mapModal.hidden, true);
    assert.equal(image(ui), photo); assert.equal(canvas(ui).hidden, false);
    ui.change(1); await flush(); ui.open();
    assert.equal(ui.maps.length, 1); assert.equal(ui.maps[0].center.lat, 38); assert.equal(ui.maps[0].level, 4);
    assert.equal(ui.circles[0].map, null); assert.equal(ui.markers[0].map, null);
    assert.equal(ui.markers.at(-1).map, ui.maps[0]);
});
test('map marker selects same photo hotspot and keeps modal open; ESC/backdrop close it', async () => {
    const ui = setup(); await flush(); ui.open(); ui.markers[0].click();
    assert.equal(hotspots(ui)[0]['aria-pressed'], 'true'); assert.equal(ui.elements.mapModal.hidden, false);
    assert.match(ui.elements.mapSelectionStatus.textContent, /HS1/);
    ui.key({key: 'Escape', preventDefault() {}}); assert.equal(ui.elements.spotDetailModal.hidden, true);
    assert.equal(ui.elements.mapModal.hidden, false);
    ui.key({key: 'Escape', preventDefault() {}}); assert.equal(ui.elements.mapModal.hidden, true);
    ui.open(); ui.elements.mapModal.click({target: ui.elements.mapModal}); assert.equal(ui.elements.mapModal.hidden, true);
});
test('SDK unavailable still allows photo hotspot information', async () => {
    const ui = setup({noSdk: true}); await flush(); ui.open();
    assert.equal(ui.maps.length, 0); assert.match(ui.elements.map.textContent, /지도를 불러올 수 없습니다/);
    ui.elements.closeMapButton.click(); hotspots(ui)[0].click(); assert.equal(ui.elements.spotId.textContent, 'HS1');
});
test('coordinate plane uses uncropped responsive image, absolute overlay, and course-centered markers', () => {
    const css = fs.readFileSync('src/main/resources/static/css/home-spatial.css', 'utf8');
    assert.match(css, /\.monitoring-image\s*\{[^}]*width: 100%; height: auto;/);
    assert.match(css, /\.monitoring-hotspots\s*\{[^}]*position: absolute; inset: 0;/);
    assert.match(css, /\.monitoring-hotspot\s*\{[^}]*translateX\(-50%\)/);
    assert.doesNotMatch(spatialScript, /\/images\/site|\.jpeg|objectKey/);
});

test('map selection made before the layout response remains highlighted when hotspots arrive', async () => {
    let finish;
    const ui = setup({fetch: url => url.endsWith('/data') ? new Promise(resolve => { finish = resolve; })
        : Promise.resolve({ok: true, json: async () => []})});
    ui.context.openSpotDetail({spotId: 9, code: 'GARDEN', name: '정원', course: 'HC · 코스'});
    finish({ok: true, json: async () => baseLayout()}); await flush();
    assert.equal(hotspots(ui)[0]['aria-pressed'], 'true');
});
test('invalid Site coordinates cannot expose a previous Site map', async () => {
    const ui = setup(); await flush(); ui.open(); ui.elements.closeMapButton.click();
    ui.context.siteSelect.options[1].dataset.latitude = '';
    ui.change(1); await flush(); ui.open();
    assert.equal(ui.elements.map.hidden, true);
    assert.match(ui.elements.mapSelectionStatus.textContent, /지도 좌표가 설정되지/);
    ui.elements.closeMapButton.click(); ui.change(0); await flush(); ui.open();
    assert.equal(ui.elements.map.hidden, false); assert.equal(ui.maps.length, 1);
});
test('map keyboard focus wraps, close restores focus and body scrolling', async () => {
    const ui = setup(); await flush();
    ui.context.document.body.style.overflow = 'auto';
    ui.elements.openMapButton.focus(); ui.open();
    const close = ui.elements.closeMapButton;
    close.tabIndex = 0; close.getClientRects = () => [{}];
    ui.elements.dialog.querySelectorAll = () => [close];
    let prevented = 0;
    ui.key({key:'Tab', preventDefault() { prevented++; }});
    ui.key({key:'Tab', shiftKey:true, preventDefault() { prevented++; }});
    assert.equal(prevented, 2); assert.equal(ui.context.document.activeElement, close);
    close.click(); assert.equal(ui.context.document.activeElement, ui.elements.openMapButton);
    assert.equal(ui.context.document.body.style.overflow, 'auto');
});

test('gallery sorts all images, initially selects representative, and changes hero via thumbnails', async () => {
    const ui = setup(); await flush();
    const spot = {siteId: 1, spotId: 1, code: 'HS1', name: '정원', images: [
        {imageId: 3, displayOrder: 3, readUrl: '/third'},
        {imageId: 2, displayOrder: 2, representative: true, readUrl: '/representative'},
        {imageId: 1, displayOrder: 1, readUrl: '/first'}
    ]};
    ui.context.fetch = async () => ({ok: true, json: async () => spot});
    const siteImage = ui.elements.siteImage.src, siteName = ui.elements.siteName.textContent;
    hotspots(ui)[0].focus(); hotspots(ui)[0].click(); await flush();
    const thumbs = ui.elements.spotThumbnails;
    assert.equal(ui.elements.spotDetailModal.hidden, false);
    assert.equal(ui.elements.siteName.textContent, siteName); assert.equal(ui.elements.siteImage.src, siteImage);
    assert.equal(ui.elements.spotImage.src, '/representative'); assert.equal(thumbs.hidden, false);
    assert.deepEqual(thumbs.children.map(button => button.children[0].src), ['/first', '/representative', '/third']);
    assert.equal(thumbs.children[1]['aria-pressed'], 'true');
    thumbs.children[2].click(); assert.equal(ui.elements.spotImage.src, '/third');
    assert.equal(thumbs.children[1]['aria-pressed'], 'false'); assert.equal(thumbs.children[2]['aria-pressed'], 'true');
    ui.elements.closeSpotDetailButton.click();
    assert.equal(ui.context.document.activeElement, hotspots(ui)[0]);
    assert.equal(ui.elements.siteImage.src, siteImage);
});
test('single image hides thumbnails; empty gallery and broken hero show placeholder', async () => {
    const ui = setup(); await flush();
    await ui.context.openSpotDetail({spotId: 1, code: 'HS1', name: '정원'});
    assert.equal(ui.elements.spotThumbnails.hidden, true);
    assert.equal(ui.elements.spotImage.src, '/authenticated/hs-1');
    ui.elements.spotImage.onerror(); assert.equal(ui.elements.spotImageEmpty.hidden, false);
    ui.context.fetch = async () => ({ok: true, json: async () => ({siteId: 1, spotId: 2, code: 'HS2', name: '숲', images: []})});
    await ui.context.openSpotDetail({spotId: 2, code: 'HS2', name: '숲'});
    assert.equal(ui.elements.spotThumbnails.children.length, 0);
    assert.equal(ui.elements.spotImage.src, undefined); assert.equal(ui.elements.spotImageEmpty.hidden, false);
});
test('nested detail traps focus, preserves map and scrolling lock, then restores original body state', async () => {
    const ui = setup(); await flush(); ui.context.document.body.style.overflow = 'auto';
    ui.open(); ui.markers[0].click(); await flush();
    const close = ui.elements.closeSpotDetailButton;
    close.getClientRects = () => [{}]; ui.elements.dialog.querySelectorAll = () => [close];
    let prevented = 0;
    ui.key({key: 'Tab', preventDefault() { prevented++; }});
    ui.key({key: 'Tab', shiftKey: true, preventDefault() { prevented++; }});
    assert.equal(prevented, 2);
    ui.key({key: 'Escape', preventDefault() {}});
    assert.equal(ui.elements.spotDetailModal.hidden, true); assert.equal(ui.elements.mapModal.hidden, false);
    assert.equal(ui.context.document.body.style.overflow, 'hidden');
    assert.equal(ui.context.document.activeElement, ui.elements.closeMapButton);
    ui.key({key: 'Escape', preventDefault() {}}); assert.equal(ui.context.document.body.style.overflow, 'auto');
});
test('Site change closes detail and ignores late gallery responses; reopen obtains a fresh URL', async () => {
    const ui = setup(); await flush(); let finish;
    const initialFetch = ui.context.fetch;
    ui.context.fetch = url => /\/spots\/\d+$/.test(url) ? new Promise(resolve => { finish = resolve; }) : initialFetch(url);
    hotspots(ui)[0].click(); ui.change(1); await flush();
    assert.equal(ui.elements.spotDetailModal.hidden, true);
    finish({ok: true, json: async () => ({siteId: 1, spotId: 1, code: 'OLD', images: [{readUrl: '/old'}]})}); await flush();
    assert.equal(ui.elements.spotDetailModal.hidden, true); assert.equal(ui.elements.siteName.textContent, 'Site 2');
    ui.context.fetch = async () => ({ok: true, json: async () => ({siteId: 2, spotId: 1, code: 'NEW', name: '새 정원', images: [{readUrl: '/fresh'}]})});
    await ui.context.openSpotDetail({spotId: 1}); assert.equal(ui.elements.spotImage.src, '/fresh');
    ui.elements.spotDetailModal.click({target: ui.elements.spotDetailModal}); assert.equal(ui.elements.spotDetailModal.hidden, true);
});
test('monitoring uses enlarged circles and floating hover detail without moving hotspots', () => {
    const css = fs.readFileSync('src/main/resources/static/css/home-spatial.css', 'utf8');
    assert.match(css, /clamp\(110px, 13vw, 180px\)/);
    assert.match(css, /\.monitoring-hover-panel\s*\{[^}]*position: absolute/);
    assert.match(css, /pointer-events: none/);
    assert.match(css, /focus-visible \.monitoring-hover-panel/);
    assert.match(css, /border-radius: 16px/);
    assert.doesNotMatch(script, /spotInformationPanel|showSitePanelButton/);
});

for (const failure of ['legacy', 'null', '403', '404', '500', 'network', 'json', 'missingSpot', 'missingUrl']) {
    test('gallery failure is not an empty gallery: ' + failure, async () => {
        const ui = setup(); await flush();
        ui.context.fetch = async () => {
            if (failure === 'network') throw new Error('private failure details');
            return {ok: !['403', '404', '500'].includes(failure), json: async () => {
                if (failure === 'json') throw new Error('invalid json');
                if (failure === 'missingSpot') return [];
                const spot = {siteId: 1, spotId: 1, code: 'HS1', name: '정원', representativeImageUrl: '/representative'};
                if (failure === 'null') spot.images = null;
                if (failure === 'missingUrl') spot.images = [{imageId: 1}];
                return spot;
            }};
        };
        await ui.context.openSpotDetail({spotId: 1, code: 'HS1', name: '정원', representativeImageUrl: '/representative'});
        assert.match(ui.elements.spotGalleryStatus.textContent, /불러오지 못했습니다/);
        assert.doesNotMatch(ui.elements.spotImageEmpty.textContent, /등록된 이미지가 없습니다/);
        assert.equal(ui.elements.spotThumbnails.hidden, true);
    });
}
test('photo and map use identical PK-based gallery request and complete gallery', async () => {
    const ui = setup(); await flush(); const requests = [];
    ui.context.fetch = async (url, options) => {
        requests.push([url, options.cache]);
        return {ok: true, json: async () => ({siteId: 1, siteName: 'API Site', siteAddress: 'API 주소', spotId: 1, code: 'HS2', name: '곶자왈원', images: [
            {imageId: 6, displayOrder: 3, readUrl: '/six'},
            {imageId: 4, displayOrder: 1, readUrl: '/four'},
            {imageId: 5, displayOrder: 2, representative: true, readUrl: '/five'}
        ]})};
    };
    hotspots(ui)[0].click(); await flush();
    const gallery = () => ui.elements.spotThumbnails.children.map(button => button.children[0].src);
    assert.deepEqual(gallery(), ['/four', '/five', '/six']); assert.equal(ui.elements.spotImage.src, '/five');
    ui.context.closeSpotDetail(); ui.open(); ui.markers[0].click(); await flush();
    assert.deepEqual(gallery(), ['/four', '/five', '/six']); assert.equal(ui.elements.spotImage.src, '/five');
    assert.equal(ui.elements.spotSiteName.textContent, 'API Site');
    assert.equal(ui.elements.spotSiteAddress.textContent, 'API 주소');
    assert.deepEqual(requests, [['/api/sites/1/spots/1', 'no-store'], ['/api/sites/1/spots/1', 'no-store']]);
});
test('actual image download failure is a loading error, not no registered images', async () => {
    const ui = setup(); await flush();
    await ui.context.openSpotDetail({spotId: 1, code: 'HS1', name: '정원'});
    ui.elements.spotImage.onerror();
    assert.match(ui.elements.spotImageEmpty.textContent, /불러오지 못했습니다/);
    assert.doesNotMatch(ui.elements.spotImageEmpty.textContent, /등록된 이미지가 없습니다/);
});


test('display layout keeps photos and labels inside the stage without mutating saved positions', async () => {
    const layout = baseLayout();
    layout.spots = [0, 50, 100].map((x, index) => ({...layout.spots[0], spotId: index + 1, courseId: 19, xPercent: x}));
    const before = JSON.stringify(layout);
    const ui = setup({fetch: layoutFetch(layout)}); await flush();
    canvas(ui).clientWidth = 334;
    image(ui).naturalWidth = 1000; image(ui).naturalHeight = 700;
    image(ui).load();
    for (const [focus, shapes, deviation] of [[midFocus(ui), 2, 18], [nearFocus(ui), 3, 10]]) {
        assert.equal(focus.length, 2);
        const definitions=focus[0], mask=definitions.children[0], filter=definitions.children[1], focusImage=focus[1];
        assert.equal(mask.tag,'mask');
        assert.equal(mask.children[0].children.length,shapes);
        assert.equal(filter.children[0].tag,'feGaussianBlur');
        assert.equal(filter.children[0].stdDeviation,deviation);
        assert.equal(focusImage.href,'/storage/spatial');
        assert.equal(focusImage.width,334);
        assert.equal(focusImage.height,parseFloat(canvas(ui).style.minHeight));
    }
    const corridor=midFocus(ui)[0].children[0].children[0].children[0];
    assert.equal(corridor.tag,'rect');
    assert.equal(corridor.class,'monitoring-context-corridor-mask');
    assert.equal(corridor.width,120);
    assert.equal(corridor.x,107);
    assert.equal(corridor.rx,60);
    assert.ok(corridor.y < 0);
    assert.ok(corridor.height > parseFloat(canvas(ui).style.minHeight));
    assert.equal(JSON.stringify(layout), before);
    assert.equal(hotspots(ui)[0].style.top, hotspots(ui)[1].style.top);
    for (const button of hotspots(ui)) {
        const center = parseFloat(button.style.left), width = parseFloat(button.style.width);
        assert.ok(center - width / 2 >= 0 && center + width / 2 <= 334);
        assert.equal(button.children[0].style.width, '110px');
        assert.equal(button.children[1].children[3].textContent, 'GARDEN · 정원');
        assert.ok(parseFloat(button.style.top) + 110 + 146 <= parseFloat(canvas(ui).style.minHeight));
        assert.ok(parseFloat(button.children[2].style.width) <= 318);
    }
    canvas(ui).clientWidth = 1000; ui.windowEvents.resize();
    assert.equal(hotspots(ui)[0].children[0].style.width, '180px');
    assert.equal(corridor.width,160);
    assert.equal(corridor.x,420);
    assert.equal(JSON.stringify(layout), before);
});

const midFocus = ui => canvas(ui).children[1].children;
const nearFocus = ui => canvas(ui).children[2].children;
const halos = ui => canvas(ui).children[3].children;
const badges = ui => canvas(ui).children[4].children;
test('fixed Spot metric, hover rows and overall-only Course score stay synchronized', async () => {
    const ui = setup(); await flush(); image(ui).load();
    assert.equal(halos(ui).length, 1);
    const halo = halos(ui)[0];
    const before = [halo.style.left, halo.style.top, halo.style.width, halo.style.height];
    assert.equal(badges(ui)[0].children.length, 1);
    assert.equal(badges(ui)[0].children[0].textContent, 'HC1 · 코스');
    assert.equal(hotspots(ui)[0].children[0].style['--spot-data-color'],ui.context.window.HomeSurvey.haloColor(88.2,'stress'));
    assert.equal(hotspots(ui)[0].children[1].children[0].textContent, '스트레스');
    assert.equal(hotspots(ui)[0].children[1].children[1].children[0].textContent, '88.2%');
    assert.equal(hotspots(ui)[0].children[1].children[1].children[1].textContent, '개선');
    const auxiliary=hotspots(ui)[0].children[1].children[2];
    assert.equal(auxiliary.children[0].textContent, '17회 중 15회 개선');
    assert.equal(auxiliary.children[1].textContent, '최대 개선');
    assert.equal(auxiliary.children[1].hidden, false);
    assert.deepEqual([halo.style.left, halo.style.top, halo.style.width, halo.style.height], before);
    const hoverRows=hotspots(ui)[0].children[2].children.slice(1);
    assert.deepEqual(hoverRows.map(row=>row.children[0].textContent),['스트레스','정서 안정성']);
    assert.deepEqual(hoverRows.map(row=>row.children[1].children[0].textContent),['88.2% 개선','58.8% 개선']);
    assert.deepEqual(hoverRows.map(row=>row.children[1].children[1].textContent),
        ['17회 중 15회 개선','17회 중 10회 개선']);
    assert.ok(hoverRows.every(row=>row.children.length===2));
    assert.ok(!hotspots(ui)[0].children[2].children.some(child=>child.textContent==='대표'));
    assert.match(hotspots(ui)[0]['aria-label'], /스트레스 88.2% · 개선 · 17회 중 15회 개선/);
    assert.match(hotspots(ui)[0]['aria-label'], /정서 안정성 58.8% · 개선 · 17회 중 10회 개선/);
    hotspots(ui)[0].click(); await flush(); assert.equal(ui.elements.spotDetailModal.hidden, false);
    assert.ok(ui.elements.spotAnalysis.children.some(row => row.children && row.children.some(child => child.textContent === '측정 인원')));
    ui.change(1); assert.equal(canvas(ui).children.length, 0); await flush();
    assert.equal(halos(ui).length, 1); assert.notEqual(halos(ui)[0], halo);
});
test('stale HC response cannot restore another Site overlay', async () => {
    let finish;
    const ui = setup({fetch: url => url === '/admin/sites/1/spatial-layout/data'
        ? new Promise(resolve => { finish = resolve; })
        : layoutFetch({...baseLayout(), siteId: 2})(url)});
    ui.change(1); await flush();
    finish({ok: true, json: async () => ({...baseLayout(), spots: [{...baseLayout().spots[0], courseId: 99}]})});
    await flush(); assert.equal(halos(ui).length, 0); assert.equal(badges(ui).length, 0);
});

test('detail clears previous Site text during preview and rejects mismatched detail identities', async () => {
    const ui = setup(); await flush();
    await ui.context.openSpotDetail({spotId: 1});
    assert.equal(ui.elements.spotSiteName.textContent, '상세 Site');
    for (const identity of [{spotId: 2, siteId: 1}, {spotId: 1, siteId: 2}]) {
        ui.context.fetch = async () => ({ok: true, json: async () => ({...identity, siteName: '잘못된 Site', images: []})});
        const pending = ui.context.openSpotDetail({spotId: 1});
        assert.equal(ui.elements.spotSiteName.textContent, 'Site 1');
        assert.equal(ui.elements.spotSiteAddress.textContent, '주소');
        await pending;
        assert.equal(ui.elements.spotSiteName.textContent, 'Site 1');
        assert.match(ui.elements.spotGalleryStatus.textContent, /불러오지 못했습니다/);
    }
});
