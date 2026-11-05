// Execute the real handler with mocked Auth/database boundaries; no live accounts modified.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { stripTypeScriptTypes } = require('node:module');
const source = stripTypeScriptTypes(fs.readFileSync('supabase/functions/admin-staff-management/index.ts', 'utf8').replace(/^import .*;\r?\n/gm, ''));
async function run({token='valid',role=null,title=null,user={id:'caller',email:'admin@example.test',email_confirmed_at:'2026-10-01'},authError=null,roleError=null,action='list',method='POST',invitation=null}={}) {
 const calls=[];let handler;
 const client={auth:{getUser:async t=>{calls.push(['getUser',t]);return {data:{user:token==='expired'?null:user},error:token==='expired'?new Error('Expired'):authError};},admin:{listUsers:async()=>{calls.push(['listUsers']);return {data:{users:[]},error:null};}}},from(table){
  calls.push(['from',table]);let single=false,verb='select';
  const q={};for(const m of ['select','eq','in','gt','order','limit','insert','upsert','update','delete'])q[m]=(...args)=>{if(['insert','upsert','update','delete'].includes(m)){verb=m;calls.push([m,table]);}return q;};
  q.maybeSingle=()=>{single=true;return q;};q.single=q.maybeSingle;
  q.then=(resolve,reject)=>Promise.resolve({data:table==='user_roles'&&single?(role==='ADMIN'?{role}:null):table==='admin_staff_profiles'&&single?(title?{staff_role:title}:null):table==='admin_staff_invitations'&&single?invitation:[],error:table==='user_roles'?roleError:null}).then(resolve,reject);
  return q;
 }};
 vm.runInNewContext(source,{Deno:{serve:f=>handler=f},serviceClient:()=>client,Response,Date,Map,Set,Error,corsHeaders:{},jsonResponse:(body,status=200)=>new Response(JSON.stringify(body),{status}),errorMessage:e=>e.message});
 const headers={'Content-Type':'application/json'};if(token!==null)headers.Authorization=token==='malformed'?'Basic invalid':`Bearer ${token}`;
 const response=await handler(new Request('https://example.test/admin-staff-management',{method,headers,...(method==='POST'?{body:JSON.stringify({action,user_id:'other-user'})}:{})}));
 return {status:response.status,calls};
}
(async()=>{
 for(const token of [null,'malformed','expired']){const r=await run({token});assert.equal(r.status,401);assert.ok(!r.calls.some(c=>c[0]==='from'||c[0]==='listUsers'));}
 for(const user of [null,{id:'anon',is_anonymous:true}]){const r=await run({user});assert.equal(r.status,401);assert.ok(!r.calls.some(c=>c[0]==='from'));}
 assert.equal((await run({authError:new Error('Invalid signature')})).status,401);
 for(const role of [null,'CUSTOMER','PROVIDER','OPERATIONS','FINANCE']){const r=await run({role,user:{id:'caller',app_metadata:{role:'ADMIN'},user_metadata:{role:'SUPER_ADMIN'}}});assert.equal(r.status,403);assert.ok(!r.calls.some(c=>c[0]==='listUsers'));}
 for(const title of ['ADMINISTRATOR','SUPER_ADMIN']){const r=await run({role:'ADMIN',title});assert.equal(r.status,200);assert.equal(r.calls.filter(c=>c[0]==='listUsers').length,1);assert.equal(r.calls[0][0],'getUser');assert.ok(r.calls.findIndex(c=>c[1]==='user_roles')<r.calls.findIndex(c=>c[0]==='listUsers'));}
 const error=await run({role:'ADMIN',roleError:new Error('Database unavailable')});assert.ok(error.status>=400);assert.ok(!error.calls.some(c=>c[0]==='listUsers'));
 for(const action of ['invite','change_role','revoke','cancel_invite']){const r=await run({role:'ADMIN',title:'ADMINISTRATOR',action});assert.equal(r.status,403);assert.ok(!r.calls.some(c=>['insert','update','delete','upsert'].includes(c[0])));}
 assert.equal((await run({role:'ADMIN',title:'SUPER_ADMIN',action:'revoke'})).status,200);
 const unverified=await run({action:'finalize_otp',user:{id:'caller',email:'admin@example.test'}});assert.equal(unverified.status,403);assert.ok(!unverified.calls.some(c=>c[0]==='from'));
 assert.equal((await run({action:'finalize_otp'})).status,403);
 assert.equal((await run({action:'finalize_otp',invitation:{id:'invite',role:'OPERATIONS',staff_role:'OPERATIONS_MANAGER',invited_by:'owner'}})).status,200);
 assert.equal((await run({token:null,method:'OPTIONS'})).status,200);
 assert.equal((await run({method:'GET'})).status,405);
 console.log('PASS: anonymous/malformed/expired/invalid sessions, role denials, ADMIN list access, SUPER_ADMIN writes, fail-closed lookup, verified invitation and HTTP method gates');
})().catch(e=>{console.error(e);process.exitCode=1;});
