const assert=require('node:assert/strict'),fs=require('node:fs'),{JSDOM}=require('jsdom');
const dom=new JSDOM('<div id="dashboard"><nav id="nav"></nav><h1 id="pageTitle"></h1><p id="crumb"></p><main><section id="overviewView" class="view"></section></main></div>',{runScripts:'outside-only'}),w=dom.window,saved=[];
w.confirm=()=>true;w.URL.revokeObjectURL=()=>{};
w.ZomealAPI={accountAccess:async()=>true,mealFeedback:async()=>({items:[{meal_id:'meal',complaint_id:'complaint',provider_name:'Kitchen',item_name:'<img src=x onerror=alert(1)>',customer_name:'Customer',customer_phone:'919999999999',service_date:'2026-10-01',meal_slot:'LUNCH',meal_value_paise:7500,status:'CALLBACK_REQUESTED',issue:'Food quality',details:'Food not fresh',rating:2,review_text:'Too cold'}]}),reviewComplaint:async(...args)=>saved.push(args)};
w.eval(fs.readFileSync('admin/meal-feedback.js','utf8'));
const tick=()=>new Promise(r=>setTimeout(r,15));
(async()=>{
 await tick();const nav=w.document.querySelector('#nav button');assert.equal(nav.classList.contains('hidden'),false);nav.click();await tick();
 const card=w.document.querySelector('.feedback-card');assert.equal(card.querySelector('img'),null,'unsafe customer text escaped');
 card.querySelector('[data-state="REFUNDED"]').click();await tick();assert.equal(saved.length,0);
 card.querySelector('[data-note]').value='Callback completed and approved';
 card.querySelector('[data-amount]').value='80';card.querySelector('[data-state="REFUNDED"]').click();await tick();assert.equal(saved.length,0);
 card.querySelector('[data-amount]').value='50';card.querySelector('[data-state="REFUNDED"]').click();await tick();assert.deepEqual(saved[0],['complaint','REFUNDED','Callback completed and approved',5000]);
 w.document.querySelector('#dashboard').classList.add('hidden');await tick();assert.equal(w.document.querySelector('.feedback-grid').textContent,'');
 console.log('PASS: admin feedback navigation, escaping, callback note and amount validation, explicit refund payload, logout clearing');w.close();
})().catch(e=>{console.error(e);w.close();process.exitCode=1;});
