// Run with Node 24 (built-in TypeScript stripping); no network or live recipients.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {stripTypeScriptTypes}=require('node:module');
const {webcrypto,generateKeyPairSync}=require('node:crypto');
const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048});
let handler;const completed=[],sent=[];
const db={rpc:async(name,args)=>{
 if(name==='authorize_meal_push_dispatch')return {data:args.dispatch_secret==='test-secret'};
 if(name==='claim_campaign_push_batch')return {data:['ok','invalid','retry',null].map((token,i)=>({id:String(i),lease_id:'lease',notification_id:'notification-'+i,token,title:'Lunch payment deducted',body:'₹75.50 deducted. Wallet balance: ₹924.50.',destination:'notifications',app_kind:i===0?'CUSTOMER':'PROVIDER'}))};
 if(name==='finish_campaign_push'){completed.push(args);return {};}
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
vm.runInNewContext(stripTypeScriptTypes(fs.readFileSync('supabase/functions/dispatch-campaign-push/index.ts','utf8').replace(/^import .*;\r?\n/,'')),context);
(async()=>{
 assert.equal((await handler(new Request('https://local',{method:'GET'}))).status,405);
 assert.equal((await handler(new Request('https://local',{method:'POST'}))).status,401);
 assert.equal((await handler(new Request('https://local',{method:'POST',headers:{'x-meal-dispatch-secret':'wrong'}}))).status,401);
 assert.equal(completed.length,0);
 const response=await handler(new Request('https://local',{method:'POST',headers:{'x-meal-dispatch-secret':'test-secret'}}));
 assert.equal(response.status,200);assert.equal(completed.length,4);
 assert.equal(completed.find(x=>x.target_id==='1').invalid_token,true);
 assert.equal(completed.find(x=>x.target_id==='2').delivered,false);
 assert.equal(sent[0].data.destination,'notifications');assert.equal(sent[0].data.notification_id,'notification-0');
 assert.equal(sent[0].notification,undefined);
 assert.equal(sent[1].notification.body,'₹75.50 deducted. Wallet balance: ₹924.50.');
 assert.equal(sent[0].data.body,'₹75.50 deducted. Wallet balance: ₹924.50.');
 assert.equal(sent[0].android.notification,undefined,'Do not enable Firebase automatic rendering for customer data pushes');
 assert.equal(sent[1].android.notification.channel_id,'zomeal_provider_updates');
 assert.equal(completed.find(x=>x.target_id==='3').error_code,'DEVICE_DISABLED');
 console.log('PASS: campaign worker authentication, customer/provider channels, disabled tokens and transient retries');
})().catch(e=>{console.error(e);process.exitCode=1;});
