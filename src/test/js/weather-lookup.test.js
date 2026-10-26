const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const WeatherLookup = require('../../main/resources/static/js/weather-lookup.js');

test('calculates the advertised range from the current date in Seoul', () => {
    const range = WeatherLookup.getAvailableRange(new Date('2026-10-25T15:00:00Z'));

    assert.deepEqual(range, {
        start: '2026-08-18',
        end: '2026-11-10',
        label: '26.08.18 ~ 26.11.10'
    });
});

test('includes both boundaries and rejects dates immediately outside them', () => {
    const range = WeatherLookup.getAvailableRange(new Date('2026-10-26T03:00:00Z'));

    assert.equal(WeatherLookup.isDateAvailable('2026-08-18', range), true);
    assert.equal(WeatherLookup.isDateAvailable('2026-11-10', range), true);
    assert.equal(WeatherLookup.isDateAvailable('2026-08-17', range), false);
    assert.equal(WeatherLookup.isDateAvailable('2026-11-11', range), false);
});

test('blocks an out-of-range lookup without making a request', async () => {
    const ui = elements('2026-08-17');
    let requests = 0;

    WeatherLookup.bind({
        ...ui,
        now: new Date('2026-10-26T03:00:00Z'),
        fetcher: async () => {
            requests++;
            return {ok: true, json: async () => ({})};
        }
    });
    await ui.button.click();

    assert.equal(requests, 0);
    assert.equal(ui.hint.textContent, '불러올 수 있는 날씨의 범위는 26.08.18 ~ 26.11.10 입니다.');
    assert.equal(ui.status.dataset.state, 'error');
    assert.match(ui.status.textContent, /26\.08\.18 ~ 26\.11\.10/);
});

test('shows the server message and does not replace existing weather values', async () => {
    const ui = elements('2026-10-26');
    ui.weatherInput.value = '맑음';

    WeatherLookup.bind({
        ...ui,
        now: new Date('2026-10-26T03:00:00Z'),
        fetcher: async () => ({
            ok: false,
            json: async () => ({message: '선택한 날짜의 날씨 데이터가 제공되지 않습니다.'})
        })
    });
    await ui.button.click();

    assert.equal(ui.status.textContent, '선택한 날짜의 날씨 데이터가 제공되지 않습니다.');
    assert.equal(ui.weatherInput.value, '맑음');
    assert.equal(ui.button.disabled, false);
});

test('both schedule screens use the shared accessible weather lookup UI', () => {
    for (const template of ['schedule-form.html', 'schedule-edit.html']) {
        const html = fs.readFileSync(`src/main/resources/templates/admin/${template}`, 'utf8');
        assert.match(html, /\/js\/weather-lookup\.js/);
        assert.match(html, /id="weatherRangeHint"/);
        assert.match(html, /id="weatherLookupStatus"/);
        assert.match(html, /aria-live="polite"/);
        assert.doesNotMatch(html, /alert\(/);
    }
});

function elements(date) {
    const button = {
        disabled: false,
        addEventListener(type, listener) {
            if (type === 'click') this.click = listener;
        }
    };
    const value = initial => ({value: initial});

    return {
        button,
        hint: {textContent: ''},
        status: {textContent: '', dataset: {}},
        dateInput: value(date),
        timeInput: value('09:00'),
        weatherInput: value(''),
        temperatureInput: value(''),
        humidityInput: value('')
    };
}
