/* Mock GPS — map UI. Talks to the Android side through window.Android (see MainActivity.Bridge). */
(function () {
  'use strict';

  var native = window.Android || previewBridge();

  // ---------------------------------------------------------------- storage
  var store = {
    get: function (k, d) {
      try { var v = localStorage.getItem('mockgps.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; }
    },
    set: function (k, v) {
      try { localStorage.setItem('mockgps.' + k, JSON.stringify(v)); } catch (e) { /* storage unavailable */ }
    }
  };

  function $(id) { return document.getElementById(id); }

  var ui = {
    top: $('top'), sheet: $('sheet'), q: $('q'), clearQ: $('clearQ'), btnGoogle: $('btnGoogle'), searchForm: $('searchForm'), results: $('results'),
    setup: $('setup'), setupHow: $('setupHow'), openDev: $('openDev'),
    btnFavs: $('btnFavs'), btnLocate: $('btnLocate'),
    status: $('status'), statusText: $('statusText'),
    segButtons: Array.prototype.slice.call(document.querySelectorAll('.seg button')),
    pointPane: $('pointPane'), routePane: $('routePane'),
    latV: $('latV'), lngV: $('lngV'), btnCopy: $('btnCopy'), btnSave: $('btnSave'), pointHint: $('pointHint'),
    wpCount: $('wpCount'), routeDist: $('routeDist'), routeEta: $('routeEta'),
    speedButtons: Array.prototype.slice.call(document.querySelectorAll('#speeds button')), speed: $('speed'),
    loop: $('loop'), drift: $('drift'), btnUndo: $('btnUndo'), btnClear: $('btnClear'),
    progress: $('progress'), routeHint: $('routeHint'),
    btnStart: $('btnStart'), btnMove: $('btnMove'), btnPause: $('btnPause'), btnStop: $('btnStop'),
    modal: $('modal'), modalTitle: $('modalTitle'), modalBody: $('modalBody'), modalClose: $('modalClose'),
    toast: $('toast')
  };

  var state = {
    mode: store.get('mode', 'point'),
    pin: store.get('pin', null),        // {lat, lng, name}
    route: store.get('route', []),      // [[lat, lng], ...]
    speed: store.get('speed', 5),       // km/h
    loop: store.get('loop', false),
    drift: store.get('drift', false),
    favs: store.get('favs', []),        // [{name, lat, lng}]
    nat: { mode: 'idle' },              // latest state from the service
    activePoint: store.get('activePoint', null),
    routeDirty: false,
    mockAppSelected: true,
    pendingLocate: false
  };

  var ACCENT = '#0e7c66';

  // ---------------------------------------------------------------- map
  var view = store.get('view', null);
  var map = L.map('map', {
    zoomControl: false,
    doubleClickZoom: false, // a double tap would otherwise also drop two pins
    worldCopyJump: true
  }).setView(view ? [view.lat, view.lng] : [20, 0], view ? view.zoom : 2);

  map.attributionControl.setPrefix('<a href="https://leafletjs.com">Leaflet</a>');
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);

  map.on('moveend', function () {
    var c = map.getCenter().wrap();
    store.set('view', { lat: c.lat, lng: c.lng, zoom: map.getZoom() });
  });

  map.on('click', function (e) {
    if (!ui.results.hidden || document.activeElement === ui.q || document.activeElement === ui.speed) {
      closeResults();
      document.activeElement.blur();
      return;
    }
    var ll = e.latlng.wrap();
    if (state.mode === 'point') setPin(ll.lat, ll.lng, null);
    else addWaypoint(ll.lat, ll.lng);
  });

  var pinIcon = L.divIcon({
    className: 'pin-icon',
    html: '<svg viewBox="0 0 36 46"><path fill="' + ACCENT + '" d="M18 1C8.6 1 1 8.6 1 18c0 12.8 17 27 17 27s17-14.2 17-27C35 8.6 27.4 1 18 1z"/><circle cx="18" cy="18" r="6.5" fill="#fff"/></svg>',
    iconSize: [36, 46],
    iconAnchor: [18, 45]
  });

  var liveIcon = L.divIcon({
    className: 'live-wrap',
    html: '<div class="live-icon"><div class="halo"></div><div class="arrow"></div><div class="core"></div></div>',
    iconSize: [26, 26],
    iconAnchor: [13, 13]
  });

  function wpIcon(i) {
    return L.divIcon({
      className: 'wp-wrap',
      html: '<div class="wp-icon' + (i === 0 ? ' first' : '') + '">' + (i + 1) + '</div>',
      iconSize: [24, 24],
      iconAnchor: [12, 12]
    });
  }

  var pinMarker = null;
  var liveMarker = null;
  var routeLayer = L.layerGroup().addTo(map);
  var routeLine = null;
  var closeLine = null;

  // ---------------------------------------------------------------- point mode
  function setPin(lat, lng, name) {
    state.pin = { lat: round6(lat), lng: round6(lng), name: name || null };
    store.set('pin', state.pin);
    drawPin();
    render();
  }

  function drawPin() {
    var show = state.mode === 'point' && state.pin;
    if (!show) {
      if (pinMarker) { map.removeLayer(pinMarker); pinMarker = null; }
      return;
    }
    var ll = [state.pin.lat, state.pin.lng];
    if (!pinMarker) {
      pinMarker = L.marker(ll, { icon: pinIcon, draggable: true, autoPan: true, zIndexOffset: 500 }).addTo(map);
      pinMarker.on('dragend', function () {
        var p = pinMarker.getLatLng().wrap();
        setPin(p.lat, p.lng, null);
      });
    } else {
      pinMarker.setLatLng(ll);
    }
  }

  // ---------------------------------------------------------------- route mode
  function addWaypoint(lat, lng) {
    var last = state.route[state.route.length - 1];
    if (last && distance(last[0], last[1], lat, lng) < 1) return;
    state.route.push([round6(lat), round6(lng)]);
    routeChanged();
  }

  function routeChanged() {
    store.set('route', state.route);
    if (state.nat.mode === 'route') state.routeDirty = true;
    drawRoute();
    render();
  }

  function drawRoute() {
    routeLayer.clearLayers();
    routeLine = closeLine = null;
    if (state.mode !== 'route') return;
    var pts = state.route;
    if (pts.length > 1) {
      routeLine = L.polyline(pts, { color: ACCENT, weight: 5, opacity: 0.85, interactive: false }).addTo(routeLayer);
    }
    if (state.loop && pts.length > 1) {
      closeLine = L.polyline([pts[pts.length - 1], pts[0]], {
        color: ACCENT, weight: 4, opacity: 0.55, dashArray: '6 9', interactive: false
      }).addTo(routeLayer);
    }
    pts.forEach(function (p, i) {
      var m = L.marker(p, { icon: wpIcon(i), draggable: true, zIndexOffset: 400 }).addTo(routeLayer);
      m.on('drag', function () {
        var ll = m.getLatLng();
        state.route[i] = [round6(ll.lat), round6(ll.lng)];
        if (routeLine) routeLine.setLatLngs(state.route);
        if (closeLine) closeLine.setLatLngs([state.route[state.route.length - 1], state.route[0]]);
      });
      m.on('dragend', routeChanged);
      m.on('contextmenu', function () { // long-press removes a waypoint
        state.route.splice(i, 1);
        routeChanged();
        toast('Waypoint ' + (i + 1) + ' removed');
      });
    });
  }

  // ---------------------------------------------------------------- live marker
  function drawLive() {
    var n = state.nat;
    if (n.mode === 'idle') {
      if (liveMarker) { map.removeLayer(liveMarker); liveMarker = null; }
      return;
    }
    if (!liveMarker) {
      liveMarker = L.marker([n.lat, n.lng], { icon: liveIcon, interactive: false, keyboard: false, zIndexOffset: 300 }).addTo(map);
    } else {
      liveMarker.setLatLng([n.lat, n.lng]);
    }
    var el = liveMarker.getElement();
    var arrow = el && el.querySelector('.arrow');
    if (arrow) {
      arrow.style.display = n.speed > 0 ? 'block' : 'none';
      arrow.style.transform = 'rotate(' + (n.bearing || 0) + 'deg)';
    }
  }

  // ---------------------------------------------------------------- rendering
  function render() {
    ui.segButtons.forEach(function (b) { b.classList.toggle('on', b.dataset.mode === state.mode); });
    ui.pointPane.hidden = state.mode !== 'point';
    ui.routePane.hidden = state.mode !== 'route';

    // point pane
    var pin = state.pin;
    ui.latV.textContent = pin ? pin.lat.toFixed(6) : '—';
    ui.lngV.textContent = pin ? pin.lng.toFixed(6) : '—';
    ui.btnCopy.disabled = ui.btnSave.disabled = !pin;
    ui.btnSave.classList.toggle('on', !!(pin && findFav(pin.lat, pin.lng) >= 0));
    ui.pointHint.textContent = !pin ? 'Tap the map to drop a pin, or search above.' : (pin.name || 'Drag the pin to fine-tune.');

    // route pane
    var len = routeLength(state.route, state.loop);
    ui.wpCount.textContent = state.route.length;
    ui.routeDist.textContent = fmtDist(len);
    ui.routeEta.textContent = state.route.length > 1 ? fmtDuration(len / (state.speed / 3.6)) : '—';
    ui.speedButtons.forEach(function (b) { b.classList.toggle('on', Number(b.dataset.speed) === state.speed); });
    if (document.activeElement !== ui.speed) ui.speed.value = state.speed;
    ui.loop.checked = state.loop;
    ui.drift.checked = state.drift;
    ui.btnUndo.disabled = ui.btnClear.disabled = state.route.length === 0;
    ui.routeHint.textContent = state.route.length < 2
      ? 'Tap the map to add waypoints (at least two).'
      : (state.routeDirty ? 'Route edited — tap Restart to apply.' : 'Drag waypoints to adjust; long-press to remove.');

    renderStatus();
    renderActions();
    ui.setup.hidden = state.mockAppSelected;
    requestAnimationFrame(measure);
  }

  function renderStatus() {
    var n = state.nat;
    var cls = '';
    var text = 'Not mocking';
    var pct = Math.round((n.progress || 0) * 100);
    if (n.mode === 'point') {
      cls = 'live';
      text = 'Mocking · ' + n.lat.toFixed(5) + ', ' + n.lng.toFixed(5);
    } else if (n.mode === 'route') {
      if (n.finished) { cls = 'paused'; text = 'Arrived · ' + fmtDist(n.routeLength); }
      else if (n.paused) { cls = 'paused'; text = 'Paused · ' + pct + '%'; }
      else { cls = 'live'; text = 'Moving · ' + Math.round(n.speed * 3.6) + ' km/h · ' + pct + '%'; }
    }
    ui.status.className = 'status ' + cls;
    ui.statusText.textContent = text;
    ui.progress.hidden = !(n.mode === 'route' && state.mode === 'route');
    ui.progress.firstElementChild.style.width = pct + '%';
  }

  function renderActions() {
    var n = state.nat;
    var running = n.mode !== 'idle';
    var same = running && n.mode === state.mode;

    ui.btnStart.hidden = same;
    ui.btnStart.textContent = state.mode === 'point'
      ? (running ? 'Mock this point' : 'Start mocking')
      : 'Start route';

    var moved = state.mode === 'point' && state.pin && state.activePoint &&
      distance(state.pin.lat, state.pin.lng, state.activePoint.lat, state.activePoint.lng) > 0.5;
    ui.btnMove.hidden = !(same && (moved || (state.mode === 'route' && state.routeDirty)));
    ui.btnMove.textContent = state.mode === 'point' ? 'Move here' : 'Restart';

    ui.btnPause.hidden = !(same && state.mode === 'route' && !n.finished && !state.routeDirty);
    ui.btnPause.textContent = n.paused ? 'Resume' : 'Pause';

    ui.btnStop.hidden = !running;
    var visible = [ui.btnStart, ui.btnMove, ui.btnPause].filter(function (b) { return !b.hidden; }).length;
    ui.btnStop.classList.toggle('solo', running && visible === 0);
  }

  // Keep floating controls and the map's visual centre clear of the sheet.
  var sheetH = 280, topH = 72;
  function measure() {
    sheetH = ui.sheet.offsetHeight;
    topH = ui.top.offsetHeight + 10;
    document.documentElement.style.setProperty('--sheet-h', sheetH + 'px');
    document.documentElement.style.setProperty('--top-h', topH + 'px');
  }
  if (window.ResizeObserver) new ResizeObserver(measure).observe(ui.sheet);
  window.addEventListener('resize', function () { measure(); map.invalidateSize(); });

  /** Centre a point in the part of the map not covered by the search bar and sheet. */
  function focus(lat, lng, zoom) {
    zoom = zoom == null ? map.getZoom() : zoom;
    var p = map.project([lat, lng], zoom).add([0, (sheetH - topH) / 2]);
    map.setView(map.unproject(p, zoom), zoom, { animate: true });
  }

  function fitRoute() {
    if (state.route.length < 2) {
      if (state.route.length === 1) focus(state.route[0][0], state.route[0][1], Math.max(map.getZoom(), 15));
      return;
    }
    map.fitBounds(L.latLngBounds(state.route), {
      paddingTopLeft: [40, topH + 20],
      paddingBottomRight: [70, sheetH + 20],
      maxZoom: 17
    });
  }

  // ---------------------------------------------------------------- actions
  function start() {
    if (state.mode === 'point') {
      if (!state.pin) { toast('Tap the map to choose a location first.'); return; }
      state.activePoint = { lat: state.pin.lat, lng: state.pin.lng };
      store.set('activePoint', state.activePoint);
      native.startPoint(state.pin.lat, state.pin.lng, state.drift);
    } else {
      if (state.route.length < 2) { toast('Add at least two waypoints.'); return; }
      state.routeDirty = false;
      native.startRoute(JSON.stringify(state.route), state.speed, state.loop, state.drift);
    }
    render();
  }

  ui.btnStart.addEventListener('click', start);
  ui.btnMove.addEventListener('click', start);
  ui.btnPause.addEventListener('click', function () { native.togglePause(); });
  ui.btnStop.addEventListener('click', function () { native.stop(); });

  ui.segButtons.forEach(function (b) {
    b.addEventListener('click', function () {
      if (state.mode === b.dataset.mode) return;
      state.mode = b.dataset.mode;
      store.set('mode', state.mode);
      drawPin();
      drawRoute();
      render();
      if (state.mode === 'route' && state.route.length) fitRoute();
      else if (state.mode === 'point' && state.pin) focus(state.pin.lat, state.pin.lng);
    });
  });

  ui.btnCopy.addEventListener('click', function () {
    if (!state.pin) return;
    var text = state.pin.lat.toFixed(6) + ', ' + state.pin.lng.toFixed(6);
    native.copyText(text);
  });

  ui.btnSave.addEventListener('click', function () {
    if (!state.pin) return;
    var idx = findFav(state.pin.lat, state.pin.lng);
    if (idx >= 0) {
      var removed = state.favs.splice(idx, 1)[0];
      store.set('favs', state.favs);
      toast('Removed “' + removed.name + '”');
      render();
      return;
    }
    askName(state.pin.name || ('Place ' + (state.favs.length + 1)), function (name) {
      state.favs.unshift({ name: name, lat: state.pin.lat, lng: state.pin.lng });
      store.set('favs', state.favs);
      toast('Saved “' + name + '”');
      render();
    });
  });

  ui.speedButtons.forEach(function (b) {
    b.addEventListener('click', function () { setSpeed(Number(b.dataset.speed)); });
  });
  ui.speed.addEventListener('change', function () { setSpeed(Number(ui.speed.value)); });
  ui.speed.addEventListener('keydown', function (e) { if (e.key === 'Enter') ui.speed.blur(); });

  function setSpeed(v) {
    if (!isFinite(v) || v <= 0) v = state.speed;
    state.speed = Math.min(400, Math.max(1, Math.round(v * 10) / 10));
    store.set('speed', state.speed);
    if (state.nat.mode === 'route') state.routeDirty = true;
    render();
  }

  ui.loop.addEventListener('change', function () {
    state.loop = ui.loop.checked;
    store.set('loop', state.loop);
    if (state.nat.mode === 'route') state.routeDirty = true;
    drawRoute();
    render();
  });

  ui.drift.addEventListener('change', function () {
    state.drift = ui.drift.checked;
    store.set('drift', state.drift);
    if (state.nat.mode !== 'idle') toast('Applies the next time you start.');
  });

  ui.btnUndo.addEventListener('click', function () {
    if (!state.route.length) return;
    state.route.pop();
    routeChanged();
  });

  ui.btnClear.addEventListener('click', function () {
    if (!state.route.length) return;
    var backup = state.route.slice();
    state.route = [];
    routeChanged();
    toast('Route cleared', 'Undo', function () { state.route = backup; routeChanged(); });
  });

  ui.btnFavs.addEventListener('click', openFavs);
  ui.btnLocate.addEventListener('click', locate);
  ui.setupHow.addEventListener('click', openSetupHelp);
  ui.openDev.addEventListener('click', function () { native.openDeveloperOptions(); });

  function locate() {
    var r;
    try { r = JSON.parse(native.getLastKnownLocation()); } catch (e) { r = { error: 'unavailable' }; }
    if (r.error === 'permission') {
      state.pendingLocate = true;
      native.requestLocationPermission();
      return;
    }
    if (r.error) { toast('No location fix yet. Try again in a moment.'); return; }
    focus(r.lat, r.lng, Math.max(map.getZoom(), 16));
  }

  // ---------------------------------------------------------------- search
  var searchController = null;

  ui.q.addEventListener('input', function () {
    ui.clearQ.hidden = !ui.q.value;
    if (!ui.q.value) closeResults();
  });
  ui.clearQ.addEventListener('click', function () {
    ui.q.value = '';
    ui.clearQ.hidden = true;
    closeResults();
    ui.q.focus();
  });
  ui.searchForm.addEventListener('submit', function (e) {
    e.preventDefault();
    search(ui.q.value.trim());
  });

  var MAP_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4 3 6.5v13L9 17l6 2.5 6-2.5v-13L15 6.5z"/><path d="M9 4v13M15 6.5v13"/></svg>';

  function search(q) {
    if (!q) { openGoogle(''); return; }
    var c = parseCoords(q);
    if (c) {
      ui.q.blur();
      closeResults();
      choosePlace(c.lat, c.lng, null);
      return;
    }
    if (searchController) searchController.abort();
    searchController = window.AbortController ? new AbortController() : null;
    renderResults(q, [], 'Searching…');
    var url = 'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q=' + encodeURIComponent(q);
    fetch(url, searchController ? { signal: searchController.signal } : {})
      .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
      .then(function (items) {
        ui.q.blur();
        renderResults(q, items, items.length ? null : 'No matches here — try Google Maps above.');
      })
      .catch(function (err) {
        if (err && err.name === 'AbortError') return;
        renderResults(q, [], 'Couldn’t search OpenStreetMap. Google Maps above still works.');
      });
  }

  /** Google Maps first (best for shops, restaurants, landmarks), then OpenStreetMap matches. */
  function renderResults(q, items, msg) {
    ui.results.innerHTML = '';
    var g = document.createElement('li');
    g.className = 'r-google';
    g.innerHTML = MAP_ICON + '<div><span class="r-name"></span><span class="r-sub">Shops, restaurants, landmarks and more</span></div>';
    g.querySelector('.r-name').textContent = 'Search Google Maps for “' + q + '”';
    g.addEventListener('click', function () { openGoogle(q); });
    ui.results.appendChild(g);

    var head = document.createElement('li');
    head.className = 'r-head';
    head.textContent = 'OpenStreetMap';
    ui.results.appendChild(head);

    if (msg) {
      var m = document.createElement('li');
      m.className = 'r-empty';
      m.textContent = msg;
      ui.results.appendChild(m);
    }
    items.forEach(function (it) {
      var li = document.createElement('li');
      var name = it.name || String(it.display_name).split(',')[0];
      var nameEl = document.createElement('span');
      nameEl.className = 'r-name';
      nameEl.textContent = name;
      var sub = document.createElement('span');
      sub.className = 'r-sub';
      sub.textContent = it.display_name;
      li.appendChild(nameEl);
      li.appendChild(sub);
      li.addEventListener('click', function () {
        closeResults();
        choosePlace(Number(it.lat), Number(it.lon), name);
      });
      ui.results.appendChild(li);
    });
    ui.results.hidden = false;
  }

  /** Opens the Google Maps picker screen, searching near the current map view. */
  function openGoogle(q) {
    closeResults();
    ui.q.blur();
    var c = map.getCenter().wrap();
    native.openGoogleMaps(q || '', c.lat, c.lng, map.getZoom());
  }

  ui.btnGoogle.addEventListener('click', function () { openGoogle(ui.q.value.trim()); });

  function closeResults() {
    ui.results.hidden = true;
  }

  function choosePlace(lat, lng, name) {
    if (state.mode === 'point') {
      setPin(lat, lng, name);
      focus(lat, lng, Math.max(map.getZoom(), 16));
    } else {
      addWaypoint(lat, lng);
      if (state.route.length > 1) fitRoute();
      else focus(lat, lng, Math.max(map.getZoom(), 15));
      if (name) toast('Added “' + name + '”');
    }
  }

  /** Accepts "1.2834, 103.8607", "1.2834 103.8607" or a maps URL containing "@lat,lng". */
  function parseCoords(s) {
    var plain = /^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/.exec(s);
    var embedded = /(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)/.exec(s);
    var m = plain || embedded;
    if (!m) return null;
    var lat = Number(m[1]);
    var lng = Number(m[2]);
    if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
    return { lat: lat, lng: lng };
  }

  // ---------------------------------------------------------------- modals
  var modalOnClose = null;

  function openModal(title, build, onClose) {
    ui.modalTitle.textContent = title;
    ui.modalBody.innerHTML = '';
    build(ui.modalBody);
    modalOnClose = onClose || null;
    ui.modal.hidden = false;
  }

  function closeModal() {
    if (ui.modal.hidden) return;
    ui.modal.hidden = true;
    ui.modalBody.innerHTML = '';
    var cb = modalOnClose;
    modalOnClose = null;
    if (cb) cb();
  }

  ui.modalClose.addEventListener('click', closeModal);
  ui.modal.addEventListener('click', function (e) { if (e.target === ui.modal) closeModal(); });

  function openFavs() {
    openModal('Saved places', function (body) {
      if (!state.favs.length) {
        var empty = document.createElement('div');
        empty.className = 'empty';
        empty.textContent = 'No saved places yet. Drop a pin and tap the star to save it.';
        body.appendChild(empty);
        return;
      }
      var hint = document.createElement('p');
      hint.textContent = state.mode === 'route' ? 'Tap a place to add it as a waypoint.' : 'Tap a place to move the pin there.';
      body.appendChild(hint);
      var ul = document.createElement('ul');
      ul.className = 'fav-list';
      state.favs.forEach(function (f, i) {
        var li = document.createElement('li');
        var main = document.createElement('button');
        main.type = 'button';
        main.className = 'fav-main';
        var b = document.createElement('b');
        b.textContent = f.name;
        var s = document.createElement('span');
        s.textContent = f.lat.toFixed(6) + ', ' + f.lng.toFixed(6);
        main.appendChild(b);
        main.appendChild(s);
        main.addEventListener('click', function () {
          closeModal();
          choosePlace(f.lat, f.lng, f.name);
        });
        var del = document.createElement('button');
        del.type = 'button';
        del.className = 'icon-btn';
        del.setAttribute('aria-label', 'Delete ' + f.name);
        del.innerHTML = '<svg viewBox="0 0 24 24"><path d="M5 7h14M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/></svg>';
        del.addEventListener('click', function () {
          state.favs.splice(i, 1);
          store.set('favs', state.favs);
          render();
          openFavs();
        });
        li.appendChild(main);
        li.appendChild(del);
        ul.appendChild(li);
      });
      body.appendChild(ul);
    });
  }

  function askName(initial, onSave) {
    openModal('Save place', function (body) {
      var input = document.createElement('input');
      input.className = 'field';
      input.type = 'text';
      input.maxLength = 60;
      input.value = initial;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn primary';
      btn.style.width = '100%';
      btn.textContent = 'Save';
      function save() {
        var name = input.value.trim() || initial;
        closeModal();
        onSave(name);
      }
      btn.addEventListener('click', save);
      input.addEventListener('keydown', function (e) { if (e.key === 'Enter') save(); });
      body.appendChild(input);
      body.appendChild(btn);
      setTimeout(function () { input.focus(); input.select(); }, 60);
    });
  }

  function openSetupHelp() {
    openModal('Allow Mock GPS to set your location', function (body) {
      body.innerHTML =
        '<p>Android only lets one app, chosen in Developer options, provide fake locations. You do this once.</p>' +
        '<ol>' +
        '<li>Open <b>Settings → About phone</b> and tap <b>Build number</b> seven times to unlock Developer options. ' +
        '(On some phones it is under <b>Software information</b>.)</li>' +
        '<li>Go to <b>Settings → System → Developer options</b>.</li>' +
        '<li>Tap <b>Select mock location app</b> and choose <b>Mock GPS</b>.</li>' +
        '<li>Come back here and tap <b>Start</b>.</li>' +
        '</ol>';
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn primary';
      btn.style.width = '100%';
      btn.textContent = 'Open Developer options';
      btn.addEventListener('click', function () { native.openDeveloperOptions(); });
      body.appendChild(btn);
    });
  }

  // ---------------------------------------------------------------- toast
  var toastTimer = null;
  function toast(msg, actionLabel, action) {
    ui.toast.textContent = msg;
    if (actionLabel) {
      var a = document.createElement('button');
      a.type = 'button';
      a.textContent = actionLabel;
      a.style.cssText = 'margin-left:12px;border:0;background:none;color:#7fe0c6;font-weight:700;padding:0;pointer-events:auto';
      a.addEventListener('click', function () { ui.toast.classList.remove('show'); action(); });
      ui.toast.appendChild(a);
    }
    ui.toast.style.pointerEvents = actionLabel ? 'auto' : 'none';
    ui.toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { ui.toast.classList.remove('show'); ui.toast.style.pointerEvents = 'none'; }, actionLabel ? 4500 : 2600);
  }

  // ---------------------------------------------------------------- native callbacks
  window.onNativeState = function (s) {
    var wasIdle = state.nat.mode === 'idle';
    state.nat = s;
    if (s.mode === 'idle') state.routeDirty = false;
    if (s.error === 'not_mock_app') {
      state.mockAppSelected = false;
      openSetupHelp();
    } else if (s.error === 'start_failed') {
      toast('Couldn’t start. Make sure location permission is allowed.');
    } else if (s.mode !== 'idle') {
      state.mockAppSelected = true;
      if (wasIdle) toast(s.mode === 'route' ? 'Route started' : 'Location mocked');
    }
    drawLive();
    render();
  };

  window.onNativeResume = function (info) {
    state.mockAppSelected = !!info.mockAppSelected;
    window.onNativeState(info.state);
  };

  window.onPlacePicked = function (p) {
    if (!p || !isFinite(p.lat) || !isFinite(p.lng)) return;
    choosePlace(p.lat, p.lng, p.name || null);
    if (!p.precise) toast('Used the map centre — drag the pin to fine-tune.');
    else if (state.mode === 'point') toast('Pin dropped' + (p.name ? ' at ' + p.name : ''));
  };

  window.onPermissionResult = function (r) {
    if (!r.granted) {
      toast(r.forStart
        ? 'Location permission is required to run the mock service.'
        : 'Allow location access to jump to where you are.');
      state.pendingLocate = false;
      return;
    }
    if (!r.forStart && state.pendingLocate) {
      state.pendingLocate = false;
      locate();
    }
  };

  /** Returns true if the back press was handled here. */
  window.handleBack = function () {
    if (!ui.modal.hidden) { closeModal(); return true; }
    if (!ui.results.hidden) { closeResults(); return true; }
    if (document.activeElement && document.activeElement.tagName === 'INPUT') { document.activeElement.blur(); return true; }
    return false;
  };

  // ---------------------------------------------------------------- helpers
  function findFav(lat, lng) {
    for (var i = 0; i < state.favs.length; i++) {
      if (Math.abs(state.favs[i].lat - lat) < 1e-6 && Math.abs(state.favs[i].lng - lng) < 1e-6) return i;
    }
    return -1;
  }

  function round6(x) { return Math.round(x * 1e6) / 1e6; }

  function distance(lat1, lng1, lat2, lng2) {
    var R = 6371008.8, r = Math.PI / 180;
    var dp = (lat2 - lat1) * r, dl = (lng2 - lng1) * r;
    var a = Math.sin(dp / 2) * Math.sin(dp / 2) +
      Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function routeLength(pts, loop) {
    var d = 0;
    for (var i = 1; i < pts.length; i++) d += distance(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]);
    if (loop && pts.length > 1) d += distance(pts[pts.length - 1][0], pts[pts.length - 1][1], pts[0][0], pts[0][1]);
    return d;
  }

  function fmtDist(m) {
    if (m < 1000) return Math.round(m) + ' m';
    return (m / 1000).toFixed(m < 10000 ? 2 : 1) + ' km';
  }

  function fmtDuration(s) {
    if (!isFinite(s)) return '—';
    if (s < 60) return Math.max(1, Math.round(s)) + ' s';
    if (s < 3600) return Math.round(s / 60) + ' min';
    var h = Math.floor(s / 3600);
    return h + ' h ' + Math.round((s - h * 3600) / 60) + ' min';
  }

  /** Stand-in for the Android bridge so the page can be opened in a desktop browser. */
  function previewBridge() {
    var s = { mode: 'idle', paused: false, finished: false, lat: 0, lng: 0, bearing: 0, speed: 0, progress: 0, routeLength: 0, error: null };
    var selected = !/[?&]setup\b/.test(location.search);
    function emit() { setTimeout(function () { window.onNativeState(JSON.parse(JSON.stringify(s))); }, 30); }
    return {
      ready: function () {
        setTimeout(function () { window.onNativeResume({ mockAppSelected: selected, hasLocationPermission: true, state: s }); }, 30);
      },
      startPoint: function (lat, lng) { s.mode = 'point'; s.lat = lat; s.lng = lng; s.speed = 0; emit(); },
      startRoute: function (json, kmh, loop) {
        var p = JSON.parse(json);
        s.mode = 'route'; s.paused = false; s.finished = false; s.speed = kmh / 3.6; s.progress = 0.35; s.bearing = 40;
        s.lat = p[0][0] + (p[1][0] - p[0][0]) * 0.6; s.lng = p[0][1] + (p[1][1] - p[0][1]) * 0.6;
        s.routeLength = routeLength(p, loop);
        emit();
      },
      togglePause: function () { s.paused = !s.paused; s.speed = s.paused ? 0 : s.speed; emit(); },
      stop: function () { s.mode = 'idle'; emit(); },
      openDeveloperOptions: function () { toast('(Would open Developer options)'); },
      getLastKnownLocation: function () { return JSON.stringify({ lat: 1.2834, lng: 103.8607, accuracy: 8 }); },
      requestLocationPermission: function () {},
      copyText: function () { toast('Copied'); },
      openUrl: function (u) { window.open(u, '_blank'); },
      openGoogleMaps: function () {
        setTimeout(function () { window.onPlacePicked({ lat: 1.304052, lng: 103.831767, name: 'ION Orchard', precise: true }); }, 300);
      }
    };
  }

  // ---------------------------------------------------------------- boot
  drawPin();
  drawRoute();
  render();
  measure();
  native.ready();
})();
