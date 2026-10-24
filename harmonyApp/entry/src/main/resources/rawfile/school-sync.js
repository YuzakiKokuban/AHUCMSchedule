/* Runs only in the authenticated school's main frame, never in the local parser. */
(function () {
  'use strict';
  const origin = 'https://jwxt.ahtcm.edu.cn';
  const job = window.__ahuJob;
  window.__ahuResult = { job, state: 'pending' };
  (async () => {
    if (location.origin !== origin || top !== window) throw new Error('请先在学校网页完成登录');
    const read = async (path, post, referer) => {
      const url = new URL(path, origin);
      if (url.origin !== origin || !url.pathname.startsWith('/new/') || url.username || url.password) throw new Error('学校请求地址异常');
      const abort = new AbortController(), timeout = setTimeout(() => abort.abort(), 30000);
      try {
        const response = await fetch(url.href, { method: post === undefined ? 'GET' : 'POST', body: post,
          credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal: abort.signal,
          referrer: origin + (referer || '/new/welcome.page?ui=new'),
          headers: post === undefined ? {} : { 'Content-Type': 'application/x-www-form-urlencoded' } });
        if (!response.ok || response.url && new URL(response.url).origin !== origin) throw new Error('学校会话已失效或服务不可用');
        const chunks = [], reader = response.body.getReader();
        let size = 0;
        while (true) {
          const part = await reader.read();
          if (part.done) break;
          size += part.value.length;
          if (size > 20 * 1024 * 1024) { await reader.cancel(); throw new Error('学校返回数据过大'); }
          chunks.push(part.value);
        }
        const bytes = new Uint8Array(size);
        let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
        return bytes;
      } finally { clearTimeout(timeout); }
    };
    const text = async (path, post, referer) => new TextDecoder('utf-8', { fatal: true }).decode(await read(path, post, referer));
    const menus = await text('/new/desktop/getMenu', '');
    if (!menus.includes('xsgrkb/main.page')) throw new Error('未取得课表权限，请重新登录');
    const main = await text('/new/student/xsgrkb/main.page');
    const match = /\/new\/student\/xsgrkb\/week\.page\?xnxqdm=(20\d{4})/.exec(main);
    if (!match) throw new Error('学校课表页面已变化或登录已失效');
    const termCode = match[1], weekPath = '/new/student/xsgrkb/week.page?xnxqdm=' + termCode;
    const html = await text(weekPath);
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const selected = doc.querySelector('select#xnxqdm option[selected]');
    if (!selected || selected.value !== termCode) throw new Error('学校返回学期不一致');
    const hours = /businessHours\s*=\s*\$\.parseJSON\(\s*'([^']*)'/s.exec(html);
    if (!hours) throw new Error('未取得完整学校作息');
    const periods = JSON.parse(hours[1]).map(p => ({ section: +p.jcdm, start: p.qssj.slice(0,5), end: p.jssj.slice(0,5) }));
    const query = new URLSearchParams({ xnxqdm: termCode, xnxqmc: selected.textContent, zc: '', expname: 'download' });
    const bytes = await read('/new/printkb/prtxskb?' + query, undefined, weekPath);
    let binary = '';
    for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    const notices = [], ids = new Set();
    let noticesError = '';
    try {
      await text('/new/notice/notice.page');
      for (const endpoint of ['getNotRead', 'getRead']) {
        let received = 0;
        for (let page = 1; ; page++) {
          if (page > 50) throw new Error('学校通知未完整返回');
          const data = JSON.parse(await text('/new/notice/' + endpoint, `page=${page}&rows=2000`, '/new/notice/notice.page'));
          if (!Array.isArray(data.rows) || !Number.isSafeInteger(data.total) || data.total < 0) throw new Error('学校通知格式变化');
          for (const row of data.rows) {
            if (!['id','msg','cjsj'].every(key => typeof row[key] === 'string' || key === 'id' && typeof row[key] === 'number')) throw new Error('学校通知字段缺失');
            if (ids.has(String(row.id))) continue;
            ids.add(String(row.id));
            const body = new DOMParser().parseFromString(row.msg, 'text/html');
            body.querySelectorAll('script,style').forEach(node => node.remove());
            notices.push({ id: String(row.id), text: body.body.textContent.trim(), publishedAt: row.cjsj });
          }
          received += data.rows.length;
          if (received >= data.total) break;
          if (!data.rows.length) throw new Error('学校通知未完整返回');
        }
      }
    } catch (_) { noticesError = '课表已获取，通知未能完整同步；保留上次通知'; }
    window.__ahuResult = { job, state: 'done', payload: { termCode, xlsBase64: btoa(binary), periods }, notices, noticesError };
  })().catch(() => { window.__ahuResult = { job, state: 'error', message: '学校同步未完成，请检查网络、网页登录及教务访问权限；原课表保留' }; });
})();
