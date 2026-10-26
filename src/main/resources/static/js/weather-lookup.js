(function (root, factory) {
    const api = factory();

    if (typeof module === "object" && module.exports) {
        module.exports = api;
    }

    root.WeatherLookup = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    const PAST_DAYS = 69;
    const FORECAST_DAYS = 16;
    const SEOUL_TIME_ZONE = "Asia/Seoul";

    function getSeoulDateParts(now) {
        const parts = new Intl.DateTimeFormat("en-CA", {
            timeZone: SEOUL_TIME_ZONE,
            year: "numeric",
            month: "2-digit",
            day: "2-digit"
        }).formatToParts(now || new Date());
        const values = Object.fromEntries(parts.map(part => [part.type, part.value]));

        return {
            year: Number(values.year),
            month: Number(values.month),
            day: Number(values.day)
        };
    }

    function shiftDate(parts, days) {
        const date = new Date(Date.UTC(parts.year, parts.month - 1, parts.day));
        date.setUTCDate(date.getUTCDate() + days);

        return {
            year: date.getUTCFullYear(),
            month: date.getUTCMonth() + 1,
            day: date.getUTCDate()
        };
    }

    function pad(value) {
        return String(value).padStart(2, "0");
    }

    function toIsoDate(parts) {
        return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}`;
    }

    function toShortDate(parts) {
        return `${pad(parts.year % 100)}.${pad(parts.month)}.${pad(parts.day)}`;
    }

    function getAvailableRange(now) {
        const today = getSeoulDateParts(now);
        const start = shiftDate(today, -PAST_DAYS);
        const end = shiftDate(today, FORECAST_DAYS - 1);

        return {
            start: toIsoDate(start),
            end: toIsoDate(end),
            label: `${toShortDate(start)} ~ ${toShortDate(end)}`
        };
    }

    function isDateAvailable(date, range) {
        return Boolean(date) && date >= range.start && date <= range.end;
    }

    async function readErrorMessage(response) {
        try {
            const body = await response.json();
            return body.message || "날씨 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
        } catch (error) {
            return "날씨 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
        }
    }

    function bind(options) {
        const range = getAvailableRange(options.now);
        const setStatus = function (message, state) {
            options.status.textContent = message;
            options.status.dataset.state = state || "";
        };

        options.hint.textContent = `불러올 수 있는 날씨의 범위는 ${range.label} 입니다.`;

        options.button.addEventListener("click", async function () {
            const date = options.dateInput.value;
            const time = options.timeInput.value;

            if (!date || !time) {
                setStatus("일정 날짜와 첫 번째 시작 시간을 먼저 입력해 주세요.", "error");
                return;
            }

            if (!isDateAvailable(date, range)) {
                setStatus(`날씨는 ${range.label} 범위에서만 불러올 수 있습니다.`, "error");
                return;
            }

            options.button.disabled = true;
            setStatus("날씨 정보를 불러오는 중입니다.", "loading");

            try {
                const response = await options.fetcher(
                    `/admin/weather?date=${encodeURIComponent(date)}&time=${encodeURIComponent(time)}`
                );

                if (!response.ok) {
                    setStatus(await readErrorMessage(response), "error");
                    return;
                }

                const data = await response.json();

                if (!data || data.weather == null || data.temperature == null || data.humidity == null) {
                    setStatus("선택한 날짜의 날씨 데이터가 제공되지 않습니다. 다른 날짜를 선택해 주세요.", "error");
                    return;
                }

                options.weatherInput.value = data.weather;
                options.temperatureInput.value = data.temperature;
                options.humidityInput.value = data.humidity;
                setStatus("날씨 정보를 불러왔습니다.", "success");
            } catch (error) {
                setStatus("날씨 정보를 불러오는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", "error");
            } finally {
                options.button.disabled = false;
            }
        });

        return range;
    }

    return {
        bind,
        getAvailableRange,
        isDateAvailable
    };
}));
