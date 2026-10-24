/* 岐黄课表 HarmonyOS prototype. Keep field/validation parity with core's XLS importer. */
(function (root) {
  'use strict';
  const DAY = 86400000;
  const fail = message => { throw new Error(message); };
  const iso = millis => new Date(millis).toISOString().slice(0, 10);
  function allowedSchoolUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
        ['tpass.ahtcm.edu.cn', 'jwxt.ahtcm.edu.cn'].includes(url.hostname);
    } catch (_) { return false; }
  }
  function parseXls(bytes, XLSX, now = new Date().toISOString()) {
    const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const magic = [208, 207, 17, 224, 161, 177, 26, 225];
    if (data.length < 8 || data.length > 20 * 1024 * 1024 || magic.some((b, i) => data[i] !== b)) fail('请选择学校官方导出的 XLS');
    const book = XLSX.read(data, { type: 'array', cellFormula: false, cellHTML: false, cellText: false, cellStyles: false });
    const sheet = book.Sheets[book.SheetNames[0]];
    if (!sheet || !sheet['!ref']) fail('课表没有工作表');
    const range = XLSX.utils.decode_range(sheet['!ref']);
    if (range.e.r > 10000 || range.e.c > 99) fail('课表行列范围异常');
    const text = (r, c) => {
      const cell = sheet[XLSX.utils.encode_cell({ r, c })];
      if (!cell || cell.t === 'z') return '';
      if (cell.t !== 's' || cell.f) fail('课表单元格格式异常');
      return String(cell.v).replace(/\r\n?/g, '\n');
    };
    let term;
    for (let r = 0; r <= Math.min(4, range.e.r); r++) {
      term = /(20\d{2})\s*-\s*(20\d{2})\s*-\s*([12])\s*课表/.exec(text(r, 0));
      if (term) break;
    }
    if (!term || +term[2] !== +term[1] + 1) fail('无法识别课表学期');
    const year = +term[1], endYear = +term[2], semester = +term[3];
    let monday, lastDate, dates = new Map();
    const lessons = [];
    for (let r = 1; r <= range.e.r; r++) {
      const dateCells = [];
      for (let c = 1; c <= range.e.c; c++) {
        const match = /^(\d{2})-(\d{2})$/.exec(text(r, c).trim());
        if (match) dateCells.push({ c, month: +match[1], day: +match[2] });
      }
      if (!text(r, 0).trim() && dateCells.length) {
        const headers = [];
        for (let c = 1; c <= range.e.c; c++) {
          const match = /^第(\d+)周$/.exec(text(r - 1, c).trim());
          if (match) headers.push({ c, week: +match[1] });
        }
        dates = new Map();
        for (const item of dateCells) {
          const header = headers.filter(h => h.c <= item.c).pop();
          if (!header || header.week < 1 || header.week > 30) fail('课表缺少有效教学周');
          const weekday = item.c - header.c + 1;
          const dateYear = semester === 1 && item.month >= 8 ? year : endYear;
          const ms = Date.UTC(dateYear, item.month - 1, item.day);
          const date = new Date(ms);
          if (date.getUTCFullYear() !== dateYear || date.getUTCMonth() + 1 !== item.month || date.getUTCDate() !== item.day ||
              weekday > 7 || ((date.getUTCDay() + 6) % 7 + 1) !== weekday) fail('课表日期与星期不一致');
          const candidate = ms - ((header.week - 1) * 7 + weekday - 1) * DAY;
          if (new Date(candidate).getUTCDay() !== 1 || (monday !== undefined && monday !== candidate)) fail('课表各周日期不一致');
          monday = candidate;
          lastDate = Math.max(lastDate || ms, ms);
          dates.set(item.c, { date: iso(ms), week: header.week });
        }
        continue;
      }
      if (!/^第[一二三四五六七八九十0-9]+大?节$/.test(text(r, 0).trim())) continue;
      for (let c = 1; c <= range.e.c; c++) {
        const value = text(r, c).replace(/^\n+|\n+$/g, '');
        if (!value.trim()) continue;
        const lines = value.split('\n');
        const times = lines.map((line, i) => /^(\d{1,2})-((?:\d{2})+)$/.test(line.trim()) ? i : -1).filter(i => i >= 0);
        const fields = new Set(times.flatMap(i => [i - 1, i + 1]));
        const headers = lines.map((line, i) => !fields.has(i) && /^(.+)\[([^\]\n]+)\]$/.test(line.trim()) ? i : -1).filter(i => i >= 0);
        if (!times.length || JSON.stringify(headers) !== JSON.stringify(times.map(i => i - 2))) fail('课程信息不完整，原课表保留');
        times.forEach((i, index) => {
          const course = /^(.+)\[([^\]\n]+)\]$/.exec((lines[i - 2] || '').trim());
          const section = /^(\d{1,2})-((?:\d{2})+)$/.exec(lines[i].trim());
          const sections = section[2].match(/\d{2}/g).map(Number);
          const date = dates.get(c);
          if (!course || !date || date.week !== +section[1] || sections.some((n, j) => n < 1 || n > 14 || (j > 0 && n <= sections[j - 1]))) fail('课程周次或节次异常');
          const next = index + 1 < times.length ? times[index + 1] - 2 : lines.length;
          lessons.push({ name: course[1].trim(), kind: course[2], teachers: lines[i - 1].trim(), location: (lines[i + 1] || '').trim(),
            content: lines.slice(Math.min(i + 2, lines.length), next).join('\n').trim(), date: date.date, week: date.week, sections });
        });
      }
    }
    if (!lessons.length || monday === undefined || lastDate === undefined) fail('没有可验证的课程，原课表保留');
    const groups = new Map();
    for (const lesson of lessons) {
      const key = JSON.stringify({ ...lesson, location: '' });
      const group = groups.get(key) || { lesson, rooms: new Set() };
      if (lesson.location) group.rooms.add(lesson.location);
      groups.set(key, group);
    }
    const merged = Array.from(groups.values()).map(g => ({ ...g.lesson, location: Array.from(g.rooms).sort().join(' / ') }));
    merged.sort((a, b) => a.date.localeCompare(b.date) || a.sections[0] - b.sections[0] || a.name.localeCompare(b.name));
    return { termCode: `${year}0${semester}`, termLabel: `${year}-${endYear} 第${semester}学期`, weekOneMonday: iso(monday),
      retrievedAt: now, calendarEndDate: iso(lastDate), source: 'file', periods: [], lessons: merged };
  }
  function validatePeriods(periods) {
    if (!Array.isArray(periods) || periods.length !== 14) fail('作息时间不完整');
    const ordered = [...periods].sort((a, b) => a.section - b.section);
    ordered.forEach((p, i) => {
      if (p.section !== i + 1 || !/^([01]\d|2[0-3]):[0-5]\d$/.test(p.start) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(p.end) || p.start >= p.end) fail('作息时间格式异常');
    });
    return ordered;
  }
  function parsePeriods(html) {
    const match = /businessHours\s*=\s*\$\.parseJSON\(\s*'([^']*)'/s.exec(html);
    if (!match) fail('未获取到学校作息时间');
    return validatePeriods(JSON.parse(match[1]).map(p => ({ section: +p.jcdm, start: p.qssj.slice(0, 5), end: p.jssj.slice(0, 5) })));
  }
  function parsePayload(payload, XLSX) {
    if (!payload || typeof payload.xlsBase64 !== 'string' || payload.xlsBase64.length > 28 * 1024 * 1024 || !/^20\d{4}$/.test(payload.termCode)) fail('同步数据异常');
    const bytes = Uint8Array.from(atob(payload.xlsBase64), c => c.charCodeAt(0));
    const snapshot = parseXls(bytes, XLSX);
    if (snapshot.termCode !== payload.termCode) fail('学校返回学期不一致，原课表保留');
    snapshot.periods = validatePeriods(payload.periods);
    snapshot.source = 'school';
    return snapshot;
  }
  const api = { allowedSchoolUrl, parseXls, parsePeriods, parsePayload };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.AhuSchedule = api;
})(globalThis);
