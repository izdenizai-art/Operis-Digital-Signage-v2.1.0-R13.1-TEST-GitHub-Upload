const resolveSchedule = (...args) => window.OperisScheduleEngine.resolveSchedule(...args);
const playlistItemAt = (...args) => window.OperisScheduleEngine.playlistItemAt(...args);

const VERSION = 'android-2.1.0-r13.1-test';
const VERSION_NAME = '2.1.0-r13.1-test';
const VERSION_CODE = 20103;
const MAX_UNAUTHORIZED = 3;
const AUTH_RETRY_MS = 10000;
const HEARTBEAT_MS = 15000;
const STATE_REFRESH_MS = 5000;
const TR_OFFSET_MS = 3 * 60 * 60 * 1000;
const OPERIS_TRUSTED_TIME_ANCHOR = 'operis_trusted_time_anchor_v2';
const OPERIS_PUBLICATION_KEY = 'operis_last_publication_v1';
const OPERIS_RUNTIME_KEY = 'operis_schedule_runtime_v1';
const PENDING_REBOOT_KEY = 'operis_pending_reboot_command_v1';
const MODES = new Set(['PASSENGER','ADVERTISING','MIXED','PLAYLIST']);

const $ = id => document.getElementById(id);
const setup = $('setup');
const player = $('player');
const serverInput = $('server');
const codeInput = $('code');
const setupStatus = $('setupStatus');
const baseCanvas = $('baseCanvas');
const scheduledCanvas = $('scheduledCanvas');
const shellHeader = $('shellHeader');
const syncStateEl = $('syncState');
const revisionEl = $('revision');

let server = clean(localStorage.getItem('iz_server') || '');
let code = String(localStorage.getItem('iz_code') || '').trim().toUpperCase();
let token = '';
let currentState = null;
let trustedEpochMs = null;
let monoAnchor = null;
let connected = false;
let unauthorizedCount = 0;
let lastStateSyncMono = 0;
let loopGeneration = 0;
let nativeRequestSeq = 0;
let scheduledRenderKey = '';
let baseRenderKey = '';
let lastRuntimeKey = '';
let suppressedScheduleId = '';
let lastPreview = { status:'YOK', capturedAt:null, bytes:0 };
let lastPeriodicPreviewAt = 0;
let lastPublicationError = '';

const qs = new URLSearchParams(location.search);
serverInput.value = server;
codeInput.value = code;

function clean(value) { return String(value || '').trim().replace(/\/+$/, ''); }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function escText(value) { return String(value ?? ''); }
function clamp(value, min, max, fallback) { const n = Number(value); return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback; }
function safeMode(value) { const m = String(value || 'PASSENGER').toUpperCase(); return MODES.has(m) ? m : 'PASSENGER'; }
function safeAlign(value) { const v = String(value || 'left').toLowerCase(); return ['left','center','right','justify'].includes(v) ? v : 'left'; }
function safeTransform(value) { const v = String(value || 'none').toLowerCase(); return ['none','uppercase','lowercase','capitalize'].includes(v) ? v : 'none'; }

const nativePending = new Map();
window.OperisNativeCallbacks = {
  resolve(id, raw) {
    const key = String(id || '');
    const pending = nativePending.get(key);
    if (!pending) return;
    nativePending.delete(key);
    clearTimeout(pending.timer);
    try {
      const parsed = JSON.parse(String(raw || '{}'));
      pending.resolve(parsed && typeof parsed === 'object' ? parsed : {});
    } catch (error) {
      pending.reject(error);
    }
  }
};

function nativeBridge() { return window.OperisNative || null; }
function nativeCall(method, args, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const bridge = nativeBridge();
    if (!bridge || typeof bridge[method] !== 'function') {
      reject(new Error('Native Android köprüsü bulunamadı: ' + method));
      return;
    }
    const requestId = 'n' + Date.now().toString(36) + (++nativeRequestSeq).toString(36);
    const timer = setTimeout(() => {
      if (nativePending.delete(requestId)) reject(new Error('Native istek zaman aşımı.'));
    }, Math.max(1000, Number(timeoutMs) || 30000));
    nativePending.set(requestId, { resolve, reject, timer });
    try { bridge[method](requestId, ...args); }
    catch (error) { clearTimeout(timer); nativePending.delete(requestId); reject(error); }
  });
}

