export interface Lesson {
  name: string;
  kind: string;
  teachers: string;
  location: string;
  content: string;
  date: string;
  week: number;
  sections: number[];
}
export interface ClassPeriod { section: number; start: string; end: string; }
export interface ScheduleSnapshot {
  termCode: string;
  termLabel: string;
  weekOneMonday: string;
  retrievedAt: string;
  calendarEndDate: string;
  source: string;
  lessons: Lesson[];
  periods: ClassPeriod[];
}
export interface Notice { id: string; text: string; publishedAt: string; }
export interface SyncPayload { termCode: string; xlsBase64: string; periods: ClassPeriod[]; }
export interface SyncResult {
  job: string;
  state: string;
  message?: string;
  payload?: SyncPayload;
  notices?: Notice[];
  noticesError?: string;
}
export interface CachedSchedule {
  schema: number;
  accountKey: string;
  snapshot: ScheduleSnapshot;
  notices: Notice[];
}

export function schoolUrlAllowed(value: string): boolean {
  // Strict authority boundary, no userinfo, custom port, lookalike suffix or HTTP.
  return /^https:\/\/(?:jwxt|tpass)\.ahtcm\.edu\.cn(?::443)?(?:\/|$)/.test(value);
}

export function dayFor(snapshot: ScheduleSnapshot, week: number, weekday: number): string {
  return new Date(Date.parse(snapshot.weekOneMonday + 'T00:00:00Z') + ((week - 1) * 7 + weekday) * 86400000)
    .toISOString().slice(0, 10);
}

export function currentWeek(snapshot: ScheduleSnapshot): number {
  const today = new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10);
  return Math.max(1, Math.min(30, Math.floor((Date.parse(today) - Date.parse(snapshot.weekOneMonday)) / 604800000) + 1));
}

export function validSnapshot(value: ScheduleSnapshot): boolean {
  if (!value || !/^20\d{4}$/.test(value.termCode) || !/^\d{4}-\d{2}-\d{2}$/.test(value.weekOneMonday) ||
    !Number.isFinite(Date.parse(value.retrievedAt)) || !Array.isArray(value.lessons) || value.lessons.length < 1 ||
    value.lessons.length > 100000 || !Array.isArray(value.periods) || value.periods.length !== 14 || value.source !== 'school') return false;
  if (new Date(value.weekOneMonday + 'T00:00:00Z').getUTCDay() !== 1) return false;
  for (let i = 0; i < value.periods.length; i++) {
    const period = value.periods[i];
    if (period.section !== i + 1 || !/^([01]\d|2[0-3]):[0-5]\d$/.test(period.start) ||
      !/^([01]\d|2[0-3]):[0-5]\d$/.test(period.end) || period.start >= period.end) return false;
  }
  for (const lesson of value.lessons) {
    if (typeof lesson.name !== 'string' || !lesson.name.trim() || typeof lesson.kind !== 'string' ||
      typeof lesson.location !== 'string' || typeof lesson.teachers !== 'string' || typeof lesson.content !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}$/.test(lesson.date) || !Number.isInteger(lesson.week) || lesson.week < 1 || lesson.week > 30 ||
      !Array.isArray(lesson.sections) || lesson.sections.length === 0 ||
      lesson.sections.some((section: number, i: number) => !Number.isInteger(section) || section < 1 || section > 14 || i > 0 && section <= lesson.sections[i - 1])) return false;
    const weekday = (Date.parse(lesson.date) - Date.parse(value.weekOneMonday)) / 86400000 - (lesson.week - 1) * 7;
    if (!Number.isInteger(weekday) || weekday < 0 || weekday > 6) return false;
  }
  return true;
}
