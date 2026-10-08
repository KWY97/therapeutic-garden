/* ================================
   Site 데이터
================================ */

var siteSelect =
    document.getElementById(
        'siteSelect'
    );


/*
 * 현재 선택된 Site
 */
var selectedSite =
    siteSelect.options[
        siteSelect.selectedIndex
        ];


// Site가 없거나 SDK 로딩에 실패해도 정보 패널은 사용할 수 있다.
var map = null;
var survey = window.HomeSurvey;
var monitoringEffects = window.monitoringEffects || {};
var monitoringMaximums = window.monitoringMaximums || {};
var selectedSpot = null;
var loadVersion = 0;
function validNumber(value) {
    return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value));
}
function validPosition(latitude, longitude) {
    return validNumber(latitude) && validNumber(longitude)
        && Math.abs(Number(latitude)) <= 90 && Math.abs(Number(longitude)) <= 180;
}
function moveSiteMap(site) {
    if (mapModal.hidden) return;
    var container = document.getElementById('map');
    if (!site || !validPosition(site.dataset.latitude, site.dataset.longitude)) {
        container.hidden = true;
        document.getElementById('mapSelectionStatus').textContent = 'Site의 지도 좌표가 설정되지 않았습니다.';
        return;
    }
    container.hidden = false;
    if (!window.kakao || !window.kakao.maps) {
        document.getElementById('map').textContent = '지도를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.';
        return;
    }
    var center = new kakao.maps.LatLng(Number(site.dataset.latitude), Number(site.dataset.longitude));
    var level = validNumber(site.dataset.mapLevel) ? Number(site.dataset.mapLevel) : 3;
    if (!map) map = new kakao.maps.Map(document.getElementById('map'), {center: center, level: level});
    else { map.relayout(); map.setCenter(center); map.setLevel(level); }
}

/* ================================
   Healing Space 데이터

   HC / HS 데이터는 더 이상
   JavaScript에 직접 작성하지 않는다.

   선택된 Site의 ID를 이용해서
   Spring Boot API로부터 조회한다.
================================ */

var healingCourses = [];
var healingSpots = [];


/*
 * 지도에 생성된 HC Circle과
 * HS Marker를 보관한다.
 *
 * Site를 변경했을 때 기존 객체를
 * 지도에서 제거하기 위해 필요하다.
 */
var courseCircles = [];
var spotMarkers = [];


/* ================================
   왼쪽 정보 패널 요소
================================ */

/*
 * Site 정보
 */
var siteName =
    document.getElementById(
        'siteName'
    );


var siteAddress =
    document.getElementById(
        'siteAddress'
    );


/*
 * Healing Spot 정보
 */
var spotId =
    document.getElementById(
        'spotId'
    );


var spotName =
    document.getElementById(
        'spotName'
    );


var spotCourse =
    document.getElementById(
        'spotCourse'
    );


var spotImage =
    document.getElementById(
        'spotImage'
    );


/* ================================
   Site 기본 정보 표시
================================ */

function showSiteInformation(siteOption) {

    siteName.textContent = siteOption ? siteOption.dataset.name : '등록된 Site가 없습니다.';
    siteAddress.textContent = siteOption ? siteOption.dataset.address : '';
    setImage(document.getElementById('siteImage'), document.getElementById('siteImageEmpty'),
        getSiteImage(siteOption), siteOption ? siteOption.dataset.name : 'Site');

}


/* ================================
   기존 지도 객체 제거

   Site를 변경할 때
   이전 Site의 HC / HS가 지도에
   남아있지 않도록 제거한다.
================================ */

function clearHealingSpaceMap() {


    /*
     * 기존 HealingCourse Circle 제거
     */
    courseCircles.forEach(
        function(circle) {

            circle.setMap(null);

        }
    );


    /*
     * 기존 HealingSpot Marker 제거
     */
    spotMarkers.forEach(
        function(marker) {

            marker.setMap(null);

        }
    );


    /*
     * 보관 배열도 비운다.
     */
    courseCircles = [];
    spotMarkers = [];

}


/* ================================
   Healing Course 영역 표시
================================ */

function drawHealingCourses() {


    healingCourses.forEach(
        function(course) {


            if (!map || !validPosition(course.centerLatitude, course.centerLongitude)
                || !validNumber(course.radius) || Number(course.radius) <= 0) return;
            var center = new kakao.maps.LatLng(course.centerLatitude, course.centerLongitude);
            var courseColor = courseAnalysisColor(course);

            var circle =
                new kakao.maps.Circle({

                    center: center,

                    radius: course.radius,

                    strokeWeight: 0,

                    fillColor: courseColor,
                    fillOpacity: 0.22

                });


            circle.surveyCourse = course;
            circle.setMap(map);


            /*
             * 나중에 Site 변경 시
             * 제거할 수 있도록 저장
             */
            courseCircles.push(
                circle
            );

        }
    );

}