function nativeJson(method) {
  try {
    const bridge = nativeBridge();
    if (!bridge || typeof bridge[method] !== 'function') return {};
    const parsed = JSON.parse(String(bridge[method]() || '{}'));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch { return {}; }
}
function nativeTelemetry() { return nativeJson('getTelemetryJson'); }
function nativeCapabilities() { return nativeJson('getCapabilitiesJson'); }
function nativeMediaStatus() { return nativeJson('getMediaCacheStatusJson'); }
function cachedMediaUrl(mediaId) {
  try {
    const bridge = nativeBridge();
    if (!bridge || typeof bridge.getCachedMediaUrl !== 'function') return '';
    return String(bridge.getCachedMediaUrl(String(mediaId || '')) || '');
  } catch { return ''; }
}
function nativeCommand(type) {
  try {
    const bridge = nativeBridge();
    if (!bridge || typeof bridge.executeDeviceCommand !== 'function') return {ok:false,supported:false,message:'Native cihaz köprüsü bulunamadı.'};
    const parsed = JSON.parse(String(bridge.executeDeviceCommand(type) || '{}'));
    return parsed && typeof parsed === 'object' ? parsed : {ok:false,supported:false,message:'Native komut sonucu okunamadı.'};
  } catch (error) { return {ok:false,supported:false,message:String(error?.message || error)}; }
}

function syncNativeConnection() {
  try {
    const bridge = nativeBridge();
    if (bridge && typeof bridge.setConnectionSettings === 'function') bridge.setConnectionSettings(server, code);
  } catch {}
}
function readDeviceToken() {
  const legacy = String(localStorage.getItem('iz_token') || '');
  try {
    const bridge = nativeBridge();
    if (bridge && typeof bridge.getDeviceToken === 'function') {
      let nativeToken = String(bridge.getDeviceToken() || '');
      if (!nativeToken && legacy && typeof bridge.setDeviceToken === 'function') { bridge.setDeviceToken(legacy); nativeToken = legacy; }
      localStorage.removeItem('iz_token');
      return nativeToken;
    }
  } catch {}
  return legacy;
}
function saveDeviceToken(value) {
  token = String(value || '');
  try {
    const bridge = nativeBridge();
    if (bridge && typeof bridge.setDeviceToken === 'function') { bridge.setDeviceToken(token); localStorage.removeItem('iz_token'); return; }
  } catch {}
  localStorage.setItem('iz_token', token);
}
function clearDeviceToken() {
  token = '';
  try { const bridge = nativeBridge(); if (bridge && typeof bridge.clearDeviceToken === 'function') bridge.clearDeviceToken(); } catch {}
  localStorage.removeItem('iz_token');
}

async function apiFetch(url, options = {}) {
  if (!server || !url.startsWith(server)) throw new Error('API isteği yapılandırılmış sunucu dışına çıkamaz.');
  const path = url.slice(server.length);
  if (!path.startsWith('/')) throw new Error('Geçersiz API yolu.');
  const result = await nativeCall('apiRequestAsync', [String(options.method || 'GET').toUpperCase(), server, path, String(options.body || '')], 30000);
  const body = String(result.body || '');
  return { ok:!!result.ok, status:Number(result.status)||0, text:async()=>body, json:async()=>JSON.parse(body || 'null') };
}

async function register(force = false) {
  if (!server || !code) throw new Error('Sunucu adresi ve cihaz kodu gerekli.');
  if (!/^https?:\/\//i.test(server)) throw new Error('Sunucu adresi http:// veya https:// ile başlamalı.');
  syncNativeConnection();
  if (force) clearDeviceToken();
  if (!token) token = readDeviceToken();
  if (!token) {
    const response = await apiFetch(server + '/api/device/register', { method:'POST', body:JSON.stringify({Code:code, Name:'Operis Digital Signage', Version:VERSION}) });
    const text = await response.text();
    if (!response.ok) throw new Error('Kayıt hatası ' + response.status + ': ' + text);
    const object = JSON.parse(text || '{}');
    const newToken = object.token || object.Token;
    if (!newToken) throw new Error('Sunucu cihaz tokenı döndürmedi.');
    saveDeviceToken(newToken);
  }
  localStorage.setItem('iz_server', server);
  localStorage.setItem('iz_code', code);
  syncNativeConnection();
  return token;
}

function parseServerTime(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value < 1e12 ? value * 1000 : value;
  let text = String(value || '').trim();
  if (!text) return null;
  text = text.replace(' ', 'T');
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,6})?)?$/.test(text)) text += '+03:00';
  const ms = Date.parse(text);
  return Number.isFinite(ms) ? ms : null;
}
function serverTimeValue(state) { return state?.serverTimeUtc ?? state?.ServerTimeUtc ?? state?.serverTime ?? state?.ServerTime ?? ''; }
function persistTrustedTimeAnchor(serverEpochMs) {
  try { localStorage.setItem(OPERIS_TRUSTED_TIME_ANCHOR, JSON.stringify({serverEpochMs:Number(serverEpochMs),deviceWallMs:Date.now()})); } catch {}
}
function restoreTrustedTimeAnchor() {
  try {
    const saved = JSON.parse(localStorage.getItem(OPERIS_TRUSTED_TIME_ANCHOR) || 'null');
    if (!saved || !Number.isFinite(Number(saved.serverEpochMs)) || !Number.isFinite(Number(saved.deviceWallMs))) return false;
    const elapsed = Math.max(0, Date.now() - Number(saved.deviceWallMs));
    trustedEpochMs = Number(saved.serverEpochMs) + elapsed;
    monoAnchor = performance.now();
    return true;
  } catch { return false; }
}
function anchorTrustedTime(state) {
  const ms = parseServerTime(serverTimeValue(state));
  if (!Number.isFinite(ms)) return false;
  trustedEpochMs = ms;
  monoAnchor = performance.now();
  persistTrustedTimeAnchor(ms);
  return true;
}
function trustedNowMs() { return Number.isFinite(trustedEpochMs) && monoAnchor !== null ? trustedEpochMs + (performance.now() - monoAnchor) : null; }

function savePublicationState(state) {
  try {
    const copy = JSON.parse(JSON.stringify(state || {}));
    copy._operisSavedWallMs = Date.now();
    localStorage.setItem(OPERIS_PUBLICATION_KEY, JSON.stringify(copy));
  } catch {}
}
function loadPublicationState() {
  try { return JSON.parse(localStorage.getItem(OPERIS_PUBLICATION_KEY) || 'null'); } catch { return null; }
}
function loadRuntime() {
  try {
    const runtime = JSON.parse(localStorage.getItem(OPERIS_RUNTIME_KEY) || 'null');
    if (runtime?.suppressedScheduleId) suppressedScheduleId = String(runtime.suppressedScheduleId);
    if (runtime?.lastPreview && typeof runtime.lastPreview === 'object') lastPreview = runtime.lastPreview;
    return runtime || {};
  } catch { return {}; }
}
function saveRuntime(runtime) {
  try {
    const object = Object.assign({}, runtime || {}, {suppressedScheduleId, lastPreview, updatedAt:Date.now()});
    localStorage.setItem(OPERIS_RUNTIME_KEY, JSON.stringify(object));
  } catch {}
}

function signageSchedules(state) {
  const candidates = state?.signageSchedules ?? state?.publicationSchedules ?? state?.campaignSchedules;
  if (Array.isArray(candidates)) return candidates;
  const raw = Array.isArray(state?.schedules) ? state.schedules : [];
  if (raw.some(x => x && (x.playlistId != null || x.startAt || x.dailyStart))) return raw.filter(x => x && (x.playlistId != null || x.startAt || x.dailyStart));
  return [];
}
function tripSchedules(state) {
  const explicit = state?.tripSchedules ?? state?.departures;
  if (Array.isArray(explicit)) return explicit;
  const raw = Array.isArray(state?.schedules) ? state.schedules : [];
  return raw.filter(x => x && (x.departure || x.departureTime || x.time) && !x.playlistId);
}
function playlists(state) { return Array.isArray(state?.playlists) ? state.playlists : (Array.isArray(state?.mediaPlaylists) ? state.mediaPlaylists : []); }
function baseLayoutOf(state) { return state?.baseLayout ?? state?.activeLayout ?? state?.layout ?? null; }
function operatingModeOf(state) { return safeMode(state?.operatingMode ?? state?.deviceSettings?.operatingMode ?? state?.mode ?? 'PASSENGER'); }
function signageState(state) { return { operatingMode:operatingModeOf(state), baseLayout:baseLayoutOf(state), schedules:signageSchedules(state), playlists:playlists(state) }; }
function publicationRevision(state) { return String(state?.revision ?? state?.Revision ?? state?.publicationRevision ?? ''); }

