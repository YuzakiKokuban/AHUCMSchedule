const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = '../../main/res/raw/managed_sync.js';
const driver = fs.existsSync(require('node:path').join(__dirname,path)) ? require(path) : {run: async()=>{throw new Error('driver missing')}};
const payload = {owner:'a'.repeat(64),termCode:'202601',revision:'b'.repeat(64),timer:{totalWeek:20,startSemester:1788105600000,startWithSunday:false,showWeekend:true,forenoon:4,afternoon:6,night:4,sections:[{section:1,startTime:'08:30',endTime:'09:10'}]},courses:[{name:'Nursing',teacher:'T',position:'R',day:1,sections:[1],weeks:[1,2]}]};

test('a delayed native ack cannot erase a newer timeout receipt',()=>{
 const report={state:'failed',message:'native_checkpoint_timeout'};
 const box={token:'lease',seq:2,report,ack:0,final:true};
 driver.acknowledge(box,'lease',1,true);
 assert.equal(box.report,report);
 assert.equal(box.ack,1);
 driver.acknowledge(box,'other-lease',2,false);
 assert.equal(box.cancelRequested,undefined);
 driver.acknowledge(box,'lease',2,false);
 assert.equal(box.report,null);
 assert.equal(box.cancelRequested,true);
 assert.equal(box.denied,2);
});
function fixture({mismatch=false,uncertain=false,other=false,existing=false,taken=false,dropFirst=0,requireCurrent=false,resetWeek=false}={}) {
  const managedName='AHUCM-'+payload.owner.slice(0,16)+'-'+payload.termCode;
  let tables=other||existing?[{id:9,name:other?'Personal':'AHUCM-old',current:1}]:[];
  if(existing)tables=[{id:10,name:managedName,current:1}];
  if(taken)tables=[{id:11,name:managedName,current:1}];
  let courses=[], dropped=dropFirst, setting={id:51,speak:1,sectionTimes:'[]',extend:JSON.stringify({bgSetting:{name:'custom'},showNotInWeek:true})}, writes=[], receipts=[];
  const request=async ({url,method,data})=>{
    if(method!=='GET')writes.push({url,method,data});
    if(url.endsWith('/tables'))return {code:0,data:tables};
    if(url.endsWith('/table')&&method==='POST'){tables.push({id:10,name:data.name,current:0});return {code:0,data:10};}
    if(url.endsWith('/courseInfos')){
      if(requireCurrent&&!tables.some(t=>t.id===data.ctId&&t.current===1))return {code:0,data:[]};
      if(dropped>0){dropped--;return {code:0,data:[]};}
      if(resetWeek)setting={...setting,presentWeek:1};
      courses=data.courses;return {code:0,data:courses.map((_,i)=>i+1)};
    }
    if(url.endsWith('/table')&&method==='PUT'){setting={...data.setting,sectionTimes:data.setting.sections};return {code:0,data:true};}
    if(url.endsWith('/table')&&method==='GET')return {code:0,data:{courses:mismatch?[]:courses,setting}};
    if(url.endsWith('/table_switch')){tables.forEach(t=>t.current=t.id===10?1:0);return {code:0,data:true};}
    throw Error('unexpected request');
  };
  return {request,writes,receipts,checkpoint:async r=>{receipts.push(r);return true;},job:{payload,phase:uncertain?'courses-writing':existing||taken?'verifying':'accepted',tableId:uncertain||existing||taken?'10':''}};
}
test('writes only dedicated table and reports applied after course/settings readback',async()=>{
 const f=fixture({other:true});const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'applied');assert.equal(r.courseCount,1);
 assert.equal(f.writes.some(w=>w.method==='DELETE'),false);
 assert.equal(f.writes.filter(w=>w.data.ctId).every(w=>String(w.data.ctId)==='10'),true);
 assert.ok(f.receipts.some(r=>r.phase==='courses-writing'));
});
test('readback mismatch never reports applied',async()=>{
 const f=fixture({mismatch:true});const r=await driver.run(f.job,f.request,f.checkpoint);assert.notEqual(r.state,'applied');
});

