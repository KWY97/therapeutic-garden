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

    function initializeReportPreviews() {
        const tabList = page.querySelector('[data-report-tabs]');
        const tabs = tabList ? Array.from(tabList.querySelectorAll('[data-report-tab]')) : [];
        const panels = Array.from(page.querySelectorAll('[data-report-panel]'));
        const activateTab = (tab, focus = false) => {
            const selected = tab.dataset.reportTab;
            tabs.forEach(item => {
                const active = item === tab;
                item.classList.toggle('is-active', active);
                item.setAttribute('aria-selected', String(active));
                item.tabIndex = active ? 0 : -1;
            });
            panels.forEach(panel => { panel.hidden = panel.dataset.reportPanel !== selected; });
            if (focus) tab.focus();
        };

        tabs.forEach(tab => tab.addEventListener('click', () => activateTab(tab)));
        if (tabList && tabs.length) {
            tabList.addEventListener('keydown', event => {
                const current = tabs.indexOf(event.target);
                if (current < 0) return;
                let next = current;
                if (event.key === 'ArrowRight') next = (current + 1) % tabs.length;
                else if (event.key === 'ArrowLeft') next = (current - 1 + tabs.length) % tabs.length;
                else if (event.key === 'Home') next = 0;
                else if (event.key === 'End') next = tabs.length - 1;
                else return;
                event.preventDefault();
                activateTab(tabs[next], true);
            });
        }

        const modal = page.querySelector('[data-report-modal]');
        const modalDialog = modal && modal.querySelector('[data-report-modal-dialog]');
        const modalBody = modal && modal.querySelector('[data-report-modal-body]');
        const modalTitle = modal && modal.querySelector('[data-report-modal-title]');
        const closeButton = modal && modal.querySelector('[data-report-modal-close]');
        if (!modal || !modalDialog || !modalBody || !modalTitle || !closeButton) return;

        const modalImage = document.createElement('img');
        modalImage.className = 'landing-report-modal-image';
        modalBody.append(modalImage);
        let previousFocus = null;
        let previousOverflow = '';

        const closeModal = () => {
            if (modal.hidden) return;
            modal.hidden = true;
            document.body.style.overflow = previousOverflow;
            if (previousFocus && previousFocus.focus) previousFocus.focus();
        };
        page.querySelectorAll('[data-report-preview]').forEach(trigger => {
            trigger.addEventListener('click', () => {
                const preview = trigger.querySelector('img');
                const source = trigger.dataset.reportPreviewSrc || (preview && (preview.currentSrc || preview.src));
                if (!source) return;
                previousFocus = document.activeElement || trigger;
                previousOverflow = document.body.style.overflow;
                modalTitle.textContent = trigger.dataset.reportPreviewTitle;
                modalImage.src = source;
                modalImage.alt = trigger.dataset.reportPreviewAlt || (preview && preview.alt) || '';
                modal.dataset.previewKind = trigger.dataset.reportPreviewKind;
                modal.hidden = false;
                document.body.style.overflow = 'hidden';
                modalBody.scrollTop = 0;
                closeButton.focus();
            });
        });
        closeButton.addEventListener('click', closeModal);
        modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });
        document.addEventListener('keydown', event => {
            if (modal.hidden) return;
            if (event.key === 'Escape') {
                event.preventDefault();
                closeModal();
                return;
            }
            if (event.key !== 'Tab') return;
            const controls = Array.from(modalDialog.querySelectorAll('button, [href], [tabindex]'))
                .filter(control => control.tabIndex >= 0 && !control.hidden);
            if (!controls.length) return;
            const first = controls[0];
            const last = controls[controls.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        });
    }

    initializeCourseConnectors();
    initializeRevealAnimations();
    initializeHealingSpotSelectors();
    initializeReportPreviews();
    page.querySelectorAll('[data-effect-metric]').forEach(element => {
        renderImprovementValue(element, element.textContent);
    });
})();