function mediaCatalog(state) {
  const list = Array.isArray(state?.media) ? state.media : (Array.isArray(state?.mediaFiles) ? state.mediaFiles : []);
  return list.filter(Boolean);
}
function mediaIdOf(media) {
  let raw = media?.mediaId ?? media?.id ?? media?.storedName ?? media?.fileName ?? '';
  raw = String(raw || '').replace(/[^A-Za-z0-9._-]/g, '_').replace(/\.\.+/g, '_').slice(0,128);
  return raw || null;
}
function mediaPathOf(media) {
  const direct = String(media?.relativeUrl ?? media?.path ?? media?.url ?? '').trim();
  if (direct.startsWith('/media/') && !direct.startsWith('//')) return direct;
  const stored = String(media?.storedName ?? '').trim();
  if (stored) return '/media/' + encodeURIComponent(stored);
  return '';
}
function mediaById(state, mediaId) {
  const key = String(mediaId ?? '');
  return mediaCatalog(state).find(m => String(m?.mediaId ?? m?.id ?? m?.storedName ?? '') === key) || null;
}
function mergeMediaDescriptor(item, state) {
  const fromCatalog = item?.mediaId != null ? mediaById(state, item.mediaId) : null;
  return Object.assign({}, fromCatalog || {}, item || {});
}
function layoutComponents(layout) { return Array.isArray(layout?.components) ? layout.components : []; }
function requiredMediaDescriptors(state) {
  const out = new Map();
  const add = raw => {
    if (!raw) return;
    const merged = mergeMediaDescriptor(raw, state);
    const id = mediaIdOf(merged);
    if (id) out.set(id, merged);
  };
  for (const media of mediaCatalog(state)) if (media.required === true) add(media);
  for (const playlist of playlists(state)) for (const item of (Array.isArray(playlist?.items) ? playlist.items : [])) add(item);
  for (const component of layoutComponents(baseLayoutOf(state))) {
    const type = String(component?.type || '').toUpperCase();
    if (['IMAGE','VIDEO','LOGO','MEDIA_REGION'].includes(type)) {
      const mediaId = component?.settings?.mediaId ?? component?.mediaId;
      if (mediaId != null) add({mediaId});
      const playlistId = component?.settings?.playlistId ?? component?.playlistId;
      if (playlistId != null) {
        const playlist = playlists(state).find(p => String(p?.id) === String(playlistId));
        for (const item of (Array.isArray(playlist?.items) ? playlist.items : [])) add(item);
      }
    }
  }
  return [...out.values()];
}
async function downloadMediaDescriptor(media) {
  const id = mediaIdOf(media);
  if (!id) return false;
  if (cachedMediaUrl(id)) return true;
  const path = mediaPathOf(media);
  if (!path) return false;
  const result = await nativeCall('downloadMediaAsync', [server, path, id, String(media?.sha256 || media?.hash || '')], 10 * 60 * 1000);
  return !!result.ok && !!cachedMediaUrl(id);
}
async function ensurePublicationMedia(state) {
  const required = requiredMediaDescriptors(state);
  for (const media of required) {
    const ok = await downloadMediaDescriptor(media);
    if (!ok) { lastPublicationError = 'Medya hazırlanamadı: ' + (mediaIdOf(media) || 'bilinmeyen'); return false; }
  }
  lastPublicationError = '';
  return true;
}
function mediaUrlFor(item, state) {
  const merged = mergeMediaDescriptor(item, state);
  const id = mediaIdOf(merged);
  return id ? cachedMediaUrl(id) : '';
}

function showSetup(message) { setup.style.display = 'flex'; player.style.display = 'none'; if (message) setupStatus.textContent = message; }
function showPlayer() { setup.style.display = 'none'; player.style.display = 'block'; }
function setSync(text, cls) { syncStateEl.textContent = text; syncStateEl.className = 'status-pill ' + (cls || ''); }

function uiValue(key, fallback = '') {
  const sources = [currentState?.uiText,currentState?.branding,currentState?.deviceSettings?.uiText,currentState?.deviceSettings?.branding,currentState?.organization];
  for (const source of sources) if (source && source[key] != null) return String(source[key]);
  return String(fallback);
}
function syncExitPassword(state) {
  const pin = String(state?.exitPassword ?? state?.ExitPassword ?? state?.deviceSettings?.exitPassword ?? '').trim();
  if (!/^\d{4,12}$/.test(pin)) return;
  try { const bridge = nativeBridge(); if (bridge && typeof bridge.updateExitPassword === 'function') bridge.updateExitPassword(pin); } catch {}
}

function trParts(ms) {
  const d = new Date(ms + TR_OFFSET_MS);
  return { y:d.getUTCFullYear(), m:d.getUTCMonth()+1, day:d.getUTCDate(), hh:d.getUTCHours(), mm:d.getUTCMinutes(), ss:d.getUTCSeconds(), wd:d.getUTCDay() };
}
function fmtClock(ms) { const p=trParts(ms); return [p.hh,p.mm,p.ss].map(x=>String(x).padStart(2,'0')).join(':'); }
function fmtDate(ms) { const p=trParts(ms); return String(p.day).padStart(2,'0') + '.' + String(p.m).padStart(2,'0') + '.' + p.y; }
function tripDateMs(row, nowMs) {
  const time = String(row?.departure ?? row?.departureTime ?? row?.time ?? '').trim();
  if (!/^\d{1,2}:\d{2}$/.test(time)) return null;
  const [hh,mm] = time.split(':').map(Number);
  const p = trParts(nowMs);
  const dateText = String(row?.date ?? row?.serviceDate ?? '').trim();
  let y=p.y,m=p.m,d=p.day;
  if (/^\d{4}-\d{2}-\d{2}$/.test(dateText)) [y,m,d]=dateText.split('-').map(Number);
  const utc = Date.UTC(y,m-1,d,hh-3,mm,0,0);
  if (!dateText && utc < nowMs - 6*60*60*1000) return utc + 24*60*60*1000;
  return utc;
}
function upcomingTrips(state, nowMs, limit=10) {
  return tripSchedules(state).map(row => ({row,ms:tripDateMs(row,nowMs)})).filter(x => Number.isFinite(x.ms) && x.ms >= nowMs - 60000).sort((a,b)=>a.ms-b.ms).slice(0,limit);
}
function remainText(ms, nowMs) { const minutes = Math.max(0, Math.ceil((ms-nowMs)/60000)); return minutes === 0 ? 'Şimdi' : minutes + ' dk'; }
function pickAnnouncement(state) {
  const broadcasts = Array.isArray(state?.broadcasts) ? [...state.broadcasts] : [];
  broadcasts.sort((a,b)=>(Number(b?.priority)||0)-(Number(a?.priority)||0));
  if (broadcasts[0]) return broadcasts[0].text ?? broadcasts[0].title ?? '';
  const announcements = Array.isArray(state?.announcements) ? state.announcements : [];
  return announcements[0]?.text ?? '';
}

