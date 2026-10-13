const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('member spatial uses configured API and retains the participant default', () => {
    const script = fs.readFileSync('src/main/resources/static/js/member-spatial.js', 'utf8');
    for (const base of [undefined, '/admin/members/4/data/api']) {
        const calls = [];
        const spatial = {load: (...args) => calls.push(args), setAnalysis: () => {}};
        const window = {HomeSpatial: () => spatial, MemberSpotDetail: {open: () => {}, connect: () => {}},
            memberMonitoringSiteId: 7, memberMonitoringEffects: [], memberMaximumImprovements: {},
            memberAnalysisApiBase: base};
        vm.runInNewContext(script, {window});
        assert.equal(calls[0][1].layoutUrl, (base || '/member/api') + '/spatial-layout');
    }
});
