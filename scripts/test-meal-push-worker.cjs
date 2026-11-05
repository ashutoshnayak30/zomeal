// Run with Node 24 (built-in TypeScript stripping); no network or live recipients.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {stripTypeScriptTypes}=require('node:module');
const {webcrypto,generateKeyPairSync}=require('node:crypto');
const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048});
let handler;const completed=[],sent=[];
const db={rpc:async(name,args)=>{
 if(name==='authorize_meal_push_dispatch')return {data:args.dispatch_secret==='test-secret'};
 if(name==='claim_meal_push_batch')return {data:['ok','invalid','retry',null].map((token,i)=>({id:String(i),lease_id:'lease',notification_id:'notification-'+i,token,title:'Lunch payment deducted',body:'₹75.50 deducted. Wallet balance: ₹924.50.',destination:'wallet'}))};
 if(name==='finish_meal_push'){completed.push(args);return {};}
 throw Error(name);
}};
const context={serviceClient:()=>db,Response,TextEncoder,Uint8Array,btoa,atob,URLSearchParams,AbortSignal,crypto:webcrypto,
 Deno:{env:{get:()=>JSON.stringify({project_id:'test-only',client_email:'test@example.invalid',private_key:privateKey.export({type:'pkcs8',format:'pem'})})},serve:fn=>handler=fn},
 fetch:async(url,options)=>{
  if(url.includes('oauth2'))return Response.json({access_token:'test'});
  const message=JSON.parse(options.body).message;sent.push(message);
  if(message.token==='ok')return Response.json({name:'accepted'});
  if(message.token==='invalid')return Response.json({error:{status:'NOT_FOUND',details:[{errorCode:'UNREGISTERED'}]}},{status:404});
  return Response.json({error:{status:'UNAVAILABLE'}},{status:503});
 }};
vm.runInNewContext(stripTypeScriptTypes(fs.readFileSync('supabase/functions/dispatch-meal-push/index.ts','utf8').replace(/^import .*;\r?\n/,'')),context);
(async()=>{
 assert.equal((await handler(new Request('https://local',{method:'GET'}))).status,405);
 assert.equal((await handler(new Request('https://local',{method:'POST'}))).status,401);
 assert.equal((await handler(new Request('https://local',{method:'POST',headers:{'x-meal-dispatch-secret':'wrong'}}))).status,401);
 assert.equal(completed.length,0);
 const response=await handler(new Request('https://local',{method:'POST',headers:{'x-meal-dispatch-secret':'test-secret'}}));
 assert.equal(response.status,200);assert.equal(completed.length,4);
 assert.equal(completed.find(x=>x.target_id==='1').invalid_token,true);
 assert.equal(completed.find(x=>x.target_id==='2').delivered,false);
 assert.equal(sent[0].data.destination,'wallet');assert.equal(sent[0].data.notification_id,'notification-0');
 assert.equal(sent[0].notification,undefined,'Customer pushes must reach the branded local renderer in background');
 assert.equal(sent[0].data.body,'₹75.50 deducted. Wallet balance: ₹924.50.');
 const home=fs.readFileSync('app/src/main/java/com/zomeal/app/MainActivity.kt','utf8');
 assert.match(home,/showingTomorrowMenu = homeHour >= 22/);
 assert.match(home,/60_000L - System.currentTimeMillis\(\) % 60_000L/);
 for(const [utc,expected] of [['2026-09-21T16:29:59Z',false],['2026-09-21T16:30:00Z',true],['2026-09-21T18:29:59Z',true],['2026-09-21T18:30:00Z',false]]){
  const hour=Number(new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Kolkata',hour:'2-digit',hourCycle:'h23'}).format(new Date(utc)));
  assert.equal(hour>=22,expected);
 }
 console.log('PASS: worker authorization, FCM payloads, invalid/transient outcomes, stable notification IDs; home rollover rule at 22:00/midnight IST');
})().catch(e=>{console.error(e);process.exitCode=1;});