function componentStyle(component, layout) {
  const width = Math.max(1, Number(layout?.width) || 1920);
  const height = Math.max(1, Number(layout?.height) || 1080);
  const sx = Math.max(0.01, baseCanvas.clientWidth / width);
  const sy = Math.max(0.01, baseCanvas.clientHeight / height);
  const scale = Math.min(sx, sy);
  const visible = component?.visible !== false;
  return {
    display: visible ? 'flex' : 'none',
    left: Math.round(clamp(component?.x, -width, width*2, 0) * sx) + 'px',
    top: Math.round(clamp(component?.y, -height, height*2, 0) * sy) + 'px',
    width: Math.round(clamp(component?.width, 1, width*3, 100) * sx) + 'px',
    height: Math.round(clamp(component?.height, 1, height*3, 100) * sy) + 'px',
    zIndex: String(Math.round(clamp(component?.zIndex, -1000, 10000, 1))),
    opacity: String(clamp(component?.opacity, 0, 1, 1)),
    color: String(component?.textColor || '#FFFFFF'),
    background: String(component?.gradient || component?.background || 'transparent'),
    border: String(component?.border || '0'),
    borderRadius: Math.max(0, Number(component?.borderRadius)||0) + 'px',
    boxShadow: [component?.shadow,component?.glow].filter(Boolean).join(',') || 'none',
    fontFamily: String(component?.fontFamily || 'Arial'),
    fontSize: Math.max(8, Math.round(clamp(component?.fontSize, 8, 300, 28) * scale)) + 'px',
    fontWeight: String(component?.fontWeight || '700'),
    textAlign: safeAlign(component?.align),
    lineHeight: String(clamp(component?.lineHeight, 0.6, 4, 1.2)),
    letterSpacing: Math.round(clamp(component?.letterSpacing, -20, 80, 0) * scale) + 'px',
    textTransform: safeTransform(component?.textTransform),
    justifyContent: safeAlign(component?.align) === 'center' ? 'center' : (safeAlign(component?.align) === 'right' ? 'flex-end' : 'flex-start')
  };
}
function applyComponentStyle(element, component, layout) { Object.assign(element.style, componentStyle(component, layout)); }
function newComponent(component, layout, extraClass='') {
  const el = document.createElement('div');
  el.className = 'component ' + extraClass;
  el.dataset.componentId = String(component?.id ?? '');
  applyComponentStyle(el, component, layout);
  return el;
}
function appendText(el, text) { el.textContent = escText(text); return el; }
function renderTripTableInto(el, state, nowMs, rows=10) {
  el.classList.add('table');
  for (const item of upcomingTrips(state, nowMs, rows)) {
    const row = document.createElement('div'); row.className='component-table-row';
    const time=document.createElement('span');time.className='t';time.textContent=String(item.row.departure ?? item.row.departureTime ?? item.row.time ?? '');
    const dest=document.createElement('span');dest.textContent=String(item.row.destination ?? item.row.route ?? '');
    const status=document.createElement('span');status.textContent=String(item.row.status ?? 'NORMAL');
    const rem=document.createElement('span');rem.className='r';rem.textContent=remainText(item.ms, nowMs);
    row.append(time,dest,status,rem); el.appendChild(row);
  }
  if (!el.children.length) appendText(el, uiValue('noUpcomingTrips','Yaklaşan sefer bulunmuyor.'));
}
function renderFirstTripsInto(el, state, nowMs) {
  const seen=new Set(), parts=[];
  for (const item of upcomingTrips(state,nowMs,60)) {
    const dest=String(item.row.destination ?? item.row.route ?? '');
    if (!dest || seen.has(dest)) continue;
    seen.add(dest); parts.push(dest + ' — ' + String(item.row.departure ?? item.row.departureTime ?? item.row.time ?? ''));
    if (parts.length >= 6) break;
  }
  appendText(el, parts.join('\n') || 'Yaklaşan sefer bulunmuyor.');
  el.style.whiteSpace='pre-line';
}
function renderMediaInto(el, item, state, objectFit='contain') {
  const merged=mergeMediaDescriptor(item,state);
  const url=mediaUrlFor(merged,state);
  if (!url) { const missing=document.createElement('div');missing.className='media-missing';missing.textContent='Medya önbellekte bulunamadı';el.appendChild(missing);return; }
  const kind=String(merged?.type ?? merged?.mediaType ?? merged?.fileName ?? '').toUpperCase();
  const isVideo=kind.includes('VIDEO') || /\.(MP4|WEBM|M4V)$/i.test(String(merged?.fileName ?? merged?.storedName ?? ''));
  const media=document.createElement(isVideo?'video':'img');
  media.src=url; media.style.objectFit=String(merged?.objectFit || objectFit || 'contain');
  if (isVideo) { media.autoplay=true; media.muted=true; media.playsInline=true; media.loop=!!merged?.loop; }
  el.appendChild(media);
}
function renderMediaRegionInto(el, component, state, nowMs) {
  const playlistId=component?.settings?.playlistId ?? component?.playlistId;
  const playlist=playlists(state).find(p=>String(p?.id)===String(playlistId));
  if (playlist) {
    const selected=playlistItemAt(nowMs, Number(state?._operisSavedWallMs)||nowMs, playlist);
    if (selected.item) renderMediaInto(el, selected.item, state, component?.settings?.objectFit || 'cover');
    return;
  }
  renderMediaInto(el,{mediaId:component?.settings?.mediaId ?? component?.mediaId,type:component?.type},state,component?.settings?.objectFit||'contain');
}
function renderComponent(component, context) {
  const type=String(component?.type||'').toUpperCase();
  const el=newComponent(component,context.layout,type==='SCHEDULE_TABLE'?'table':'');
  switch(type) {
    case 'LOGO': renderMediaInto(el,{mediaId:component?.settings?.mediaId ?? component?.mediaId,type:'IMAGE'},context.state,'contain'); break;
    case 'STATION_NAME': appendText(el,context.state?.stationName ?? context.state?.screenName ?? uiValue('unassignedDevice','Atanmamış Cihaz')); break;
    case 'CLOCK': appendText(el,context.nowMs==null?'--:--:--':fmtClock(context.nowMs)); break;
    case 'DATE': appendText(el,context.nowMs==null?'':fmtDate(context.nowMs)); break;
    case 'CLOCK_DATE': appendText(el,context.nowMs==null?'Saat bilgisi bekleniyor':fmtDate(context.nowMs)+' — '+fmtClock(context.nowMs)); break;
    case 'FIRST_TRIPS': renderFirstTripsInto(el,context.state,context.nowMs ?? Date.now()); break;
    case 'SCHEDULE_TABLE': renderTripTableInto(el,context.state,context.nowMs ?? Date.now(),Number(component?.settings?.rows)||10); break;
    case 'ANNOUNCEMENT': case 'TICKER': appendText(el,pickAnnouncement(context.state) || component?.text || ''); break;
    case 'IMAGE': case 'VIDEO': renderMediaInto(el,{mediaId:component?.settings?.mediaId ?? component?.mediaId,type},context.state,component?.settings?.objectFit||'contain'); break;
    case 'WEATHER': { const w=context.state?.weather||{}; appendText(el,(w.temperature ?? w.temp ?? '--')+'°C '+(w.condition||'')); break; }
    case 'FREE_TEXT': appendText(el,component?.text ?? component?.settings?.text ?? ''); break;
    case 'MEDIA_REGION': renderMediaRegionInto(el,component,context.state,context.nowMs ?? Date.now()); break;
    default: appendText(el,component?.text ?? '');
  }
  return el;
}

