const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = '../../main/res/raw/managed_sync.js';
const driver = fs.existsSync(require('node:path').join(__dirname,path)) ? require(path) : {run: async()=>{throw new Error('driver missing')}};
const payload = {owner:'a'.repeat(64),termCode:'202601',revision:'b'.repeat(64),timer:{totalWeek:20,startSemester:1788105600000,startWithSunday:false,showWeekend:true,forenoon:4,afternoon:6,night:4,sections:[{section:1,startTime:'08:30',endTime:'09:10'}]},courses:[{name:'Nursing',teacher:'T',position:'R',day:1,sections:[1],weeks:[1,2]}]};
function fixture({mismatch=false,uncertain=false,other=false}={}) {
  let tables=other?[{id:9,name:'Personal',current:1}]:[], courses=[], setting={id:51,speak:1,sectionTimes:'[]',extend:JSON.stringify({bgSetting:{name:'custom'},showNotInWeek:true})}, writes=[], receipts=[];
  const request=async ({url,method,data})=>{
    if(method!=='GET')writes.push({url,method,data});
    if(url.endsWith('/tables'))return {code:0,data:tables};
    if(url.endsWith('/table')&&method==='POST'){tables.push({id:10,name:data.name,current:0});return {code:0,data:10};}
    if(url.endsWith('/courseInfos')){courses=data.courses;return {code:0,data:courses.map((_,i)=>i+1)};}
    if(url.endsWith('/table')&&method==='PUT'){setting={...data.setting,sectionTimes:data.setting.sections};return {code:0,data:true};}
    if(url.endsWith('/table')&&method==='GET')return {code:0,data:{courses:mismatch?[]:courses,setting}};
    if(url.endsWith('/table_switch')){tables.forEach(t=>t.current=t.id===10?1:0);return {code:0,data:true};}
    throw Error('unexpected request');
  };
  return {request,writes,receipts,checkpoint:async r=>{receipts.push(r);return true;},job:{payload,phase:uncertain?'courses-writing':'accepted',tableId:uncertain?'10':''}};
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
test('uncertain previous mutation is recovered by readback only',async()=>{
 const f=fixture({uncertain:true});const r=await driver.run(f.job,f.request,f.checkpoint);assert.equal(f.writes.length,0);assert.equal(r.state,'pending');
});
test('refuses write if durable checkpoint cannot be saved',async()=>{
 const f=fixture();const r=await driver.run(f.job,f.request,async()=>false);assert.equal(f.writes.length,0);assert.notEqual(r.state,'applied');
});
test('server readback compares section values regardless of JSON key order',()=>{
 const s=driver.settingsFor(payload);s.sectionTimes=JSON.stringify([{e:'09:10',i:1,s:'08:30'}]);
 assert.equal(driver.sameSettings(s,payload),true);
});
test('an unrelated preexisting table with the reserved name is not adopted',async()=>{
 const f=fixture();const request=async q=>q.url.endsWith('/tables')?{code:0,data:[{id:9,name:'AHUCM-'+payload.owner.slice(0,16)+'-'+payload.termCode}]}:f.request(q);
 const r=await driver.run(f.job,request,f.checkpoint);assert.equal(r.state,'failed');assert.equal(f.writes.length,0);
});
test('known managed target must still have its exact identity before replacement',async()=>{
 const f=fixture({other:true});f.job.tableId='9';const r=await driver.run(f.job,f.request,f.checkpoint);
 assert.equal(r.state,'failed');assert.equal(f.writes.length,0);
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
test('cancellation after course write allows only reads and retains uncertainty',async()=>{
 const f=fixture();const checkpoint=async r=>r.phase==='settings-writing'?{allowMutation:false,cancelRequested:true}:true;
 const r=await driver.run(f.job,f.request,checkpoint);
 assert.deepEqual(f.writes.map(w=>w.url),['/course-multi/table','/course-multi/courseInfos']);
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
