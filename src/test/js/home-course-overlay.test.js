const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {window: {}};
vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/home-course-overlay.js', 'utf8'), context);
const api = context.window.HomeCourseOverlay;
const spot = (id, x, y) => ({spotId: id, courseId: id, courseCode: '동일 코드', courseName: 'DB 이름', xPercent: x, yPercent: y});
test('arbitrary course count grouped by identity, zero positioned omitted; single and multiple bounds', () => {
    const result = api.groups([spot(11, 30, 40), spot(11, 60, 70), spot(12, 50, 50), spot(13, null, null),
        ...Array.from({length: 7}, (_, i) => spot(20 + i, 10, 20))]);
    assert.equal(result.length, 9);
    assert.equal(result[0].spots.length, 2);
    assert.equal(JSON.stringify(result[0].bounds), JSON.stringify({left: 22, right: 68, top: 30, bottom: 80}));
    assert.equal(result[1].bounds.right - result[1].bounds.left, 16);
    assert.equal(result[1].bounds.bottom - result[1].bounds.top, 20);
    assert.equal(result[0].name, 'DB 이름');
});
test('bounds clamp at each image edge and all coordinates remain unchanged', () => {
    const spots = [spot(1, 0, 100), spot(1, 100, 0)];
    const before = JSON.stringify(spots), bounds = api.groups(spots)[0].bounds;
    assert.equal(JSON.stringify(bounds), JSON.stringify({left: 0, right: 100, top: 0, bottom: 100}));
    assert.equal(JSON.stringify(spots), before);
    assert.equal(api.groups([spot(1, -1, 10), spot(2, 20, ''), spot(3, Infinity, 20)]).length, 0);
});
test('course grouping never creates or aggregates analytical values', () => {
    const input = [spot(1, 20, 30), spot(1, 40, 50)];
    const before = JSON.stringify(input);
    assert.equal(api.groups(input)[0].spots.length, 2);
    assert.equal(JSON.stringify(input), before);
    assert.equal(api.aggregate, undefined);
    assert.equal(api.demoSpots, undefined);
});
test('badge search avoids hotspots, stays within desktop/mobile planes and never changes region', () => {
    for (const plane of [{w: 1000, h: 700}, {w: 334, h: 220}]) {
        const bounds = {left: 10, right: 70, top: 10, bottom: 70}, before = JSON.stringify(bounds);
        const obstacle = {x: 0, y: 0, w: plane.w, h: 65};
        const pos = api.badgePosition(bounds, {w: 110, h: 40}, plane, [obstacle]);
        assert.ok(pos.y >= 65); assert.ok(pos.x >= 0 && pos.x + pos.w <= plane.w);
        assert.ok(pos.y + pos.h <= plane.h); assert.equal(JSON.stringify(bounds), before);
    }
});
test('exact obstacle edges find a narrow mobile gap missed by grid candidates', () => {
    const plane = {w: 285, h: 211}, size = {w: 75, h: 37};
    const obstacles = [{x: 52, y: 15, w: 62, h: 62}, {x: 197, y: 22, w: 62, h: 62},
        {x: 217, y: 0, w: 62, h: 51}];
    const pos = api.badgePosition({left: 72, right: 95, top: 0, bottom: 35}, size, plane, obstacles);
    assert.ok(pos.x >= 114 && pos.x + pos.w <= 197);
    assert.ok(pos.y <= 4);
});
test('monitoring map matches the landing treatment while preserving hotspot priority and mobile styling', () => {
    const css = fs.readFileSync('src/main/resources/static/css/home-spatial.css', 'utf8');
    assert.match(css, /\.monitoring-course-halos, \.monitoring-course-badges\s*\{[^}]*pointer-events: none/);
    assert.match(css, /\.monitoring-course-halos\s*\{[^}]*z-index: 3/);
    assert.match(css, /\.monitoring-course-badges\s*\{[^}]*z-index: 4/);
    assert.match(css, /\.monitoring-hotspots\s*\{ z-index: 5/);
    assert.match(css, /--spot-ring-white-width: 2px/);
    assert.match(css, /--spot-ring-color-width: 3px/);
    assert.match(css, /--spot-glow-size: 12px/);
    assert.match(css, /--spot-glow-alpha: 28%/);
    assert.match(css, /\.monitoring-image\s*\{[^}]*filter: blur\(1\.5px\) brightness\(\.94\) saturate\(\.78\);[^}]*transform: scale\(1\.008\)/);
    assert.match(css, /\.monitoring-canvas::after\s*\{[^}]*linear-gradient\(180deg, #1c342515, transparent 50%, #1c342526\)/);
    assert.match(css, /\.monitoring-focus-layer\s*\{[^}]*display: none;[^}]*overflow: hidden;[^}]*pointer-events: none/);
    assert.match(css, /--halo-opacity: \.68/);
    assert.match(css, /\.monitoring-course-halo\s*\{[^}]*radial-gradient/);
    assert.match(css, /mix-blend-mode: normal/);
    assert.doesNotMatch(css, /\.monitoring-course-halo[^}]*filter: blur/);
    const js = fs.readFileSync('src/main/resources/static/js/home-spatial.js', 'utf8');
    assert.match(js, /createElementNS\('http:\/\/www\.w3\.org\/2000\/svg', 'mask'\)/);
    assert.match(js, /createElementNS\('http:\/\/www\.w3\.org\/2000\/svg', 'feGaussianBlur'\)/);
    assert.match(js, /monitoring-context-corridor-mask/);
    assert.match(js, /midFocus\.shapes\.append\(contextCorridor\)/);
    assert.doesNotMatch(js, /'clipPath'/);
    assert.doesNotMatch(js, /style\.(fillOpacity|strokeOpacity)/);
    assert.doesNotMatch(js, /aggregate\(/);
    assert.match(js, /--halo-color', color/);
    assert.doesNotMatch(js, /circle\.style\.borderColor/);
    assert.doesNotMatch(js, /monitoring-course-regions|region\.style\.stroke/);
    const html = fs.readFileSync('src/main/resources/templates/home.html', 'utf8');
    assert.doesNotMatch(html, /id="hcStress"|id="hcEmotional"|monitoring-effect-controls/);
    assert.match(html, /monitoring-effect-legend/);
    assert.doesNotMatch(html, /Demo 데이터|시연용 데이터/);
});

test('photo groups use arbitrary identities, keep pairs horizontal, avoid collisions and preserve input', () => {
    for (const width of [300, 334, 696, 1040]) {
        const input = [2, 2, 2, 1, 5].flatMap((count, course) => Array.from({length: count}, (_, i) => ({
            ...spot(31 + course, 15 + course * 12, 10 + i * 30), spotId: course * 10 + i
        })));
        const groups = api.groups(input), before = JSON.stringify(groups);
        const result = api.photoLayout(groups, width, width * .74);
        assert.equal(JSON.stringify(groups), before);
        for (const box of result.courses) {
            assert.ok(box.x >= 0 && box.x + box.w <= width);
            assert.ok(box.y >= 0 && box.y + box.h <= result.height);
            if (box.spots.length === 2) assert.equal(box.spots[0].y, box.spots[1].y);
            for (const other of result.courses) if (box !== other)
                assert.ok(box.x + box.w <= other.x || other.x + other.w <= box.x || box.y + box.h <= other.y || other.y + other.h <= box.y);
        }
    }
});

test('HC-B display group matches HC-C edge inset while preserving its internal photo padding', () => {
    const input = [['A',1,17.0068,35.447],['A',2,29.1498,21.918],['B',3,86.9433,9.4522],
        ['B',4,80.1942,24.4408],['C',5,70.5466,72.3295],['C',6,85.0202,86.5763]]
        .map(([course,id,x,y]) => ({spotId:id,courseId:course,courseCode:'HC-'+course,courseName:course,xPercent:x,yPercent:y}));
    const courses=api.groups(input), original=api.photoLayout(courses,1040,728);
    const before=JSON.stringify(original), geometry=api.applyCourseBreathingRoom(original,courses,1040);
    const b=geometry.courses.find(box=>box.courseId==='B'), c=geometry.courses.find(box=>box.courseId==='C');
    const originalB=original.courses.find(box=>box.courseId==='B');
    const referenceInset=1040-c.x-c.w;
    assert.equal(b.x,originalB.x-referenceInset);
    assert.equal(b.y,originalB.y+referenceInset);
    assert.equal(b.spots[1].x,originalB.spots[1].x-referenceInset);
    assert.equal(b.spots[1].y,originalB.spots[1].y+referenceInset);
    assert.equal(JSON.stringify(original),before);
    const boundary=api.visualBoundary(b,courses,1040,geometry.diameter,geometry.courses);
    assert.equal(1040-(boundary.x+boundary.width),referenceInset+2);
    assert.equal(boundary.y,referenceInset+2);
    assert.ok(boundary.x+boundary.width >= Math.max(...b.spots.map(s=>s.x+geometry.diameter/2))+2);
    for (const code of ['A','C']) {
        const box=geometry.courses.find(item=>item.courseId===code);
        const unchanged=api.visualBoundary(box,courses,1040,geometry.diameter,geometry.courses);
        assert.equal(unchanged.width,box.w-4);
        assert.deepEqual(box,original.courses.find(item=>item.courseId===code));
    }
});