function updateShell(nowMs) {
  const station=String(currentState?.stationName ?? currentState?.screenName ?? uiValue('unassignedDevice','Atanmamış Cihaz'));
  $('station').textContent=station;
  $('orgName').textContent=uiValue('organizationName',uiValue('displayName','Operis Digital Signage'));
  $('clock').textContent=nowMs==null?'--:--:--':fmtClock(nowMs);
  $('clockDate').textContent=nowMs==null?uiValue('timeWaiting','Saat bilgisi bekleniyor'):fmtDate(nowMs);
  const logoId=currentState?.organization?.logoMediaId ?? currentState?.branding?.logoMediaId;
  const logo=logoId!=null?cachedMediaUrl(mediaIdOf({mediaId:logoId})||''):'';
  const img=$('orgLogo');
  if (logo) { img.src=logo; img.style.display='block'; } else { img.removeAttribute('src'); img.style.display='none'; }
}
function renderFallbackPassenger(nowMs) {
  shellHeader.style.display='flex';
  baseCanvas.innerHTML='';
  const frame=document.createElement('section');frame.className='fallback-passenger';
  const title=document.createElement('div');title.className='fallback-title';
  const left=document.createElement('span');left.textContent=uiValue('scheduleTitle','Yolcu Bilgilendirme');
  const right=document.createElement('span');right.textContent=uiValue('displayName','Operis Digital Signage');right.style.color='var(--operis-accent)';right.style.fontSize='16px';
  title.append(left,right);
  const table=document.createElement('div');table.className='trip-table';
  const list=nowMs==null?[]:upcomingTrips(currentState,nowMs,10);
  if (!list.length) { const empty=document.createElement('div');empty.className='empty-state';empty.textContent=uiValue('noUpcomingTrips','Yaklaşan sefer bulunmuyor.');table.appendChild(empty); }
  else for (const item of list) {
    const row=document.createElement('div');row.className='trip-row';
    const time=document.createElement('div');time.className='time';time.textContent=String(item.row.departure ?? item.row.departureTime ?? item.row.time ?? '');
    const dest=document.createElement('div');dest.textContent=String(item.row.destination ?? item.row.route ?? '');
    const status=document.createElement('div');status.textContent=String(item.row.status ?? 'NORMAL');
    const remain=document.createElement('div');remain.className='remain';remain.textContent=remainText(item.ms,nowMs);
    row.append(time,dest,status,remain);table.appendChild(row);
  }
  const ticker=document.createElement('div');ticker.className='ticker';ticker.textContent=pickAnnouncement(currentState)||uiValue('defaultTicker','');
  frame.append(title,table,ticker);baseCanvas.appendChild(frame);
}
function basePlaylist(state) {
  const list=playlists(state);
  const id=state?.basePlaylistId ?? state?.defaultPlaylistId ?? state?.playlistId ?? state?.deviceSettings?.playlistId;
  return list.find(p=>String(p?.id)===String(id)) || list[0] || null;
}
function renderPlaylistStage(container, playlist, activeSinceMs, nowMs, state, targetClass='') {
  container.innerHTML='';
  if (!playlist) { const empty=document.createElement('div');empty.className='empty-state';empty.textContent='Playlist atanmadı';container.appendChild(empty);return ''; }
  const selected=playlistItemAt(nowMs,activeSinceMs,playlist);
  const item=selected.item;
  if (!item) return '';
  const key=String(playlist.id||'')+':'+selected.index+':'+String(mediaIdOf(item)||item.type||'');
  const stage=document.createElement('div');stage.className='media-stage '+targetClass;
  const type=String(item?.type||'').toUpperCase();
  if (type==='TEXT'||type==='ANNOUNCEMENT'||type==='FREE_TEXT') { const card=document.createElement('div');card.className='text-card';card.textContent=String(item.text||'');stage.appendChild(card); }
  else {
    renderMediaInto(stage,item,state,item?.objectFit||'contain');
    const video=stage.querySelector('video');
    if (video && !Number(item.durationMs)) video.addEventListener('loadedmetadata',()=>{ if(Number.isFinite(video.duration)&&video.duration>0)item.durationMs=Math.round(video.duration*1000); },{once:true});
  }
  container.appendChild(stage);
  return key;
}
function renderBaseLayout(nowMs) {
  if (!currentState) return;
  updateShell(nowMs);
  const mode=operatingModeOf(currentState);
  const layout=baseLayoutOf(currentState);
  const hasLayout=layout && layoutComponents(layout).length;
  if (hasLayout) {
    shellHeader.style.display=currentState?.showChrome===true?'flex':'none';
    const key='layout:'+String(layout.id??layout.name??publicationRevision(currentState))+':'+Math.floor((nowMs||0)/1000);
    baseCanvas.innerHTML='';
    baseCanvas.style.background=String(layout.background || currentState?.profile?.background || '#071B2A');
    const context={state:currentState,layout,nowMs};
    for (const component of [...layoutComponents(layout)].sort((a,b)=>(Number(a?.zIndex)||0)-(Number(b?.zIndex)||0))) {
      if (component?.visible===false) continue;
      baseCanvas.appendChild(renderComponent(component,context));
    }
    if (currentState?.emergency?.text) { const emergency=document.createElement('div');emergency.className='emergency';emergency.textContent=String(currentState.emergency.text);baseCanvas.appendChild(emergency); }
    baseRenderKey=key;
    return;
  }
  if (mode==='ADVERTISING'||mode==='PLAYLIST') {
    shellHeader.style.display='none';
    const playlist=basePlaylist(currentState);
    const since=Number(currentState?._operisSavedWallMs)||Date.now();
    const selected=playlist?playlistItemAt(nowMs??Date.now(),since,playlist):{index:-1};
    const key='base-playlist:'+String(playlist?.id||'none')+':'+selected.index;
    if (key!==baseRenderKey) { renderPlaylistStage(baseCanvas,playlist,since,nowMs??Date.now(),currentState);baseRenderKey=key; }
    return;
  }
  renderFallbackPassenger(nowMs);
  baseRenderKey='fallback:'+Math.floor((nowMs||0)/1000);
}
function findRegionBox(regionId) {
  const layout=baseLayoutOf(currentState);
  const component=layoutComponents(layout).find(c=>String(c?.id)===String(regionId) || String(c?.name)===String(regionId));
  if (!component) return null;
  return componentStyle(component,layout);
}
function clearScheduledLayer() { scheduledCanvas.innerHTML='';scheduledRenderKey=''; }
function renderScheduledLayer(nowMs) {
  if (!currentState || nowMs==null) { clearScheduledLayer(); return; }
  const resolved=resolveSchedule(nowMs,signageState(currentState));
  const active=resolved.activeSchedule;
  if (!active || (suppressedScheduleId && String(active.id)===suppressedScheduleId)) { if(scheduledRenderKey)clearScheduledLayer();return; }
  const selected=playlistItemAt(nowMs,resolved.activeSinceMs??nowMs,resolved.playlist);
  const item=selected.item;
  if (!item) { clearScheduledLayer(); return; }
  const target=String(resolved.target||'FULLSCREEN');
  const key=String(active.id||'')+':'+selected.index+':'+target+':'+String(mediaIdOf(item)||item.type||'');
  if (key===scheduledRenderKey) return;
  scheduledCanvas.innerHTML='';
  const host=document.createElement('div');host.style.position='absolute';
  if (target.startsWith('REGION:')) {
    const regionId=target.slice('REGION:'.length);
    const box=findRegionBox(regionId);
    if (!box) { clearScheduledLayer(); return; }
    Object.assign(host.style,{left:box.left,top:box.top,width:box.width,height:box.height,zIndex:'1'});
    host.className='media-stage region';
  } else {
    Object.assign(host.style,{inset:'0'});host.className='media-stage';
  }
  const type=String(item?.type||'').toUpperCase();
  if (type==='TEXT'||type==='ANNOUNCEMENT'||type==='FREE_TEXT') { const card=document.createElement('div');card.className='text-card';card.textContent=String(item.text||'');host.appendChild(card); }
  else {
    renderMediaInto(host,item,currentState,item?.objectFit||'contain');
    const video=host.querySelector('video');
    if (video && !Number(item.durationMs)) video.addEventListener('loadedmetadata',()=>{if(Number.isFinite(video.duration)&&video.duration>0)item.durationMs=Math.round(video.duration*1000);},{once:true});
  }
  scheduledCanvas.appendChild(host);scheduledRenderKey=key;
  const runtimeKey=String(active.id||'')+':'+selected.index;
  if(runtimeKey!==lastRuntimeKey){lastRuntimeKey=runtimeKey;saveRuntime({activeScheduleId:String(active.id||''),playlistId:String(resolved.playlist?.id||''),itemIndex:selected.index,mediaId:mediaIdOf(item),target});}
}

