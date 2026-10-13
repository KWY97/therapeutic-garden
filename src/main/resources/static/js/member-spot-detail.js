(function () {
    'use strict';

    var modal = document.getElementById('spotDetailModal');
    if (!modal) return;
    var dialog = modal.querySelector('[role="dialog"]');
    var closeButton = document.getElementById('closeSpotDetailButton');
    var spotImage = document.getElementById('spotImage');
    var survey = window.HomeSurvey;
    var spatial = null;
    var previousFocus = null;
    var previousOverflow = '';
    var backgroundState = [];
    var requestVersion = 0;

    function connect(nextSpatial) {
        spatial = nextSpatial;
    }

    function renderSpotInformation(spot) {
        var course = spot.course || [spot.courseCode, spot.courseName].filter(Boolean).join(' · ');
        document.getElementById('spotId').textContent = spot.code || '';
        document.getElementById('spotName').textContent = spot.name || '';
        document.getElementById('spotCourse').textContent = course;
        document.getElementById('spotDetailTitle').textContent = [spot.code, spot.name].filter(Boolean).join(' · ');
        document.getElementById('spotDetailCourse').textContent = course;
        document.getElementById('spotSiteName').textContent = spot.siteName || '';
        document.getElementById('spotSiteAddress').textContent = spot.siteAddress || '';
    }

    function showSpotGalleryImage(url, alt, message) {
        var empty = document.getElementById('spotImageEmpty');
        spotImage.hidden = true;
        empty.hidden = false;
        empty.textContent = message;
        spotImage.onload = function () { spotImage.hidden = false; empty.hidden = true; };
        spotImage.onerror = function () {
            spotImage.hidden = true;
            empty.hidden = false;
            empty.textContent = '이미지를 불러오지 못했습니다. 닫은 뒤 다시 열어 주세요.';
        };
        spotImage.alt = alt;
        spotImage.referrerPolicy = 'no-referrer';
        if (url) spotImage.src = url;
        else spotImage.removeAttribute('src');
    }

    function resetSpotGallery(spot, message, previewUrl) {
        var thumbnails = document.getElementById('spotThumbnails');
        thumbnails.replaceChildren();
        thumbnails.hidden = true;
        showSpotGalleryImage(previewUrl, spot.code + ' ' + spot.name, message);
    }

    function renderSpotGallery(spot) {
        var thumbnails = document.getElementById('spotThumbnails');
        thumbnails.replaceChildren();
        if (!Array.isArray(spot.images)
                || spot.images.some(image => !image || typeof image.readUrl !== 'string' || !image.readUrl.trim())
                || (!spot.images.length && spot.representativeImageUrl)) throw new Error('잘못된 이미지 응답');
        var images = spot.images.slice().sort((a, b) => a.displayOrder - b.displayOrder || a.imageId - b.imageId);
        var initial = images.find(image => image.representative) || images[0];
        var controls = [];
        function choose(image) {
            showSpotGalleryImage(image ? image.readUrl : null, spot.code + ' ' + spot.name,
                image ? '이미지를 불러오는 중입니다.' : spot.code + ' ' + spot.name + ' · 등록된 이미지가 없습니다.');
            controls.forEach(entry => entry.button.setAttribute('aria-pressed', String(entry.image === image)));
        }
        images.forEach((image, index) => {
            var button = document.createElement('button');
            button.type = 'button';
            button.setAttribute('aria-label', spot.code + ' 이미지 ' + (index + 1) + (image.representative ? ' · 대표' : ''));
            var photo = document.createElement('img');
            photo.alt = '이미지 ' + (index + 1);
            photo.referrerPolicy = 'no-referrer';
            photo.src = image.readUrl;
            button.append(photo);
            button.addEventListener('click', () => choose(image));
            controls.push({button: button, image: image});
            thumbnails.append(button);
        });
        thumbnails.hidden = images.length < 2;
        choose(initial);
    }

    function measurementDates(spotCode) {
        var history = (window.memberMeasurementHistory || []).find(item => item.spotCode === spotCode);
        return history && Array.isArray(history.records)
            ? history.records.map(record => record.measurementDate).filter(Boolean).sort()
            : [];
    }

    function renderAnalysis(spot) {
        var effect = survey.indexByCode(window.memberMonitoringEffects || []).get(spot.code);
        var dates = measurementDates(spot.code);
        survey.renderSpot(document.getElementById('spotAnalysis'), {
            participant: 'member', participantLabel: window.memberDisplayName,
            effect: dates.length ? effect : null, measurementDates: dates
        });
        window.MeasurementHistory.renderSpot(document.getElementById('spotMeasurementHistory'),
            window.memberMeasurementHistory || [], spot.code, {participant: true, compact: true});
    }

    function lockBackground() {
        backgroundState = [document.querySelector('body > header'), document.querySelector('body > main')]
            .filter(Boolean).map(element => ({element: element, inert: element.inert}));
        backgroundState.forEach(entry => { entry.element.inert = true; });
    }

    function restoreBackground() {
        backgroundState.forEach(entry => { entry.element.inert = entry.inert; });
        backgroundState = [];
    }

    async function open(spot) {
        if (!spot || spot.spotId == null) return;
        var version = ++requestVersion;
        if (modal.hidden) {
            previousFocus = document.activeElement;
            previousOverflow = document.body.style.overflow;
            lockBackground();
        }
        if (spatial) spatial.select(spot.spotId);
        renderSpotInformation(spot);
        renderAnalysis(spot);
        resetSpotGallery(spot, '이미지를 불러오는 중입니다.', spot.representativeImageUrl);
        modal.hidden = false;
        document.body.style.overflow = 'hidden';
        dialog.scrollTop = 0;
        closeButton.focus();
        var status = document.getElementById('spotGalleryStatus');
        status.textContent = '이미지를 불러오는 중입니다.';
        try {
            var response = await fetch((window.memberAnalysisApiBase || '/member/api')
                + '/monitoring/spots/' + encodeURIComponent(spot.spotId), {cache: 'no-store'});
            if (!response.ok) throw new Error('HS 조회 실패');
            var fresh = await response.json();
            if (version !== requestVersion || modal.hidden) return;
            if (!fresh || Array.isArray(fresh) || String(fresh.spotId) !== String(spot.spotId)
                    || (window.memberMonitoringSiteId != null
                        && String(fresh.siteId) !== String(window.memberMonitoringSiteId))) throw new Error('잘못된 HS 응답');
            renderSpotInformation(fresh);
            renderSpotGallery(fresh);
            renderAnalysis(fresh);
            status.textContent = '';
        } catch (error) {
            if (version !== requestVersion || modal.hidden) return;
            resetSpotGallery(spot, '이미지를 불러오지 못했습니다.');
            status.textContent = '이미지를 불러오지 못했습니다. 닫은 뒤 다시 열어 주세요.';
        }
    }

    function close(restoreFocus) {
        ++requestVersion;
        if (modal.hidden) return;
        modal.hidden = true;
        restoreBackground();
        document.body.style.overflow = previousOverflow;
        if (spatial) spatial.select(null);
        if (restoreFocus !== false && previousFocus) previousFocus.focus();
    }

    closeButton.addEventListener('click', () => close());
    modal.addEventListener('click', event => { if (event.target === modal) close(); });
    document.addEventListener('keydown', function (event) {
        if (modal.hidden) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            event.stopImmediatePropagation();
            close();
        }
        if (event.key === 'Tab') {
            var controls = Array.from(dialog.querySelectorAll('button')).filter(control => control.getClientRects().length);
            var first = controls[0], last = controls[controls.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
    });

    window.MemberSpotDetail = {open: open, connect: connect};
})();