test('an unchanged school revision restores a course deleted from the managed host table',async()=>{
 const f=fixture();
 assert.equal((await driver.run(f.job,f.request,f.checkpoint)).state,'applied');
 const detail=await f.request({url:'/course-multi/table',method:'GET',data:{ctId:10}});
 detail.data.courses.splice(0,1);
 f.writes.length=0;
 const result=await driver.run({...f.job,tableId:'10',phase:'verifying'},f.request,f.checkpoint);
 assert.equal(result.state,'applied');
 assert.ok(f.writes.some(w=>w.url.endsWith('/courseInfos')));
 assert.equal(f.writes.some(w=>w.method==='POST'&&w.url.endsWith('/table')),false);
 const repaired=await f.request({url:'/course-multi/table',method:'GET',data:{ctId:10}});
 assert.equal(driver.sameCourses(repaired.data.courses,payload.courses),true);
});
test('an interrupted mutation repairs its bound table instead of waiting forever',async()=>{
 const f=fixture({uncertain:true});const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'applied');
 assert.ok(f.writes.some(w=>w.method==='POST'&&w.url.endsWith('/table')));
});
test('refuses write if durable checkpoint cannot be saved',async()=>{
 const f=fixture();const r=await driver.run(f.job,f.request,async()=>false);assert.equal(f.writes.length,0);assert.notEqual(r.state,'applied');
});
test('server readback compares section values regardless of JSON key order',()=>{
 const s=driver.settingsFor(payload);s.sectionTimes=JSON.stringify([{e:'09:10',i:1,s:'08:30'}]);
 assert.equal(driver.sameSettings(s,payload),true);
});

test('readback rejects a stale current week and the semester date used by the host UI',()=>{
 const s=driver.settingsFor(payload);
 assert.equal(driver.sameSettings({...s,presentWeek:s.presentWeek-1},payload),false);
 assert.equal(driver.sameSettings({...s,extend:JSON.stringify({startSemester:0})},payload),false);
});

test('current week changes at Shanghai Monday midnight and follows host term boundaries',()=>{
 const now=Date.now;
 try {
  for(const [date,week] of [['2026-08-30T23:59:59+08:00',1],['2026-08-31T00:00:00+08:00',1],['2026-10-25T23:59:59+08:00',8],['2026-10-26T00:00:00+08:00',9],['2027-08-01T00:00:00+08:00',21]]) {
   Date.now=()=>Date.parse(date);
   assert.equal(driver.settingsFor(payload).presentWeek,week,date);
  }
 }finally{Date.now=now;}
});

test('settings silently ignored by the server cannot be acknowledged as applied',async()=>{
 const f=fixture();
 const request=async q=>{
  const r=await f.request(q);
  if(q.method==='GET'&&q.url.endsWith('/table')&&r.data.courses.length)r.data={...r.data,setting:{...r.data.setting,presentWeek:1}};
  return r;
 };
 assert.notEqual((await driver.run(f.job,request,f.checkpoint)).state,'applied');
});