function renderTick() {
  if (!currentState) return;
  const nowMs=trustedNowMs();
  document.title=uiValue('displayName','Operis Digital Signage');
  revisionEl.textContent='Rev '+(publicationRevision(currentState)||'-');
  if (nowMs==null) setSync(uiValue('timeWaiting','Saat bilgisi bekleniyor'),'warn');
  else if (!connected) setSync('ÇEVRİMDIŞI — SON MERKEZ ZAMANIYLA ÇALIŞIYOR','warn');
  else setSync('SENKRON — '+fmtClock(nowMs),'ok');
  renderBaseLayout(nowMs);
  renderScheduledLayer(nowMs);
}

async function heartbeat() {
  if (!server||!code||!token) return false;
  try {
    const native=nativeTelemetry(),caps=nativeCapabilities(),media=nativeMediaStatus();
    const now=trustedNowMs();
    const resolved=currentState&&now!=null?resolveSchedule(now,signageState(currentState)):null;
    const body=Object.assign({},native,caps,{
      Code:code,Token:token,Version:VERSION_NAME,VersionCode:VERSION_CODE,
      resolution:native.resolution||(screen.width+'x'+screen.height),
      trustedTimeStatus:now!=null?'SYNCED':'SAAT_SENKRONU_BEKLENIYOR',
      lastServerSync:Number.isFinite(trustedEpochMs)?new Date(trustedEpochMs).toISOString():'Desteklenmiyor',
      operatingMode:currentState?operatingModeOf(currentState):'Desteklenmiyor',
      activeScheduleId:resolved?.activeSchedule?.id ?? null,
      mediaCache:media,
      lastPreview,
      lastPublicationError
    });
    const response=await apiFetch(server+'/api/device/heartbeat',{method:'POST',body:JSON.stringify(body)});
    return response.ok;
  } catch { return false; }
}

