/* Official H5 request adapter verified against umi.777d5973.js (2026-10-22).
 * No credentials leave the host. Every mutation requires a durable native ack. */
(function(root) {
 'use strict';
 function numbers(v) { return (Array.isArray(v)?v:String(v||'').split(',')).map(Number).sort((a,b)=>a-b); }
 function canonical(c) { return JSON.stringify([String(c.name||''),String(c.teacher||''),String(c.position||''),Number(c.day),numbers(c.sections),numbers(c.weeks)]); }
 function sameCourses(actual, expected) { return Array.isArray(actual)&&actual.length===expected.length&&JSON.stringify(actual.map(canonical).sort())===JSON.stringify(expected.map(canonical).sort()); }
 function readStyle(value) {
  try {const s=JSON.parse(value);return /^#[0-9a-f]{6}$/i.test(s.color)&&/^#[0-9a-f]{6}$/i.test(s.background)?s:null;}catch(e){return null;}
 }
 function randomStyle() {
  const hue=Math.random()*360;
  const hex=(s,l)=>'#'+[0,8,4].map(n=>{
   const k=(n+hue/30)%12,a=s*Math.min(l,1-l);
   return Math.round(255*(l-a*Math.max(-1,Math.min(k-3,9-k,1)))).toString(16).padStart(2,'0');
  }).join('').toUpperCase();
  return {color:hex(0.70,0.25),background:hex(0.60,0.94)};
 }
 function courseStyles(original) {
  const old=Array.isArray(original.courses)?original.courses:[],colors=new Map();
  const migrated=JSON.parse(original.setting.extend||'{}').ahuRandomColors===1;
  const legacy=!migrated&&old.length>0&&old.every(c=>{const s=readStyle(c.style);return s&&s.color.toUpperCase()==='#00A6F2'&&s.background.toUpperCase()==='#E5F4FF';});
  if(!legacy)for(const c of old){const s=readStyle(c.style);if(s&&!colors.has(c.name))colors.set(c.name,s);}
  return name=>{if(!colors.has(name))colors.set(name,randomStyle());return JSON.stringify(colors.get(name));};
 }
 function settingsFor(p) {
  const t=p.timer;
  return {presentWeek:Math.max(1,Math.floor((Date.now()-Number(t.startSemester))/604800000)+1),totalWeek:t.totalWeek,isWeekend:t.showWeekend?1:0,morningNum:t.forenoon,afternoonNum:t.afternoon,nightNum:t.night,weekStart:t.startWithSunday?7:1,speak:0,startSemester:JSON.stringify(Number(t.startSemester)),school:'{}',extend:JSON.stringify({startSemester:Number(t.startSemester)}),sections:JSON.stringify(t.sections.map(s=>({i:s.section,s:s.startTime,e:s.endTime})))};
 }
 function sameSettings(actual,p) {
  try {
   const wanted=settingsFor(p), rows=JSON.parse(actual.sectionTimes||actual.sections);
   const normalize=items=>items.map(s=>[Number(s.i),s.s,s.e]).sort((a,b)=>a[0]-b[0]);
   return ['totalWeek','isWeekend','morningNum','afternoonNum','nightNum','weekStart'].every(k=>Number(actual[k])===Number(wanted[k]))&&Number(String(actual.startSemester).replace(/"/g,''))===Number(p.timer.startSemester)&&JSON.stringify(normalize(rows))===JSON.stringify(normalize(JSON.parse(wanted.sections)));
  } catch(e) {return false;}
 }
 async function run(job,request,checkpoint) {
  const p=job.payload, name='AHUCM-'+p.owner.slice(0,16)+'-'+p.termCode;
  let tableId=job.tableId||'', phase=job.phase||'accepted';
  let expectedStyles=null,verifiedColorCount=0;
  let cancelRequested=job.cancelRequested===true, hasMutation=phase!=='accepted';
  const report=()=>({state:'pending',phase,tableId,revision:p.revision,cancelRequested});
  const save=async next=>{
   if(cancelRequested)throw Error('management_canceled');
   const ack=await checkpoint({...report(),phase:next});
   if(ack&&ack.cancelRequested)cancelRequested=true;
   if(cancelRequested)throw Error('management_canceled');
   if(ack!==true&&!(ack&&ack.allowMutation===true))throw Error('checkpoint_not_saved');
   phase=next;
  };
  const call=async(method,path,data)=>{
   if(method!=='GET'){
    if(cancelRequested)throw Error('management_canceled');
    // JSON bodies require numeric IDs. Query strings accept the journal's string IDs,
    // but the live batch endpoint rejects those strings with HTTP 500.
    data={...data};
    for(const key of ['ctId','toCtId','fromCtId'])if(key in data){
     const id=Number(data[key]);
     if(!Number.isSafeInteger(id)||id<(key==='fromCtId'?0:1))throw Error('invalid_table_identifier');
     data[key]=id;
    }
    hasMutation=true;
   }
   const r=await request({method,url:'/course-multi/'+path,data},1);
   if(!r||Number(r.code)!==0)throw Error('host_api_rejected_or_unknown');return r.data;
  };
  const verify=async()=>{
   if(!tableId)return false;
   const d=await call('GET','table',{ctId:tableId});
   const tables=await call('GET','tables',{});
   if(!sameCourses(d.courses,p.courses)||!sameSettings(d.setting,p)||!Array.isArray(tables)||!tables.some(t=>String(t.id)===String(tableId)&&t.name===name&&Number(t.current)===1))return false;
   if(JSON.parse(d.setting.extend||'{}').ahuRandomColors!==1)return false;
   const observed=new Map();
   for(const c of d.courses){
    const s=readStyle(c.style);if(!s)return false;
    const color=JSON.stringify([s.color.toUpperCase(),s.background.toUpperCase()]);
    if(observed.has(c.name)&&observed.get(c.name)!==color)return false;
    if(expectedStyles&&expectedStyles.get(c.name)!==color)return false;
    observed.set(c.name,color);
   }
   verifiedColorCount=new Set(observed.values()).size;
   return true;
  };
  const canceled=async()=>{
   if(!hasMutation)return {...report(),state:'failed',phase:'canceled',message:'canceled_before_write'};
   try {
    if(await verify())return {...report(),state:'applied',phase:'verified',courseCount:p.courses.length,colorCount:verifiedColorCount,message:'server_readback_verified'};
   }catch(e){}
   return {...report(),message:'canceled_write_requires_review'};
  };
  try {
   if(cancelRequested)return await canceled();
   // Any interrupted write is read-only until a human resolves it. Never replay it.
   if(phase!=='accepted') {
    if(await verify())return {...report(),state:'applied',phase:'verified',courseCount:p.courses.length,colorCount:verifiedColorCount,message:'server_readback_verified'};
    return {...report(),message:'uncertain_previous_write_requires_review'};
   }
   const tables=await call('GET','tables',{});
   if(!Array.isArray(tables))throw Error('invalid_table_list');
   if(tableId) {
    if(!tables.some(t=>String(t.id)===String(tableId)&&t.name===name))throw Error('managed_table_identity_changed');
   } else {
    if(tables.some(t=>t.name===name))throw Error('unowned_table_name_collision');
    await save('table-creating');
    tableId=String(await call('POST','table',{name,current:0}));
    if(!/^\d+$/.test(tableId)||tableId==='0')throw Error('invalid_created_table_id');
    await save('table-created');
   }
   const original=await call('GET','table',{ctId:tableId});
   if(!original.setting||!original.setting.id)throw Error('missing_settings_identity');
   const settings={...settingsFor(p),id:original.setting.id};
   settings.speak=original.setting.speak===undefined?0:original.setting.speak;
   settings.school=original.setting.school||'{}';
   settings.extend=JSON.stringify({...JSON.parse(original.setting.extend||'{}'),startSemester:Number(p.timer.startSemester),ahuRandomColors:1});
   const styleFor=courseStyles(original);
   expectedStyles=new Map(p.courses.map(c=>{const s=JSON.parse(styleFor(c.name));return [c.name,JSON.stringify([s.color.toUpperCase(),s.background.toUpperCase()])];}));
   await save('courses-writing');
   await call('POST','courseInfos',{ctId:tableId,courses:p.courses.map(c=>({...c,weeks:c.weeks.join(','),sections:c.sections.join(','),extend:'{}',style:styleFor(c.name),attendTime:0}))});
   await save('settings-writing');
   await call('PUT','table',{ctId:tableId,name,setting:settings});
   await save('selection-writing');
   const from=tables.find(t=>Number(t.current)===1);
   await call('POST','table_switch',{toCtId:tableId,fromCtId:from?from.id:0});
   await save('verifying');
   if(!await verify())return {...report(),message:'server_readback_mismatch'};
   return {...report(),state:'applied',phase:'verified',courseCount:p.courses.length,colorCount:verifiedColorCount,message:'server_readback_verified'};
  } catch(e) {
   if(cancelRequested)return await canceled();
   return {...report(),state:phase==='accepted'?'failed':'pending',message:String(e.message||'host_failure').slice(0,160)};
  }
 }
 const api={run,sameCourses,sameSettings,settingsFor};
 if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.AhuManagedSync=api;
})(typeof window!=='undefined'?window:globalThis);