test('rechecking a verified table repairs changed times and week without creating another table',async()=>{
 const f=fixture();
 assert.equal((await driver.run(f.job,f.request,f.checkpoint)).state,'applied');
 f.writes.length=0;
 f.job={...f.job,tableId:'10',phase:'verifying'};
 assert.equal((await driver.run(f.job,f.request,f.checkpoint)).state,'applied');
 assert.equal(f.writes.length,0);
 let corrupt=true;
 const request=async q=>{
  if(q.method==='PUT')corrupt=false;
  const r=await f.request(q);
  if(corrupt&&q.method==='GET'&&q.url.endsWith('/table'))r.data={...r.data,setting:{...r.data.setting,presentWeek:0,sectionTimes:'[{"i":1,"s":"07:00","e":"07:40"}]'}};
  return r;
 };
 assert.equal((await driver.run(f.job,request,f.checkpoint)).state,'applied');
 assert.equal(f.writes.filter(w=>w.method==='POST'&&w.url.endsWith('/table')).length,0);
 assert.ok(f.writes.some(w=>w.method==='PUT'));
});
test('an unrelated preexisting table with the reserved name is not adopted',async()=>{
 const f=fixture();const request=async q=>q.url.endsWith('/tables')?{code:0,data:[{id:9,name:'AHUCM-'+payload.owner.slice(0,16)+'-'+payload.termCode}]}:f.request(q);
 const r=await driver.run(f.job,request,f.checkpoint);assert.equal(r.state,'failed');assert.equal(f.writes.length,0);
});
test('a bound target that no longer matches is replaced without writing to it',async()=>{
 const f=fixture({other:true});f.job.tableId='9';const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'applied');
 assert.ok(f.writes.length>0);
 assert.equal(f.writes.every(w=>w.data.ctId===undefined||String(w.data.ctId)!=='9'),true);
});
test('a reserved name owned by another table is never adopted or duplicated',async()=>{
 const f=fixture({taken:true});const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'failed');
 assert.equal(r.message,'managed_table_identity_changed');
 assert.equal(f.writes.length,0);
});
test('ambiguous API response is never retried or reported as applied',async()=>{
 const f=fixture();let creates=0;const request=async q=>{
  if(q.url.endsWith('/table')&&q.method==='POST'){creates++;return {status:'error'};}return f.request(q);
 };
 const r=await driver.run(f.job,request,f.checkpoint);assert.equal(creates,1);assert.equal(r.state,'pending');
});
test('preserves the server assigned settings row id in table update',async()=>{
 const f=fixture();await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(f.writes.find(w=>w.method==='PUT').data.setting.id,51);
});
test('course replacement preserves user reminder and appearance preferences',async()=>{
 const f=fixture();await driver.run(f.job,f.request,f.checkpoint);
 const s=f.writes.find(w=>w.method==='PUT').data.setting;
 assert.equal(s.speak,1);assert.equal(JSON.parse(s.extend).bgSetting.name,'custom');
});
test('uses official settings write fields rather than replaying stale read-only sectionTimes',async()=>{
 const f=fixture();await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(f.writes.find(w=>w.method==='PUT').data.setting.sectionTimes,undefined);
});
test('batch import sends valid style JSON with the official color and background fields',async()=>{
 const f=fixture();
 const request=async q=>{
  if(q.url.endsWith('/courseInfos'))for(const course of q.data.courses){
   const style=JSON.parse(course.style);
   assert.match(style.color,/^#[0-9A-Fa-f]{6}$/);
   assert.match(style.background,/^#[0-9A-Fa-f]{6}$/);
  }
  return f.request(q);
 };
 const r=await driver.run(f.job,request,f.checkpoint);
 assert.equal(r.state,'applied');
});
test('JSON write bodies use numeric table identifiers as the official API requires',async()=>{
 const f=fixture({other:true});
 const request=async q=>{
  if(q.method!=='GET')for(const key of ['ctId','toCtId','fromCtId'])if(key in q.data){
   assert.equal(typeof q.data[key],'number');assert.ok(Number.isSafeInteger(q.data[key]));
  }
  return f.request(q);
 };
 const r=await driver.run(f.job,request,f.checkpoint);
 assert.equal(r.state,'applied');
});
test('different course names get random readable colors while repeated names share one color',async()=>{
 const f=fixture();f.job.payload={...payload,courses:[payload.courses[0],{...payload.courses[0],weeks:[3]},{...payload.courses[0],name:'Anatomy'}]};
 const random=Math.random;let seed=0;Math.random=()=>((++seed)*0.173)%1;
 try{
  assert.equal((await driver.run(f.job,f.request,f.checkpoint)).state,'applied');
  const courses=f.writes.find(w=>w.url.endsWith('/courseInfos')).data.courses;
  assert.equal(courses[0].style,courses[1].style);assert.notEqual(courses[0].style,courses[2].style);
  const luminance=hex=>{const rgb=hex.slice(1).match(/../g).map(v=>parseInt(v,16)/255).map(v=>v<=0.04045?v/12.92:((v+0.055)/1.055)**2.4);return rgb[0]*0.2126+rgb[1]*0.7152+rgb[2]*0.0722;};
  for(const c of courses){const s=JSON.parse(c.style);assert.ok((luminance(s.background)+0.05)/(luminance(s.color)+0.05)>=4.5);}
 }finally{Math.random=random;}
});
test('a subsequent overwrite preserves valid existing colors for the same course name',async()=>{
 const f=fixture();const style=JSON.stringify({color:'#34275B',background:'#F5F0FC'});let reads=0;
 const request=async q=>{
  if(q.url.endsWith('/table')&&q.method==='GET'&&reads++===0)return {code:0,data:{courses:[{...payload.courses[0],style}],setting:{id:51,extend:'{}'}}};
  return f.request(q);
 };
 assert.equal((await driver.run(f.job,request,f.checkpoint)).state,'applied');
 assert.deepEqual(JSON.parse(f.writes.find(w=>w.url.endsWith('/courseInfos')).data.courses[0].style),JSON.parse(style));
});
test('the first random-color update migrates the old all-blue imported courses',async()=>{
 const f=fixture();const style=JSON.stringify({color:'#00A6F2',background:'#E5F4FF'});let reads=0;
 const request=async q=>{
  if(q.url.endsWith('/table')&&q.method==='GET'&&reads++===0)return {code:0,data:{courses:[{...payload.courses[0],style}],setting:{id:51,extend:'{}'}}};
  return f.request(q);
 };
 assert.equal((await driver.run(f.job,request,f.checkpoint)).state,'applied');
 assert.notEqual(f.writes.find(w=>w.url.endsWith('/courseInfos')).data.courses[0].style,style);
 assert.equal(JSON.parse(f.writes.find(w=>w.method==='PUT').data.setting.extend).ahuRandomColors,1);
});
test('server readback must match the assigned course colors before reporting success',async()=>{
 const f=fixture();
 const request=async q=>{
  const r=await f.request(q);
  if(q.method==='GET'&&q.url.endsWith('/table')&&r.data.courses.length)r.data={...r.data,courses:r.data.courses.map(c=>({...c,style:JSON.stringify({color:'#111111',background:'#FFFFFF'})}))};
  return r;
 };
 assert.notEqual((await driver.run(f.job,request,f.checkpoint)).state,'applied');
});
test('canceled queued job performs no network mutations',async()=>{
 const f=fixture();f.job.cancelRequested=true;
 const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(f.writes.length,0);assert.equal(r.state,'failed');assert.equal(r.phase,'canceled');
});
test('cancellation at first native checkpoint stops table creation',async()=>{
 const f=fixture();const r=await driver.run(f.job,f.request,async()=>({allowMutation:false,cancelRequested:true}));
 assert.equal(f.writes.length,0);assert.equal(r.state,'failed');assert.equal(r.cancelRequested,true);
});
test('selects the target before importing and writes settings after the host resets the week',async()=>{
 const f=fixture({requireCurrent:true,resetWeek:true});const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'applied');
 const order=f.writes.map(w=>`${w.method} ${w.url.split('/').pop()}`);
 assert.ok(order.indexOf('PUT table')>=0&&order.indexOf('POST courseInfos')>=0);
 assert.ok(order.indexOf('POST table_switch')<order.indexOf('POST courseInfos'),`target must be current before courses: ${order}`);
 assert.ok(order.indexOf('POST courseInfos')<order.indexOf('PUT table'),`settings must follow course import: ${order}`);
});
test('a silently dropped first course batch is rewritten once the target is selected',async()=>{
 const f=fixture({dropFirst:1});const r=await driver.run(f.job,f.request,f.checkpoint);
 const posts=f.writes.filter(w=>w.url.endsWith('/courseInfos'));
 assert.equal(r.state,'applied');
 assert.equal(posts.length,2);
 assert.deepEqual(posts[1].data,posts[0].data);
 assert.equal(f.writes.filter(w=>w.method==='PUT'&&w.url.endsWith('/table')).length,1);
 assert.equal(f.writes.filter(w=>w.url.endsWith('/table_switch')).length,1);
 const order=f.writes.map(w=>`${w.method} ${w.url.split('/').pop()}`);
 assert.ok(order.indexOf('POST table_switch')<order.indexOf('POST courseInfos'));
});
test('cancellation between a dropped batch and its replay stops the replay',async()=>{
 const f=fixture({dropFirst:1});let courseCheckpoints=0;
 const checkpoint=async r=>{
  if(r.phase==='courses-writing'&&++courseCheckpoints===2)return {allowMutation:false,cancelRequested:true};
  return true;
 };
 const r=await driver.run(f.job,f.request,checkpoint);
 assert.equal(f.writes.filter(w=>w.url.endsWith('/courseInfos')).length,1);
 assert.equal(r.state,'pending');assert.equal(r.cancelRequested,true);
});
test('a wedged verifier repairs the bound table instead of staying pending',async()=>{
 const f=fixture({existing:true});const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'applied');assert.equal(r.phase,'verified');
 assert.ok(f.writes.some(w=>w.url.endsWith('/courseInfos')));
});
test('an unrecoverable course write becomes failed/accepted instead of looping pending',async()=>{
 const f=fixture({existing:true,mismatch:true});const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'failed');
 assert.equal(r.phase,'accepted');
 assert.equal(r.message,'course_write_not_persisted');
 assert.equal(f.writes.filter(w=>w.url.endsWith('/courseInfos')).length,2);
 assert.equal(f.writes.filter(w=>w.method==='PUT'&&w.url.endsWith('/table')).length,0);
 assert.equal(f.writes.filter(w=>w.url.endsWith('/table_switch')).length,1);
});
test('a wedged verifier still never writes while cancellation is requested',async()=>{
 const f=fixture({existing:true});f.job.cancelRequested=true;
 const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(f.writes.length,0);assert.equal(r.state,'pending');assert.equal(r.cancelRequested,true);
});
test('cancellation after course write allows only reads and retains uncertainty',async()=>{
 const f=fixture();const checkpoint=async r=>r.phase==='settings-writing'?{allowMutation:false,cancelRequested:true}:true;
 const r=await driver.run(f.job,f.request,checkpoint);
 assert.deepEqual(f.writes.map(w=>`${w.method} ${w.url.split('/').pop()}`),['POST table','POST table_switch','POST courseInfos']);
 assert.equal(r.state,'pending');assert.equal(r.cancelRequested,true);
});
test('canceled interrupted job is readback-only',async()=>{
 const f=fixture({uncertain:true});f.job.cancelRequested=true;
 const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(f.writes.length,0);assert.equal(r.state,'pending');assert.equal(r.cancelRequested,true);
});
test('cancellation after the last authorized write verifies completion without rollback',async()=>{
 const f=fixture();const checkpoint=async r=>r.phase==='verifying'?{allowMutation:false,cancelRequested:true}:true;
 const r=await driver.run(f.job,f.request,checkpoint);
 assert.equal(f.writes.length,4);assert.equal(r.state,'applied');assert.equal(r.cancelRequested,true);
 assert.equal(f.writes.some(w=>w.method==='DELETE'),false);
});
