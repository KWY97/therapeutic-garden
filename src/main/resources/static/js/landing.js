(() => {
    'use strict';

    const page = document.querySelector('.landing-page');
    if (!page) return;

    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
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

    // Display the server's directional value verbatim; presentation never recalculates a rate.
    function renderDirectionalValue(element, display, metric) {
        if (!element) return;
        const value = display || '데이터 준비 중';
        element.textContent = value;
        const match = /^(\d[\d.,]*%)\s+(.+)$/.exec(value);
        const direction = match ? match[2] : '';
        element.dataset.effectStatus = !match || direction === '변화 없음' ? 'neutral'
            : direction === (metric === 'stress' ? '감소' : '증가') ? 'improved' : 'worsened';
        if (match && document.createElement) {
            const number = document.createElement('span');
            number.className = 'landing-effect-number';
            number.textContent = match[1];
            const context = document.createElement('span');
            context.className = 'landing-effect-direction';
            context.textContent = match[2];
            element.textContent = '';
            element.append(number, document.createTextNode(' '), context);
        }
    }

    // Pause for explicit controls, keyboard focus, pointer inspection and hidden tabs.
    function rotationControl(container, stop, restart) {
        const button = container.querySelector('[data-rotation-toggle]');
        let paused = false;
        let inspecting = false;
        let focused = false;
        const update = () => {
            if (button) {
                button.disabled = motion.matches;
                button.setAttribute('aria-pressed', String(paused));
                button.textContent = motion.matches ? '자동 전환 꺼짐 · 모션 줄이기'
                    : paused ? '자동 전환 재생' : '자동 전환 일시정지';
            }
            stop();
            restart();
        };
        if (button) button.addEventListener('click', () => { paused = !paused; update(); });
        container.addEventListener('pointerenter', () => { inspecting = true; stop(); });
        container.addEventListener('pointerleave', () => { inspecting = false; restart(); });
        container.addEventListener('focusin', () => { focused = true; stop(); });
        container.addEventListener('focusout', (event) => {
            focused = Boolean(event.relatedTarget && container.contains(event.relatedTarget));
            if (!focused) restart();
        });
        document.addEventListener('visibilitychange', update);
        if (motion.addEventListener) motion.addEventListener('change', update);
        if (button) {
            button.disabled = motion.matches;
            if (motion.matches) button.textContent = '자동 전환 꺼짐 · 모션 줄이기';
        }
        return { canRun: () => !motion.matches && !document.hidden && !paused && !inspecting && !focused };
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
        renderDirectionalValue(stress, activeSpot.stress, 'stress');
        stress.dataset.value = activeSpot.stress || '';
        renderDirectionalValue(emotional, activeSpot.emotional, 'emotional');
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

    function initializeHealingSpotCarousels() {
        const courses = Array.from(page.querySelectorAll('.landing-course-summary'));
        const rotationDelay = 7500;

        courses.forEach((course, courseIndex) => {
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

            let control;
            const stop = () => window.clearTimeout(course.healingEffectTimer);
            const scheduleNext = (delay = rotationDelay) => {
                window.clearTimeout(course.healingEffectTimer);
                if (!control.canRun() || course.healingEffectSpots.length < 2) return;
                course.healingEffectTimer = window.setTimeout(() => {
                    changeHealingSpot(course, course.activeSpotIndex + 1);
                    scheduleNext();
                }, delay);
            };
            control = rotationControl(course, stop, scheduleNext);

            course.healingEffectSpots.forEach((spot, index) => {
                spot.button.addEventListener('click', () => {
                    changeHealingSpot(course, index);
                    announceSelection(course, `${spot.code} · ${spot.name}, 스트레스 ${spot.stress || '데이터 준비 중'}, 정서적 안정성 ${spot.emotional || '데이터 준비 중'}`);
                    scheduleNext(8500);
                });
            });

            changeHealingSpot(course, 0);
            scheduleNext(rotationDelay + (courseIndex * 750));
            course.scheduleNextHealingSpot = scheduleNext;
        });

    }

    function initializePersonalChangeAnimation() {
        const chart = page.querySelector('[data-personal-change-chart]');
        if (!chart) return;
        const spots = Array.from(chart.querySelectorAll('[data-personal-spot]'));
        if (!spots.length) return;
        const metrics = Array.from(chart.querySelectorAll('[data-personal-metric]'));
        const labels = {
            stress: { title: '평균 스트레스 증감률' },
            emotional: { title: '평균 정서적 안정성 증감률' }
        };
        let activeMetric = 'stress';
        let activeIndex = 0;
        let timer;
        let control;

        const render = () => {
            chart.dataset.activeMetric = activeMetric;
            const values = spots.map(spot => Number(spot.dataset[activeMetric]));
            const max = Math.max(...values.map(Math.abs), 1);
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
                spot.querySelector('[data-comparison-value]').textContent = display;
                spot.setAttribute('aria-label', `${spot.dataset.personalSpot} · ${spot.dataset.name}, ${labels[activeMetric].title} ${display}`);
                const bar = spot.querySelector('.landing-comparison-track i');
                bar.style.height = `${Math.abs(values[index]) / max * 50}%`;
                bar.style.top = values[index] < 0 ? '50%' : 'auto';
                bar.style.bottom = values[index] < 0 ? 'auto' : '50%';
                bar.dataset.effectStatus = values[index] === 0 ? 'neutral'
                    : values[index] > 0 ? 'improved' : 'worsened';
            });
            const spot = spots[activeIndex];
            chart.querySelector('[data-current-spot]').textContent = `${spot.dataset.personalSpot} · ${spot.dataset.name}`;
            chart.querySelector('[data-current-metric]').textContent = labels[activeMetric].title;
            renderDirectionalValue(chart.querySelector('[data-current-rate]'), spot.dataset[`${activeMetric}Display`], activeMetric);
        };
        const scheduleNext = (delay = 6500) => {
            window.clearTimeout(timer);
            if (!control.canRun()) return;
            timer = window.setTimeout(() => {
                activeIndex = (activeIndex + 1) % spots.length;
                render();
                scheduleNext();
            }, delay);
        };
        control = rotationControl(chart, () => window.clearTimeout(timer), scheduleNext);
        const announce = () => {
            const spot = spots[activeIndex];
            announceSelection(chart, `${spot.dataset.personalSpot} · ${spot.dataset.name}, ${labels[activeMetric].title} ${spot.dataset[`${activeMetric}Display`]}`);
        };
        spots.forEach((spot, index) => spot.addEventListener('click', () => {
            activeIndex = index;
            render();
            announce();
            scheduleNext(8500);
        }));
        metrics.forEach(button => button.addEventListener('click', () => {
            activeMetric = button.dataset.personalMetric;
            render();
            announce();
            scheduleNext(8500);
        }));
        render();
        scheduleNext();
    }

    initializeRevealAnimations();
    initializeHealingSpotCarousels();
    initializePersonalChangeAnimation();
    page.querySelectorAll('[data-effect-metric]').forEach(element => {
        renderDirectionalValue(element, element.textContent, element.dataset.effectMetric);
    });
})();
