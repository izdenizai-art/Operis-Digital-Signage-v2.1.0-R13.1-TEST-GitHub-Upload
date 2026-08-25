(function(){
'use strict';
const MODES = new Set(['PASSENGER', 'ADVERTISING', 'MIXED', 'PLAYLIST']);

function normalizedMode(value) {
  const mode = String(value || 'PASSENGER').toUpperCase();
  return MODES.has(mode) ? mode : 'PASSENGER';
}

function parseClock(value) {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(String(value || '').trim());
  if (!m) return null;
  const h = Number(m[1]), min = Number(m[2]), sec = Number(m[3] || 0);
  if (h < 0 || h > 23 || min < 0 || min > 59 || sec < 0 || sec > 59) return null;
  return h * 3600000 + min * 60000 + sec * 1000;
}

function localDayParts(nowMs, offsetMinutes) {
  const shifted = new Date(nowMs + offsetMinutes * 60000);
  return {
    day: shifted.getUTCDay(),
    dayStartUtcShifted: Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()),
    msOfDay: shifted.getUTCHours() * 3600000 + shifted.getUTCMinutes() * 60000 + shifted.getUTCSeconds() * 1000 + shifted.getUTCMilliseconds()
  };
}

function scheduleWindow(nowMs, schedule) {
  if (!schedule || schedule.enabled === false) return null;

  const start = schedule.startAt ? Date.parse(schedule.startAt) : NaN;
  const end = schedule.endAt ? Date.parse(schedule.endAt) : NaN;
  if (Number.isFinite(start) || Number.isFinite(end)) {
    if (Number.isFinite(start) && nowMs < start) return null;
    if (Number.isFinite(end) && nowMs >= end) return null;
    return { activeSinceMs: Number.isFinite(start) ? start : nowMs };
  }

  const dailyStart = parseClock(schedule.dailyStart);
  const dailyEnd = parseClock(schedule.dailyEnd);
  if (dailyStart == null || dailyEnd == null) return null;
  const offset = Number.isFinite(Number(schedule.timezoneOffsetMinutes)) ? Number(schedule.timezoneOffsetMinutes) : -new Date(nowMs).getTimezoneOffset();
  const parts = localDayParts(nowMs, offset);
  if (Array.isArray(schedule.daysOfWeek) && schedule.daysOfWeek.length && !schedule.daysOfWeek.map(Number).includes(parts.day)) return null;

  let active = false;
  let startDayShifted = parts.dayStartUtcShifted;
  if (dailyStart <= dailyEnd) {
    active = parts.msOfDay >= dailyStart && parts.msOfDay < dailyEnd;
  } else {
    active = parts.msOfDay >= dailyStart || parts.msOfDay < dailyEnd;
    if (parts.msOfDay < dailyEnd) startDayShifted -= 86400000;
  }
  if (!active) return null;

  const shiftedStartMs = startDayShifted + dailyStart;
  return { activeSinceMs: shiftedStartMs - offset * 60000 };
}

function playlistById(playlists, id) {
  return (Array.isArray(playlists) ? playlists : []).find(p => String(p?.id) === String(id)) || null;
}

function playlistItemAt(nowMs, activeSinceMs, playlist) {
  const items = Array.isArray(playlist?.items) ? playlist.items.filter(Boolean) : [];
  if (!items.length) return { item: null, index: -1, elapsedInItemMs: 0 };
  const durations = items.map(item => Math.max(1000, Number(item.durationMs) || 10000));
  const total = durations.reduce((a, b) => a + b, 0);
  let elapsed = Math.max(0, Number(nowMs) - Number(activeSinceMs ?? nowMs));
  if (playlist?.loop !== false && total > 0) elapsed %= total;
  else if (elapsed >= total) return { item: items[items.length - 1], index: items.length - 1, elapsedInItemMs: durations[durations.length - 1] };

  for (let i = 0; i < items.length; i++) {
    if (elapsed < durations[i]) return { item: items[i], index: i, elapsedInItemMs: elapsed };
    elapsed -= durations[i];
  }
  return { item: items[0], index: 0, elapsedInItemMs: 0 };
}

function resolveSchedule(nowMs, state = {}) {
  const mode = normalizedMode(state.operatingMode);
  const candidates = [];
  (Array.isArray(state.schedules) ? state.schedules : []).forEach((schedule, index) => {
    const window = scheduleWindow(nowMs, schedule);
    if (!window) return;
    const playlist = playlistById(state.playlists, schedule.playlistId);
    if (!playlist) return;
    candidates.push({ schedule, playlist, index, activeSinceMs: window.activeSinceMs });
  });
  candidates.sort((a, b) => (Number(b.schedule.priority) || 0) - (Number(a.schedule.priority) || 0) || a.index - b.index);
  const selected = candidates[0] || null;
  return {
    mode,
    baseLayout: state.baseLayout || null,
    activeSchedule: selected?.schedule || null,
    playlist: selected?.playlist || null,
    target: selected?.schedule?.target || null,
    activeSinceMs: selected?.activeSinceMs ?? null
  };
}

if (typeof window !== 'undefined') {
  window.OperisScheduleEngine = { resolve: resolveSchedule, resolveSchedule, playlistItemAt };
}

})();
