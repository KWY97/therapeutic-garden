(() => {
    'use strict';

    const page = document.querySelector('.landing-page');
    if (!page) return;

    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    function initializeCourseConnectors() {
        const stage = page.querySelector('.landing-preview-stage');
        if (!stage) return;

        const namespace = 'http://www.w3.org/2000/svg';
        const courses = [
            { key: 'a', spots: ['hs1', 'hs2'] },
            { key: 'b', spots: ['hs3', 'hs4'] },
            { key: 'c', spots: ['hs5', 'hs6'] }
        ].map(course => ({
            ...course,
            label: stage.querySelector(`.landing-preview-course-${course.key}`),
            circles: course.spots.map(spot => stage.querySelector(`.landing-preview-spot-${spot} .monitoring-hotspot-circle`))
        })).filter(course => course.label && course.circles.every(Boolean));
        if (!courses.length) return;

        const svg = document.createElementNS(namespace, 'svg');
        svg.classList.add('landing-course-links');
        svg.setAttribute('aria-hidden', 'true');
        courses.forEach(course => {
            const group = document.createElementNS(namespace, 'g');
            group.classList.add(`landing-course-link-${course.key}`);
            ['glow'].forEach(type => {
                const line = document.createElementNS(namespace, 'line');
                line.classList.add(`landing-course-link-${type}`);
                group.append(line);
                course[type] = line;
            });
            svg.append(group);
        });
        stage.insertBefore(svg, stage.querySelector('.landing-preview-course'));

        const percentToPixels = (value, size) => {
            const parsed = Number.parseFloat(value);
            return Number.isFinite(parsed) ? parsed / 100 * size : 0;
        };
        const intersectsCircle = (box, obstacle) => {
            const nearestX = Math.max(box.left, Math.min(obstacle.x, box.right));
            const nearestY = Math.max(box.top, Math.min(obstacle.y, box.bottom));
            return Math.hypot(obstacle.x - nearestX, obstacle.y - nearestY) < obstacle.radius;
        };
        const intersectsRect = (box, obstacle) => box.left < obstacle.right && box.right > obstacle.left
            && box.top < obstacle.bottom && box.bottom > obstacle.top;

        let frame = 0;
        const render = () => {
            frame = 0;
            const stageRect = stage.getBoundingClientRect();
            if (!stageRect.width || !stageRect.height) return;
            const mobile = window.matchMedia('(max-width: 800px)').matches;
            svg.setAttribute('viewBox', `0 0 ${stageRect.width} ${stageRect.height}`);

            const circleObstacles = Array.from(stage.querySelectorAll('.monitoring-hotspot-circle')).map(circle => {
                const rect = circle.getBoundingClientRect();
                return {
                    x: rect.left - stageRect.left + rect.width / 2,
                    y: rect.top - stageRect.top + rect.height / 2,
                    radius: Math.max(rect.width, rect.height) / 2 + 8
                };
            });
            const cardObstacles = Array.from(stage.querySelectorAll('.monitoring-hotspot-label'))
                .filter(card => getComputedStyle(card).display !== 'none')
                .map(card => {
                    const rect = card.getBoundingClientRect();
                    return {
                        left: rect.left - stageRect.left - 5,
                        top: rect.top - stageRect.top - 5,
                        right: rect.right - stageRect.left + 5,
                        bottom: rect.bottom - stageRect.top + 5
                    };
                });
            const placedLabels = [];

            courses.forEach(course => {
                const circleRects = course.circles.map(circle => circle.getBoundingClientRect());
                const centers = circleRects.map(rect => ({
                    x: rect.left - stageRect.left + rect.width / 2,
                    y: rect.top - stageRect.top + rect.height / 2,
                    radius: Math.max(rect.width, rect.height) / 2
                }));
                const [first, second] = centers;
                const dx = second.x - first.x;
                const dy = second.y - first.y;
                const setLine = (line, from, to) => {
                    line.setAttribute('x1', from.x);
                    line.setAttribute('y1', from.y);
                    line.setAttribute('x2', to.x);
                    line.setAttribute('y2', to.y);
                };
                setLine(course.glow, first, second);
                course.glow.setAttribute('stroke-width', Math.max(first.radius, second.radius) * 2 * (mobile ? 1.3 : 1.35));
                const style = getComputedStyle(course.label);
                const nudgeX = percentToPixels(style.getPropertyValue('--course-nudge-x'), stageRect.width);
                const nudgeY = percentToPixels(style.getPropertyValue('--course-nudge-y'), stageRect.height);
                const preferred = {
                    x: (first.x + second.x) / 2 + nudgeX,
                    y: (first.y + second.y) / 2 + nudgeY
                };
                const labelRect = course.label.getBoundingClientRect();
                const halfWidth = labelRect.width / 2;
                const halfHeight = labelRect.height / 2;
                const fits = point => {
                    const box = {
                        left: point.x - halfWidth,
                        right: point.x + halfWidth,
                        top: point.y - halfHeight,
                        bottom: point.y + halfHeight
                    };
                    if (box.left < 5 || box.right > stageRect.width - 5 || box.top < 5 || box.bottom > stageRect.height - 5) return false;
                    return !circleObstacles.some(obstacle => intersectsCircle(box, obstacle))
                        && !cardObstacles.some(obstacle => intersectsRect(box, obstacle))
                        && !placedLabels.some(obstacle => intersectsRect(box, obstacle));
                };
                let position = preferred;
                if (!fits(position)) {
                    const angleOffset = nudgeX || nudgeY ? Math.atan2(nudgeY, nudgeX) : Math.atan2(-dx, dy);
                    search: for (let radius = 4; radius <= Math.max(stageRect.width, stageRect.height) * .4; radius += 4) {
                        for (let index = 0; index < 24; index += 1) {
                            const turn = index === 0 ? 0 : Math.ceil(index / 2) * (index % 2 ? 1 : -1);
                            const angle = angleOffset + turn * Math.PI / 12;
                            const candidate = {
                                x: preferred.x + Math.cos(angle) * radius,
                                y: preferred.y + Math.sin(angle) * radius
                            };
                            if (fits(candidate)) {
                                position = candidate;
                                break search;
                            }
                        }
                    }
                }
                course.label.style.left = `${position.x / stageRect.width * 100}%`;
                course.label.style.top = `${position.y / stageRect.height * 100}%`;
                placedLabels.push({
                    left: position.x - halfWidth - 4,
                    right: position.x + halfWidth + 4,
                    top: position.y - halfHeight - 4,
                    bottom: position.y + halfHeight + 4
                });
            });
        };
        const scheduleRender = () => {
            if (frame) cancelAnimationFrame(frame);
            frame = requestAnimationFrame(render);
        };
        const map = stage.querySelector('.landing-preview-map');
        if (map && !map.complete) map.addEventListener('load', scheduleRender, { once: true });
        if ('ResizeObserver' in window) new ResizeObserver(scheduleRender).observe(stage);
        else window.addEventListener('resize', scheduleRender);
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(scheduleRender);
        scheduleRender();
    }

    function initializeRevealAnimations() {
        const elements = page.querySelectorAll('[data-reveal]');
        if (!elements.length || motion.matches || !('IntersectionObserver' in window)) return;

        let observer;
        const showAll = () => {
            document.documentElement.classList.remove('landing-reveal-enabled');
            if (observer) observer.disconnect();
        };

        try {
            observer = new IntersectionObserver((entries) => {
                entries.forEach((entry) => {
                    if (entry.isIntersecting) {
                        entry.target.classList.add('is-visible');
                        observer.unobserve(entry.target);
                    }
                });
            }, { threshold: 0, rootMargin: '0px 0px -24px 0px' });

            elements.forEach((element) => observer.observe(element));
            document.documentElement.classList.add('landing-reveal-enabled');
            if (motion.addEventListener) {
                motion.addEventListener('change', (event) => {
                    if (event.matches) showAll();
                });
            }
            page.addEventListener('focusin', (event) => {
                let element = event.target.closest('[data-reveal]');
                while (element) {
                    element.classList.add('is-visible');
                    observer.unobserve(element);
                    element = element.parentElement.closest('[data-reveal]');
                }
            });
        } catch (_) {
            showAll();
        }
    }

    // Display the server-formatted improvement rate verbatim; presentation never recalculates it.
    function renderImprovementValue(element, display) {
        if (!element) return;
        const value = display || '데이터 준비 중';
        element.textContent = value;
        const match = /^(\d[\d.,]*%)(?:\s+(.+))?$/.exec(value);
        element.dataset.effectStatus = match ? 'improved' : 'neutral';
        if (match && document.createElement) {
            const number = document.createElement('span');
            number.className = 'landing-effect-number';
            number.textContent = match[1];
            element.textContent = '';
            element.append(number);
            if (match[2]) {
                const context = document.createElement('span');
                context.className = 'landing-effect-direction';
                context.textContent = match[2];
                element.append(document.createTextNode(' '), context);
            }
        }
    }

    function announceSelection(container, text) {
        const status = container.querySelector('[data-selection-status]');
        if (status) status.textContent = text;
    }

    function changeHealingSpot(course, spotIndex) {
        const spots = course.healingEffectSpots || [];
        if (!spots.length) return;
        const nextIndex = ((spotIndex % spots.length) + spots.length) % spots.length;
        const activeSpot = spots[nextIndex];

        spots.forEach((spot, index) => {
            const active = index === nextIndex;
            spot.button.classList.toggle('is-active', active);
            spot.button.setAttribute('aria-pressed', String(active));
            spot.image.classList.toggle('is-active', active);
            spot.image.setAttribute('aria-hidden', String(!active));
        });

        const stress = course.querySelector('[data-field="stress-reduction"]');
        const emotional = course.querySelector('[data-field="emotional-increase"]');
        renderImprovementValue(stress, activeSpot.stress);
        stress.dataset.value = activeSpot.stress || '';
        renderImprovementValue(emotional, activeSpot.emotional);
        emotional.dataset.value = activeSpot.emotional || '';
        course.activeSpotIndex = nextIndex;
        const fields = {
            'spot-name': `${activeSpot.code} · ${activeSpot.name}`,
            'course-id': activeSpot.button.dataset.course,
            'course-name': activeSpot.button.dataset.courseName
        };
        Object.entries(fields).forEach(([field, value]) => {
            const target = course.querySelector(`[data-field="${field}"]`);
            if (target && value) target.textContent = value;
        });
        if (activeSpot.button.dataset.course) course.dataset.courseId = activeSpot.button.dataset.course;
        const indexLabel = course.querySelector('[data-spot-index]');
        if (indexLabel) indexLabel.textContent = `${String(nextIndex + 1).padStart(2, '0')} / ${String(spots.length).padStart(2, '0')}`;
    }

    function initializeHealingSpotSelectors() {
        const courses = Array.from(page.querySelectorAll('.landing-course-summary'));

        courses.forEach((course) => {
            const buttons = Array.from(course.querySelectorAll('.landing-course-spots button'));
            course.healingEffectSpots = buttons.map((button) => ({
                code: button.dataset.code,
                name: button.dataset.name,
                image: course.querySelector(`[data-spot-image="${button.dataset.code}"]`),
                stress: button.dataset.stress,
                emotional: button.dataset.emotional,
                button
            })).filter((spot) => spot.image);
            course.activeSpotIndex = 0;

            course.healingEffectSpots.forEach((spot, index) => {
                spot.button.addEventListener('click', () => {
                    changeHealingSpot(course, index);
                    announceSelection(course, `${spot.code} · ${spot.name}, 스트레스 ${spot.stress || '데이터 준비 중'}, 정서 안정성 ${spot.emotional || '데이터 준비 중'}`);
                });
            });

            changeHealingSpot(course, 0);
        });
    }

    function initializePersonalChangeSelectors() {
        const chart = page.querySelector('[data-personal-change-chart]');
        if (!chart) return;
        const spots = Array.from(chart.querySelectorAll('[data-personal-spot]'));
        if (!spots.length) return;
        const metrics = Array.from(chart.querySelectorAll('[data-personal-metric]'));
        const labels = {
            stress: { title: '스트레스 개선율' },
            emotional: { title: '정서 안정성 개선율' }
        };
        let activeMetric = 'stress';
        let activeIndex = 0;

        const render = () => {
            chart.dataset.activeMetric = activeMetric;
            const values = spots.map(spot => {
                const value = Number(spot.dataset[activeMetric]);
                return Number.isFinite(value) ? value : null;
            });
            const max = Math.max(...values.filter(value => value != null), 1);
            metrics.forEach(button => {
                const active = button.dataset.personalMetric === activeMetric;
                button.classList.toggle('is-active', active);
                button.setAttribute('aria-pressed', String(active));
            });
            spots.forEach((spot, index) => {
                const active = index === activeIndex;
                spot.classList.toggle('is-active', active);
                spot.setAttribute('aria-pressed', String(active));
                const display = spot.dataset[`${activeMetric}Display`];
                const count = spot.dataset[`${activeMetric}Count`] || '';
                const maximum = spot.dataset[`${activeMetric}Maximum`] === 'true' ? '최대 개선' : '';
                spot.querySelector('[data-comparison-value]').textContent = display;
                spot.querySelector('[data-comparison-count]').textContent = count.replace(/ 개선$/, '');
                spot.setAttribute('aria-label', [
                    `${spot.dataset.personalSpot} · ${spot.dataset.name}`,
                    `${labels[activeMetric].title} ${display}`,
                    count,
                    maximum
                ].filter(Boolean).join(', '));
                const bar = spot.querySelector('.landing-comparison-track i');
                bar.style.height = `${values[index] == null ? 0 : values[index] / max * 100}%`;
                bar.style.top = 'auto';
                bar.style.bottom = '0';
                bar.dataset.effectStatus = values[index] == null ? 'neutral' : 'improved';
            });
            const spot = spots[activeIndex];
            chart.querySelector('[data-current-spot]').textContent = `${spot.dataset.personalSpot} · ${spot.dataset.name}`;
            chart.querySelector('[data-current-metric]').textContent = labels[activeMetric].title;
            renderImprovementValue(chart.querySelector('[data-current-rate]'), spot.dataset[`${activeMetric}RateDisplay`]);
            chart.querySelector('[data-current-count]').textContent = spot.dataset[`${activeMetric}Count`] || '';
        };
        const announce = () => {
            const spot = spots[activeIndex];
            const maximum = spot.dataset[`${activeMetric}Maximum`] === 'true' ? '최대 개선' : '';
            announceSelection(chart, [
                `${spot.dataset.personalSpot} · ${spot.dataset.name}`,
                `${labels[activeMetric].title} ${spot.dataset[`${activeMetric}Display`]}`,
                spot.dataset[`${activeMetric}Count`],
                maximum
            ].filter(Boolean).join(', '));
        };
        spots.forEach((spot, index) => spot.addEventListener('click', () => {
            activeIndex = index;
            render();
            announce();
        }));
        metrics.forEach(button => button.addEventListener('click', () => {
            activeMetric = button.dataset.personalMetric;
            render();
            announce();
        }));
        render();
    }

    initializeCourseConnectors();
    initializeRevealAnimations();
    initializeHealingSpotSelectors();
    initializePersonalChangeSelectors();
    page.querySelectorAll('[data-effect-metric]').forEach(element => {
        renderImprovementValue(element, element.textContent);
    });
})();