async function ackCommand(id,status,result) {
  try { const r=await apiFetch(server+'/api/device/commands/ack',{method:'POST',body:JSON.stringify({code,token,commandId:id,status,result:result||{}})});return r.ok; }
  catch { return false; }
}
function pendingReboot() { try { const p=JSON.parse(localStorage.getItem(PENDING_REBOOT_KEY)||'null');return p&&p.id?p:null; } catch { return null; } }
async function completePendingRebootAck() {
  const pending=pendingReboot();if(!pending||!server||!code||!token||pending.server!==server||pending.code!==code)return;
  if(!await heartbeat())return;
  if(await ackCommand(pending.id,'BASARILI',{phase:'REBOOT_SONRASI_ONLINE',message:'Cihaz yeniden başladı ve heartbeat sunucuya ulaştı.'}))localStorage.removeItem(PENDING_REBOOT_KEY);
}
async function captureAndUploadPreviewAsync() {
  if(!server||!code||!token)throw new Error('Preview için bağlantı bilgisi eksik.');
  const result=await nativeCall('captureAndUploadPreviewAsync',[server,code,token],120000);
  if(!result.ok)throw new Error(result.message||result.status||'Preview gönderilemedi.');
  lastPreview={status:'BASARILI',capturedAt:result.capturedAt||new Date().toISOString(),bytes:Number(result.bytes)||0,width:result.width||0,height:result.height||0};
  saveRuntime({});
  return result;
}
async function maybePeriodicPreview() {
  const cfg=currentState?.preview ?? currentState?.deviceSettings?.preview ?? {};
  if(cfg.enabled!==true)return;
  const interval=Math.max(60000,Number(cfg.intervalMs)||Math.max(1,Number(cfg.intervalMinutes)||5)*60000);
  const now=Date.now();if(now-lastPeriodicPreviewAt<interval)return;lastPeriodicPreviewAt=now;
  try{await captureAndUploadPreviewAsync();}catch(error){lastPreview={status:'BASARISIZ',capturedAt:new Date().toISOString(),message:String(error?.message||error)};saveRuntime({});}
}

async function nativeApkCandidate(command,payload,url) {
  const result=await nativeCall('downloadAndVerifyApkAsync',[server,url,String(payload.sha256||''),String(command.id||''),JSON.stringify({packageName:String(payload.packageName||''),versionName:String(payload.versionName||''),versionCode:payload.versionCode==null?-1:Number(payload.versionCode),signatureDigest:String(payload.signatureDigest||'')})],300000);
  if(!result.ok)throw new Error(result.message||result.errorCode||'APK doğrulanamadı.');return result;
}
async function refreshAndConfirm(message) {
  const before=lastStateSyncMono;await syncState();if(lastStateSyncMono<=before)throw new Error('Sunucudan yeni yayın durumu alınamadı.');return message;
}
async function handleCommand(command) {
  const type=String(command?.type||'').toUpperCase();
  await ackCommand(command.id,'CALISIYOR',{phase:'CALISIYOR',supported:true});
  try {
    if(['REFRESH','SCREEN_REFRESH','PROFILE_SYNC','PLAYLIST_REFRESH','SCHEDULE_REFRESH'].includes(type)) { await refreshAndConfirm('Yayın durumu yenilendi');suppressedScheduleId='';saveRuntime({});await ackCommand(command.id,'BASARILI',{supported:true,message:'Yayın/playlist/zamanlama yenilendi.'});return; }
    if(type==='TIME_REFRESH'){await refreshAndConfirm('Zaman yenilendi');if(!await heartbeat())throw new Error('Zaman yenilendi ancak heartbeat ulaşmadı.');await ackCommand(command.id,'BASARILI',{supported:true,message:'Güvenilir zaman yeniden senkronlandı.'});return;}
    if(type==='CONNECTION_REFRESH'){await reconnectToCenter(false);await ackCommand(command.id,'BASARILI',{supported:true,message:'Merkez bağlantısı yenilendi.'});startLoops();return;}
    if(type==='TELEMETRY_REFRESH'){if(!await heartbeat())throw new Error('Telemetri heartbeat ulaşmadı.');await ackCommand(command.id,'BASARILI',{supported:true,message:'Telemetri gönderildi.'});return;}
    if(type==='RETURN_TO_BASE_LAYOUT'){const now=trustedNowMs();const resolved=now==null?null:resolveSchedule(now,signageState(currentState));suppressedScheduleId=String(resolved?.activeSchedule?.id||'');clearScheduledLayer();renderBaseLayout(now);saveRuntime({});await ackCommand(command.id,'BASARILI',{supported:true,message:'Geçici yayın kapatıldı; ana yayına dönüldü.'});return;}
    if(type==='SCREEN_PREVIEW_REFRESH'){const result=await captureAndUploadPreviewAsync();await ackCommand(command.id,'BASARILI',{supported:true,message:'Player ekran önizlemesi gönderildi.',preview:result});return;}
    if(type==='APP_RESTART'){await ackCommand(command.id,'BASARILI',{supported:true,message:'Player yeniden başlatılıyor.'});setTimeout(()=>location.reload(),400);return;}
    if(type==='DEVICE_REBOOT'){const caps=nativeCapabilities();if(!caps.rebootSupported){await ackCommand(command.id,'BASARISIZ',{supported:false,detail:'Device Owner olmadığı için reboot desteklenmiyor.'});return;}localStorage.setItem(PENDING_REBOOT_KEY,JSON.stringify({id:String(command.id),server,code}));const result=nativeCommand(type);if(!result.ok){localStorage.removeItem(PENDING_REBOOT_KEY);throw new Error(result.message||'Cihaz yeniden başlatılamadı.');}return;}
    if(type==='KIOSK_LOCK'||type==='KIOSK_UNLOCK'){const result=nativeCommand(type);if(!result.supported||!result.ok){await ackCommand(command.id,'BASARISIZ',{supported:false,detail:result.message||'Kiosk komutu desteklenmiyor.'});return;}await ackCommand(command.id,'BASARILI',{supported:true,message:result.message});return;}
    if(type==='POWER_ON'){const result=nativeCommand(type);await ackCommand(command.id,'BASARISIZ',{supported:false,detail:result.message||'POWER_ON için gerçek WOL/MDM/üretici desteği gerekir.'});return;}
    if(type==='APK_UPDATE'){const payload=command.payload||{},url=String(payload.url||'');if(!url)throw new Error('APK URL eksik.');const verified=await nativeApkCandidate(command,payload,url);await ackCommand(command.id,'CALISIYOR',{supported:true,phase:'DOGRULANDI',sha256:verified.sha256||'',packageName:verified.packageName||'',versionCode:verified.versionCode??''});await ackCommand(command.id,'CALISIYOR',{supported:true,phase:'KULLANICI_ONAYI_GEREKLI',detail:'Normal modda Android kurulum onayı gerekir; doğrulanmış APK hazır.'});return;}
    await ackCommand(command.id,'BASARISIZ',{supported:false,detail:'Desteklenmeyen komut: '+type});
  } catch(error) { await ackCommand(command.id,'BASARISIZ',{supported:true,detail:String(error?.message||error)}); }
}

