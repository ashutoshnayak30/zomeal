const {JSDOM}=require('jsdom'),fs=require('node:fs'),assert=require('node:assert/strict');
const dom=new JSDOM('<div id="dashboard"><nav id="nav"></nav><div id="pageTitle"></div><div id="crumb"></div><div id="content"></div></div>',{runScripts:'outside-only',url:'https://admin.example.test'});
const w=dom.window,d=w.document,calls=[];let fail=false,pending;
w.confirm=()=>true;
w.ZomealAPI={providers:async()=>[{id:'kitchen-id',display_name:'Kitchen <b>unsafe</b>'}],businessDashboard:{workspace:async(name,{payload:p})=>{calls.push(p);if(pending&&p.action==='lookup')return new Promise(r=>pending=r);if(fail)throw Error('Unavailable');if(p.action==='list')return {credits:[{id:'credit-id',phone:'8765432109',full_name:'<img src=x>',amount_paise:360000,balance_paise:360000,reason:'Test',reversed_at:null}],providers:[]};if(p.action==='lookup')return {id:'customer-id',phone:'8765432109',name:'Customer',balance_paise:0};return {id:'ok'};}}};
w.eval(fs.readFileSync('admin/demo.js','utf8'));
const settle=()=>new Promise(r=>setImmediate(r));
const submit=form=>form.dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
(async()=>{
 d.querySelector('[data-view=demo]').click();await settle();assert.match(d.querySelector('[data-credits]').textContent,/<img src=x>/);assert.equal(d.querySelector('[data-credits] img'),null);
 const lf=d.querySelector('[data-lookup]'),cf=d.querySelector('[data-credit]');lf.elements.phone.value='8765432109';submit(lf);await settle();assert.equal(cf.hidden,false);
 cf.elements.amount.value='3600';cf.elements.reason.value='Demo walkthrough';fail=true;submit(cf);await settle();const first=calls.at(-1);assert.equal(first.amount_paise,360000);assert.ok(first.request_id);
 fail=false;submit(cf);await settle();const credits=calls.filter(p=>p.action==='credit');assert.equal(credits[0].request_id,credits[1].request_id);assert.equal(cf.hidden,true);
 submit(lf);await settle();lf.elements.phone.value='9999999999';lf.elements.phone.dispatchEvent(new w.Event('input'));assert.equal(cf.hidden,true);
 pending=true;submit(lf);await settle();d.querySelector('#dashboard').classList.add('hidden');await settle();pending({id:'secret',name:'Private user',phone:'8765432109',balance_paise:100});await settle();assert.equal(d.querySelector('[data-account]').textContent,'');assert.equal(cf.hidden,true);
 assert.match(fs.readFileSync('provider-app/src/main/java/com/zomeal/provider/ProviderDemoScreen.kt','utf8'),/Practice only/);
 console.log('PASS: demo admin navigation, escaping, exact amount, retry key retention, changed-phone guard and logout stale-response protection');dom.window.close();
})().catch(e=>{console.error(e);dom.window.close();process.exitCode=1;});
