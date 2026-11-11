/* Official H5 request adapter verified against umi.777d5973.js (2026-10-22).
 * No credentials leave the host. Every mutation requires a durable native ack. */
(function(root) {
 'use strict';
 function acknowledge(box,token,seq,allowMutation) {
  if(!box||box.token!==token)return;
  // A slow Binder call can return after the checkpoint timed out and published
  // a newer final receipt. Only clear the report that was actually persisted.
  if(box.seq===seq)box.report=null;
  if(allowMutation)box.ack=seq;
  else {box.cancelRequested=true;box.denied=seq;}
 }
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
  return {presentWeek:Math.min(t.totalWeek+1,Math.max(1,Math.floor((Date.now()-Number(t.startSemester))/604800000)+1)),totalWeek:t.totalWeek,isWeekend:t.showWeekend?1:0,morningNum:t.forenoon,afternoonNum:t.afternoon,nightNum:t.night,weekStart:t.startWithSunday?7:1,speak:0,startSemester:JSON.stringify(Number(t.startSemester)),school:'{}',extend:JSON.stringify({startSemester:Number(t.startSemester)}),sections:JSON.stringify(t.sections.map(s=>({i:s.section,s:s.startTime,e:s.endTime})))};
 }
 function sameSettings(actual,p) {
  try {
   const wanted=settingsFor(p), rows=JSON.parse(actual.sectionTimes||actual.sections);
   const normalize=items=>items.map(s=>[Number(s.i),s.s,s.e]).sort((a,b)=>a[0]-b[0]);
   const extend=JSON.parse(actual.extend||'{}');
   return ['presentWeek','totalWeek','isWeekend','morningNum','afternoonNum','nightNum','weekStart'].every(k=>Number(actual[k])===Number(wanted[k]))&&Number(extend.startSemester)===Number(p.timer.startSemester)&&Number(String(actual.startSemester).replace(/"/g,''))===Number(p.timer.startSemester)&&JSON.stringify(normalize(rows))===JSON.stringify(normalize(JSON.parse(wanted.sections)));
  } catch(e) {return false;}
 }
 async function run(job,request,checkpoint) {
  const p=job.payload, baseName='AHUCM-'+p.owner.slice(0,16)+'-'+p.termCode;
  let name=baseName;
  const matchesBoundName=tableName=>tableName===baseName||
   (typeof tableName==='string'&&tableName.startsWith(baseName+'-R')&&/^[1-9][0-9]*$/.test(tableName.slice(baseName.length+2)));
  let tableId=job.tableId||'', phase=job.phase||'accepted';
  let expectedStyles=null,verifiedColorCount=0,candidateId='';
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
   if(r&&['host_authorization_unavailable','host_authorization_rejected'].includes(r.ahuTransportError))throw Error(r.ahuTransportError);
   if(!r||Number(r.code)!==0)throw Error('host_api_rejected_or_unknown');return r.data;
  };
  const verify=async expectedName=>{
   if(!tableId)return false;
   const d=await call('GET','table',{ctId:tableId});
   const tables=await call('GET','tables',{});
   const bound=Array.isArray(tables)&&tables.find(t=>String(t.id)===String(tableId)&&(expectedName?t.name===expectedName:matchesBoundName(t.name)));
   if(!bound||Number(bound.current)!==1||!sameCourses(d.courses,p.courses)||!sameSettings(d.setting,p))return false;
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
  const verifiedReport=()=>({...report(),state:'applied',phase:'verified',courseCount:p.courses.length,colorCount:verifiedColorCount,message:'server_readback_verified'});
  // A readback disagreement after an explicit failure is safe to retry only as a
  // new bounded attempt; reporting failed/accepted also lets the app start over.
  const failedReport=message=>({...report(),state:'failed',phase:'accepted',message:String(message||'host_failure').slice(0,160)});
  const coursesPersisted=async()=>{const d=await call('GET','table',{ctId:tableId});return sameCourses(d.courses,p.courses);};
  // The target must be selected before batch import. One bounded replay covers
  // a silent drop, with a durable cancellation checkpoint before either write.
  const writeCourses=async styleFor=>{
   const body={ctId:tableId,courses:p.courses.map(c=>({...c,weeks:c.weeks.join(','),sections:c.sections.join(','),extend:'{}',style:styleFor(c.name),attendTime:0}))};
   await save('courses-writing');
   await call('POST','courseInfos',body);
   if(await coursesPersisted())return true;
   await save('courses-writing');
   await call('POST','courseInfos',body);
   return await coursesPersisted();
  };
  const writeAll=async tables=>{
   const original=await call('GET','table',{ctId:tableId});
   if(!original.setting||!original.setting.id)throw Error('missing_settings_identity');
   const settings={...settingsFor(p),id:original.setting.id};
   settings.speak=original.setting.speak===undefined?0:original.setting.speak;
   settings.school=original.setting.school||'{}';
   settings.extend=JSON.stringify({...JSON.parse(original.setting.extend||'{}'),startSemester:Number(p.timer.startSemester),ahuRandomColors:1});
   const styleFor=courseStyles(original);
   expectedStyles=new Map(p.courses.map(c=>{const s=JSON.parse(styleFor(c.name));return [c.name,JSON.stringify([s.color.toUpperCase(),s.background.toUpperCase()])];}));
   // Match the official importer: inactive tables silently drop batches, and
   // import/selection can reset presentWeek. Save settings after both operations.
   await save('selection-writing');
   const from=tables.find(t=>Number(t.current)===1);
   await call('POST','table_switch',{toCtId:tableId,fromCtId:from?from.id:0});
   if(!await writeCourses(styleFor))throw Error('course_write_not_persisted');
   await save('settings-writing');
   await call('PUT','table',{ctId:tableId,name,setting:settings});
   await save('verifying');
   return await verify();
  };
  const createManagedTable=async()=>{
   await save('table-creating');
   tableId=String(await call('POST','table',{name,current:0}));
   if(!/^\d+$/.test(tableId)||tableId==='0')throw Error('invalid_created_table_id');
   await save('table-created');
  };
  // Keep the durable ID tied to a reserved name. If the bound table was deleted
  // or renamed, a same-name table still needs explicit confirmation of its ID.
  const ensureManagedTable=async tables=>{
   const bound=tableId&&tables.find(t=>String(t.id)===String(tableId)&&matchesBoundName(t.name));
   if(bound){name=bound.name;return tables;}
   if(tables.some(t=>t.name===name))throw Error('managed_table_identity_changed');
   await createManagedTable();
   const refreshed=await call('GET','tables',{});
   if(!Array.isArray(refreshed))throw Error('invalid_table_list');
   return refreshed;
  };
  const attemptWrite=async tables=>{
   if(await writeAll(tables))return verifiedReport();
   return failedReport('server_readback_mismatch');
  };
  try {
   if(cancelRequested)return await canceled();
   // A phase other than accepted means the previous round may have been
   // interrupted. Verified data needs no work. The managed table is owned by the
   // app, so an interrupted or unverified round is repaired by the same idempotent
   // replacement instead of retrying readback forever; cancellation still wins.
   if(phase!=='accepted') {
    if(await verify())return verifiedReport();
    if(cancelRequested)return {...report(),message:'canceled_write_requires_review'};
    // Without a known table the previous create may still be ambiguous.
    if(!tableId)return {...report(),message:'uncertain_previous_write_requires_review'};
    try {
     const tables=await call('GET','tables',{});
     if(!Array.isArray(tables))return {...report(),message:'uncertain_previous_write_requires_review'};
     return await attemptWrite(await ensureManagedTable(tables));
    } catch(e) {
     if(cancelRequested)return await canceled();
     return failedReport(e.message);
    }
   }
   try {
    let tables=await call('GET','tables',{});
    if(!Array.isArray(tables))throw Error('invalid_table_list');
    if(!tableId){
     const matches=tables.filter(t=>t.name===name);
     if(matches.length===1&&/^[1-9][0-9]*$/.test(String(matches[0].id)))candidateId=String(matches[0].id);
     if(job.rebindId){
      if(candidateId!==job.rebindId)throw Error('rebind_target_changed');
      tableId=candidateId;
      if(await verify(baseName))return verifiedReport();
      tableId='';
      tables=await call('GET','tables',{});
      if(!Array.isArray(tables)||tables.filter(t=>t.name===name).length!==1||
       !tables.some(t=>t.name===name&&String(t.id)===candidateId))throw Error('rebind_target_changed');
      tableId=candidateId;
     }else if(job.createBesideId){
      if(candidateId!==job.createBesideId)throw Error('rebind_target_changed');
      const names=new Set(tables.map(t=>t.name));
      let suffix=1;
      while(names.has(baseName+'-R'+suffix))suffix++;
      name=baseName+'-R'+suffix;
     }else if(matches.length)throw Error('unowned_table_name_collision');
    }
    return await attemptWrite(await ensureManagedTable(tables));
   } catch(e) {
    if(cancelRequested)return await canceled();
    // An interrupted mutation stays read-only for this claim; the next claim
    // repairs the bound table. An ambiguous table create has no known target and
    // must not be replayed by guessing.
    return {...report(),candidateId,state:phase==='accepted'?'failed':'pending',message:String(e.message||'host_failure').slice(0,160)};
   }
  } catch(e) {
   if(cancelRequested)return await canceled();
   return {...report(),state:phase==='accepted'?'failed':'pending',message:String(e.message||'host_failure').slice(0,160)};
  }
 }
 const api={run,sameCourses,sameSettings,settingsFor,acknowledge};
 if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.AhuManagedSync=api;
})(typeof window!=='undefined'?window:globalThis);
