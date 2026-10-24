const { test } = require('node:test');
const assert = require('node:assert/strict');
const XLSX = require('../entry/src/main/resources/rawfile/vendor/xlsx.full.min.js');
const core = require('../entry/src/main/resources/rawfile/schedule-core.js');

function book({ empty = false, date = '10-21', sections = '8-1314', room = '实训室4' } = {}) {
  const rows = [ ['匿名2026-2027-1课表'], ['周次\n节次', '第8周'], ['', '10-19', '', date], ['星期'], ['第七大节', '', '', empty ? '' : `护理实验[实验实训]\n示例教师\n${sections}\n${room}\n基础护理实训`] ];
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(rows), 'Sheet1');
  return XLSX.write(workbook, { type: 'buffer', bookType: 'biff8' });
}
test('official BIFF8 XLS maps week 8, Wednesday and sections 13–14', () => {
  const snapshot = core.parseXls(book(), XLSX, '2026-10-23T00:00:00.000Z');
  assert.equal(snapshot.termCode, '202601');
  assert.equal(snapshot.weekOneMonday, '2026-08-31');
  assert.equal(snapshot.lessons[0].date, '2026-10-21');
  assert.deepEqual(snapshot.lessons[0].sections, [13, 14]);
  assert.equal(snapshot.lessons[0].content, '基础护理实训');
});
test('rejects empty table, date disagreement, invalid periods and non-XLS', () => {
  for (const input of [book({empty:true}), book({date:'10-22'}), book({sections:'7-1314'}), book({sections:'8-1413'}), book({sections:'8-1516'}), Buffer.from('<html>login</html>')]) {
    assert.throws(() => core.parseXls(input, XLSX));
  }
});
test('school origin checks reject credential URLs, HTTP and lookalike hosts', () => {
  assert.equal(core.allowedSchoolUrl('https://jwxt.ahtcm.edu.cn/new/welcome.page'), true);
  for (const url of ['http://jwxt.ahtcm.edu.cn', 'https://jwxt.ahtcm.edu.cn.evil.test', 'https://user@jwxt.ahtcm.edu.cn', 'https://jwxt.ahtcm.edu.cn:444/', 'file:///etc/passwd']) assert.equal(core.allowedSchoolUrl(url), false);
});
test('all 14 school periods required and expired school HTML is rejected', () => {
  const periods = Array.from({length:14}, (_,i) => ({jcdm:String(i+1),qssj:'08:30:00',jssj:'09:10:00'}));
  const html = `var businessHours = $.parseJSON('${JSON.stringify(periods)}');`;
  assert.equal(core.parsePeriods(html).length, 14);
  assert.throws(() => core.parsePeriods(html.replace('"14"','"13"')));
  assert.throws(() => core.parsePeriods('登录已过期'));
});
test('online snapshot is accepted only when export term and all school periods match', () => {
  const periods = Array.from({length:14}, (_,i) => ({section:i+1,start:'08:30',end:'09:10'}));
  const payload = {termCode:'202601',xlsBase64:Buffer.from(book()).toString('base64'),periods};
  const snapshot = core.parsePayload(payload, XLSX);
  assert.equal(snapshot.source, 'school');
  assert.equal(snapshot.periods.length, 14);
  assert.throws(() => core.parsePayload({...payload,termCode:'202602'}, XLSX));
  assert.throws(() => core.parsePayload({...payload,periods:periods.slice(1)}, XLSX));
});