/* ================================
   HS 상세 정보 표시
================================ */

var spotModal = document.getElementById('spotDetailModal');
var spotDialog = spotModal.querySelector('[role="dialog"]');
var spotPreviousFocus = null;
var spotPreviousOverflow = '';
var spotRequestVersion = 0;
function renderSpotInformation(spot) {
    spotId.textContent = spot.code;
    spotName.textContent = spot.name;
    spotCourse.textContent = spot.course || [spot.courseCode, spot.courseName].filter(Boolean).join(' · ');
    document.getElementById('spotDetailTitle').textContent = [spot.code, spot.name].filter(Boolean).join(' · ');
    document.getElementById('spotDetailCourse').textContent = spotCourse.textContent;
    document.getElementById('spotSiteName').textContent = spot.siteName ?? selectedSite.dataset.name;
    document.getElementById('spotSiteAddress').textContent = spot.siteAddress ?? selectedSite.dataset.address;
}
function showSpotGalleryImage(url, alt, message) {
    var empty = document.getElementById('spotImageEmpty');
    spotImage.hidden = true;
    empty.hidden = false;
    empty.textContent = message;
    spotImage.onload = function() { spotImage.hidden = false; empty.hidden = true; };
    spotImage.onerror = function() {
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
    // A missing gallery field is an invalid/old API response, never an empty gallery.
    if (!Array.isArray(spot.images) || spot.images.some(image => !image || typeof image.readUrl !== 'string' || !image.readUrl.trim())
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
async function openSpotDetail(spot) {
    if (!selectedSite) return;
    var version = ++spotRequestVersion;
    var siteId = selectedSite.value;
    if (spotModal.hidden) {
        spotPreviousFocus = !mapModal.hidden ? document.getElementById('closeMapButton') : document.activeElement;
        spotPreviousOverflow = document.body.style.overflow;
    }
    selectedSpot = spot;
    healingSpotSelect.value = String(spot.spotId);
    openSpotDetailButton.disabled = false;
    spatial.select(spot.spotId);
    renderSpotInformation(spot);
    resetSpotGallery(spot, '이미지를 불러오는 중입니다.', spot.representativeImageUrl);
    spotModal.hidden = false;
    mapDialog.inert = !mapModal.hidden;
    if (!mapModal.hidden) mapDialog.setAttribute('aria-modal', 'false');
    document.querySelector('.home-map-container').inert = true;
    document.body.style.overflow = 'hidden';
    spotDialog.scrollTop = 0;
    document.getElementById('closeSpotDetailButton').focus();
    updateAnalysis();
    var status = document.getElementById('spotGalleryStatus');
    status.textContent = '이미지를 불러오는 중입니다.';
    try {
        var response = await fetch('/api/sites/' + encodeURIComponent(siteId) + '/spots/' + encodeURIComponent(spot.spotId), {cache: 'no-store'});
        if (!response.ok) throw new Error('HS 조회 실패');
        var fresh = await response.json();
        if (version !== spotRequestVersion || spotModal.hidden) return;
        if (!fresh || Array.isArray(fresh) || String(fresh.spotId) !== String(spot.spotId)
                || String(fresh.siteId) !== String(siteId)) throw new Error('잘못된 HS 응답');
        selectedSpot = fresh;
        renderSpotInformation(fresh);
        renderSpotGallery(fresh);
        status.textContent = '';
        updateAnalysis();
    } catch (error) {
        if (version !== spotRequestVersion || spotModal.hidden) return;
        resetSpotGallery(spot, '이미지를 불러오지 못했습니다.');
        status.textContent = '이미지를 불러오지 못했습니다. 닫은 뒤 다시 열어 주세요.';
    }
}
function closeSpotDetail(restoreFocus = true) {
    ++spotRequestVersion;
    if (spotModal.hidden) return;
    spotModal.hidden = true;
    mapDialog.inert = false;
    mapDialog.setAttribute('aria-modal', 'true');
    document.querySelector('.home-map-container').inert = false;
    document.body.style.overflow = spotPreviousOverflow;
    selectedSpot = null;
    spatial.select(null);
    if (restoreFocus && spotPreviousFocus) spotPreviousFocus.focus();
}
document.getElementById('closeSpotDetailButton').addEventListener('click', () => closeSpotDetail());
spotModal.addEventListener('click', event => { if (event.target === spotModal) closeSpotDetail(); });
document.addEventListener('keydown', function(event) {
    if (spotModal.hidden) return;
    if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        closeSpotDetail();
    }
    if (event.key === 'Tab') {
        var controls = Array.from(spotDialog.querySelectorAll('button')).filter(control => control.getClientRects().length);
        var first = controls[0], last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
});

/* ================================
   Healing Spot 마커 표시
================================ */

function drawHealingSpots() {


    healingSpots.forEach(
        function(spot) {


            /*
             * DB에서 받은 HS 좌표
             */
            if (!map || !validPosition(spot.latitude, spot.longitude)) return;
            var position =
                new kakao.maps.LatLng(
                    spot.latitude,
                    spot.longitude
                );


            var marker =
                new kakao.maps.Marker({

                    position: position

                });


            marker.setMap(map);


            /*
             * 나중에 Site 변경 시
             * 제거할 수 있도록 저장
             */
            spotMarkers.push(
                marker
            );


            /*
             * HS 마커 클릭
             */
            kakao.maps.event.addListener(
                marker,
                'click',
                function() {

                    openSpotDetail(spot);
                    document.getElementById('mapSelectionStatus').textContent = spot.code + ' · ' + spot.name
                        + ' 상세 보기';

                }
            );

        }
    );

}


/* ================================
   Healing Space DB 데이터 조회
================================ */

async function loadHealingSpace(siteId) {
    var version = ++loadVersion;
    clearHealingSpaceMap();
    healingCourses = [];
    healingSpots = [];
    resetHealingSpotSelect();
    var status = document.getElementById('spaceStatus');
    status.textContent = '공간 정보를 불러오는 중입니다.';
    try {
        var responses = await Promise.all([
            fetch('/api/sites/' + encodeURIComponent(siteId) + '/courses'),
            fetch('/api/sites/' + encodeURIComponent(siteId) + '/spots')
        ]);
        if (responses.some(response => !response.ok)) throw new Error('공간 조회 실패');
        var data = await Promise.all(responses.map(response => response.json()));
        if (version !== loadVersion) return;
        if (!data.every(Array.isArray)) throw new Error('잘못된 공간 응답');
        healingCourses = data[0].filter(Boolean);
        healingSpots = data[1].filter(Boolean);
        populateHealingSpotSelect(healingSpots);
        drawHealingCourses();
        drawHealingSpots();
        status.textContent = [
            !healingCourses.length ? '등록된 Healing Course가 없습니다.' : '',
            !healingSpots.length ? '등록된 Healing Spot이 없습니다.' : '',
            map && courseCircles.length < healingCourses.length ? '좌표 또는 반경이 없는 Course는 지도에서 제외됩니다.' : '',
            map && spotMarkers.length < healingSpots.length ? '좌표가 없는 Spot은 지도에서 제외됩니다.' : ''
        ].filter(Boolean).join(' ');
        updateAnalysis();
    } catch (error) {
        if (version !== loadVersion) return;
        clearHealingSpaceMap();
        healingCourses = [];
        healingSpots = [];
        status.textContent = '공간 정보를 불러오지 못했습니다. Site를 다시 선택하거나 새로고침해 주세요.';
    }
}

function getSiteImage(site) {
    return site ? site.dataset.representativeImageUrl || null : null;
}
function setImage(image, empty, path, alt) {
    image.hidden = true;
    empty.hidden = false;
    empty.textContent = alt + ' · 등록된 이미지가 없습니다.';
    image.onload = function() { image.hidden = false; empty.hidden = true; };
    image.onerror = function() { image.hidden = true; empty.hidden = false; };
    image.alt = alt;
    image.referrerPolicy = 'no-referrer';
    if (path) image.src = path;
    else image.removeAttribute('src');
}
function courseAnalysisColor(course) {
    return '#749d80';
}
function currentEffects() {
    return selectedSite ? survey.selectEffects(monitoringEffects, selectedSite.value) : [];
}
function currentMaximums() {
    return selectedSite ? survey.selectMaximums(monitoringMaximums, selectedSite.value)
        : {stressSpotCodes: [], emotionalSpotCodes: []};
}
function updateAnalysis() {
    var effects = currentEffects();
    spatial.setAnalysis(effects, currentMaximums());
    courseCircles.forEach(circle => circle.setOptions({fillColor: courseAnalysisColor(circle.surveyCourse)}));
    document.getElementById('surveyLegendTitle').textContent = 'Healing Spot 개선';
    document.getElementById('surveyLegendRange').textContent = '전체 참가자 평균';
    document.getElementById('surveyLegendContext').textContent = 'Course 단위 수치는 계산하지 않으며 Spot별 Summary만 표시합니다.';
    if (selectedSpot) {
        var effect = survey.indexByCode(effects).get(selectedSpot.code);
        survey.renderSpot(document.getElementById('spotAnalysis'), {
            participant: 'all', participantLabel: '전체 참가자 평균', effect: effect
        });
    }
}
var healingSpotSelect = document.getElementById('healingSpotSelect');
var openSpotDetailButton = document.getElementById('openSpotDetailButton');
function resetHealingSpotSelect() {
    var placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = 'HS 선택';
    healingSpotSelect.replaceChildren(placeholder);
    healingSpotSelect.value = '';
    openSpotDetailButton.disabled = true;
}
function populateHealingSpotSelect(spots) {
    resetHealingSpotSelect();
    spots.slice().sort((a, b) => String(a.code).localeCompare(String(b.code), undefined, {numeric: true})).forEach(spot => {
        var option = document.createElement('option');
        option.value = String(spot.spotId);
        option.textContent = [spot.code, spot.name].filter(Boolean).join(' · ');
        healingSpotSelect.append(option);
    });
}
healingSpotSelect.addEventListener('change', function() {
    openSpotDetailButton.disabled = !this.value;
});
openSpotDetailButton.addEventListener('click', function() {
    var spot = healingSpots.find(item => String(item.spotId) === healingSpotSelect.value);
    if (spot) openSpotDetail(spot);
});
siteSelect.addEventListener('change', function() {
    selectedSite = siteSelect.options[siteSelect.selectedIndex];
    closeSpotDetail(false);
    resetHealingSpotSelect();
    showSiteInformation(selectedSite);
    spatial.load(selectedSite);
    updateAnalysis();
    document.getElementById('mapModalTitle').textContent = selectedSite ? selectedSite.dataset.name + ' · 지도' : '지도';
    document.getElementById('mapSelectionStatus').textContent = '';
    moveSiteMap(selectedSite);
    if (selectedSite) loadHealingSpace(selectedSite.value);
});
var spatial = window.HomeSpatial(openSpotDetail);
var mapModal = document.getElementById('mapModal');
var mapDialog = mapModal.querySelector('[role="dialog"]');
var mapPreviousFocus = null;
var mapPreviousOverflow = '';
function closeMapModal() {
    if (mapModal.hidden) return;
    mapModal.hidden = true;
    document.body.style.overflow = mapPreviousOverflow;
    if (mapPreviousFocus) mapPreviousFocus.focus();
}
function resizeMap() {
    if (mapModal.hidden || !map) return;
    var center = map.getCenter();
    map.relayout();
    map.setCenter(center);
}
document.getElementById('openMapButton').addEventListener('click', function() {
    mapPreviousFocus = document.activeElement;
    mapPreviousOverflow = document.body.style.overflow;
    document.getElementById('mapModalTitle').textContent = selectedSite ? selectedSite.dataset.name + ' · 지도' : '지도';
    document.getElementById('mapSelectionStatus').textContent = '';
    mapModal.hidden = false;
    document.body.style.overflow = 'hidden';
    document.getElementById('closeMapButton').focus();
    // Wait for the visible dialog's layout before creating or relaying out the SDK map.
    window.requestAnimationFrame(function() {
        if (mapModal.hidden) return;
        moveSiteMap(selectedSite);
        clearHealingSpaceMap();
        drawHealingCourses();
        drawHealingSpots();
        updateAnalysis();
    });
});
document.getElementById('closeMapButton').addEventListener('click', closeMapModal);
mapModal.addEventListener('click', event => { if (event.target === mapModal) closeMapModal(); });
document.addEventListener('keydown', function(event) {
    if (mapModal.hidden || !spotModal.hidden || event.defaultPrevented) return;
    if (event.key === 'Escape') { event.preventDefault(); closeMapModal(); }
    if (event.key === 'Tab') {
        var controls = Array.from(mapDialog.querySelectorAll('button, a[href], input, select, [tabindex]'))
            .filter(control => control.tabIndex >= 0 && control.getClientRects().length);
        var first = controls[0], last = controls[controls.length - 1];
        if (!first) { event.preventDefault(); mapDialog.focus(); }
        else if (event.shiftKey && (document.activeElement === first || document.activeElement === mapDialog)) {
            event.preventDefault(); last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault(); first.focus();
        }
    }
});
window.addEventListener('resize', resizeMap);
if (window.ResizeObserver) new window.ResizeObserver(resizeMap).observe(document.getElementById('map'));
showSiteInformation(selectedSite);
spatial.load(selectedSite);
updateAnalysis();
if (selectedSite) {
    loadHealingSpace(selectedSite.value);
} else {
    siteSelect.disabled = true;
    document.getElementById('openMapButton').disabled = true;
    document.getElementById('map').textContent = '등록된 Site가 없습니다.';
}