async function syncState() {
  if(!token)await register(false);
  try {
    const response=await apiFetch(server+'/api/player/state?code='+encodeURIComponent(code)+'&k='+encodeURIComponent(token));
    if(response.status===401){connected=false;unauthorizedCount++;localStorage.setItem('iz_unauth',String(unauthorizedCount));if(unauthorizedCount>=MAX_UNAUTHORIZED){clearDeviceToken();await register(true);}return AUTH_RETRY_MS;}
    if(!response.ok)throw new Error('state '+response.status);
    const next=await response.json();
    anchorTrustedTime(next);
    const previousRevision=currentState?publicationRevision(currentState):'';
    const ready=await ensurePublicationMedia(next);
    if(!ready)throw new Error(lastPublicationError||'Yeni yayın medyaları hazırlanamadı.');
    currentState=next;
    if(previousRevision && previousRevision!==publicationRevision(next))suppressedScheduleId='';
    syncExitPassword(next);savePublicationState(next);lastStateSyncMono=performance.now();connected=true;unauthorizedCount=0;localStorage.removeItem('iz_unauth');showPlayer();baseRenderKey='';scheduledRenderKey='';renderTick();return STATE_REFRESH_MS;
  } catch(error) {
    connected=false;
    if(currentState){showPlayer();renderTick();}
    else showSetup('Sunucuya ulaşılamıyor. Son doğrulanmış yayın verisi yok.\n'+String(error?.message||error));
    return STATE_REFRESH_MS;
  }
}
async function commandLoop(generation) { while(server&&code&&generation===loopGeneration){try{if(token){const response=await apiFetch(server+'/api/device/commands?code='+encodeURIComponent(code)+'&k='+encodeURIComponent(token));if(response.ok){const commands=await response.json();for(const command of (Array.isArray(commands)?commands:[])){if(generation!==loopGeneration)break;await handleCommand(command);}}}}catch{}if(generation===loopGeneration)await sleep(5000);} }
async function syncLoop(generation) { while(server&&code&&generation===loopGeneration){let delay=STATE_REFRESH_MS;try{delay=await syncState();}catch{connected=false;}if(generation===loopGeneration)await sleep(delay);} }
function startLoops(){const generation=++loopGeneration;syncLoop(generation);commandLoop(generation);}
async function reconnectToCenter(startPolling=true){++loopGeneration;connected=false;syncNativeConnection();setSync('BAĞLANTI YENİLENİYOR','warn');if(!token)token=readDeviceToken();try{await register(false);}catch{clearDeviceToken();await register(true);}if(!await heartbeat())throw new Error('SUNUCUYA ULAŞILAMIYOR');const before=lastStateSyncMono;await syncState();if(lastStateSyncMono<=before)throw new Error('Merkezden yeni yayın durumu alınamadı.');await completePendingRebootAck();if(startPolling)startLoops();return true;}

window.OperisStage2={
  reconnect:()=>reconnectToCenter(true),
  refreshState:async()=>{await refreshAndConfirm('Yayın yenilendi');return true;},
  sendTelemetry:async()=>{if(!await heartbeat())throw new Error('Telemetri sunucuya ulaştırılamadı.');return true;}
};

$('setupForm').addEventListener('submit',async event=>{
  event.preventDefault();
  const nextServer=clean(serverInput.value),nextCode=String(codeInput.value||'').trim().toUpperCase();
  if(nextServer!==server||nextCode!==code){server=nextServer;code=nextCode;clearDeviceToken();}
  localStorage.setItem('iz_server',server);localStorage.setItem('iz_code',code);syncNativeConnection();setupStatus.textContent='Sunucuya bağlanılıyor...';
  try{token=readDeviceToken();await register(false);await completePendingRebootAck();showPlayer();await syncState();startLoops();}
  catch(error){setupStatus.textContent='Bağlantı kurulamadı.\n'+String(error?.message||error);if(currentState){showPlayer();renderTick();}}
});

async function start() {
  syncNativeConnection();loadRuntime();token=readDeviceToken();currentState=loadPublicationState();
  if(currentState){connected=false;if(!restoreTrustedTimeAnchor()){const serverMs=parseServerTime(serverTimeValue(currentState));const savedWall=Number(currentState?._operisSavedWallMs);if(Number.isFinite(serverMs)){trustedEpochMs=serverMs+(Number.isFinite(savedWall)?Math.max(0,Date.now()-savedWall):0);monoAnchor=performance.now();}}syncExitPassword(currentState);showPlayer();renderTick();}
  if(qs.get('setup')==='1'){showSetup('Bağlantı ayarlarını değiştirebilirsiniz.');return;}
  if(server&&code){try{await register(false);await completePendingRebootAck();showPlayer();await syncState();startLoops();}catch(error){if(!currentState)showSetup('Sunucuya ulaşılamıyor.\n'+String(error?.message||error));}}
  else if(!currentState)showSetup('Sunucu adresini ve cihaz kodunu girin.');
}

setInterval(renderTick,1000);
setInterval(()=>heartbeat(),HEARTBEAT_MS);
setInterval(()=>maybePeriodicPreview(),60000);
start();
