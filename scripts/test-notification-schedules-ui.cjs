const assert=require('node:assert/strict'),fs=require('node:fs');
const {JSDOM}=require('jsdom');
const dom=new JSDOM('<div id="dashboard"><section id="notificationsView" class="hidden"></section></div>',{runScripts:'outside-only',url:'https://example.invalid'});
const w=dom.window,saved=[],states=[];let current=[];
w.confirm=()=>true;w.HTMLElement.prototype.scrollIntoView=()=>{};
w.ZomealAPI={campaignPreview:async()=>({count:1,users:[{user_id:'abc',full_name:'<img src=x onerror=alert(1)>',phone:'919999999999'}],kitchens:[{id:'k',name:'Kitchen'}]}),
 campaignSchedules:async()=>({schedules:current,runs:[]}),saveCampaign:async p=>saved.push(p),campaignState:async(...args)=>states.push(args)};
w.eval(fs.readFileSync('admin/notification-schedules.js','utf8'));
const $=id=>w.document.getElementById(id),tick=()=>new Promise(r=>setTimeout(r,10));
(async()=>{
 $('notificationsView').classList.remove('hidden');await tick();
 $('campaignAudience').value='USERS';$('campaignAudience').dispatchEvent(new w.Event('change'));
 await $('campaignFind').onclick();assert.equal($('campaignResults').querySelector('img'),null,'names escaped');
 $('campaignResults').querySelector('button').click();assert.match($('campaignSelected').textContent,/9999999999/);
 await $('campaignForm').onsubmit({preventDefault(){}});assert.equal(saved.length,0);assert.match($('campaignFeedback').textContent,/Enter a notification title/);
 $('campaignTitle').value='Test';$('campaignMessage').value='Reminder';$('campaignSoon').checked=false;
 $('campaignStart').value='2000-01-01T14:30';await $('campaignForm').onsubmit({preventDefault(){}});assert.equal(saved.length,0);assert.match($('campaignFeedback').textContent,/past/);
 const tomorrow=new Date(Date.now()+86400000).toISOString().slice(0,10);
 $('campaignStart').value=tomorrow+'T14:30';
 await $('campaignForm').onsubmit({preventDefault(){}});assert.equal(saved.length,1);assert.equal(saved[0].start_at,tomorrow+'T09:00:00.000Z');assert.equal(saved[0].target_values[0],'abc');
 $('campaignApp').value='PROVIDER';$('campaignApp').dispatchEvent(new w.Event('change'));assert.equal($('campaignAudience').querySelector('[value=LOW_WALLET]').disabled,true);
 $('campaignTemplate').value='wallet';$('campaignTemplate').dispatchEvent(new w.Event('change'));assert.equal($('campaignApp').value,'CUSTOMER');assert.equal($('campaignAudience').value,'LOW_WALLET');
 $('campaignTitle').value='Cancel test';$('campaignMessage').value='Do not send';w.confirm=()=>false;await $('campaignForm').onsubmit({preventDefault(){}});assert.equal(saved.length,1);
 $('dashboard').classList.add('hidden');await tick();assert.equal($('campaignResults').textContent,'');assert.equal($('campaignList').textContent,'');
 console.log('PASS: schedule UI recipient selection, escaped names, IST conversion, templates, confirmation and logout clearing');w.close();
})().catch(e=>{console.error(e);w.close();process.exitCode=1;});
