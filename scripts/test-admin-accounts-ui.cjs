const {JSDOM}=require('jsdom');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const dom=new JSDOM(`<div id="dashboard"><div class="sidebar"><nav id="nav"><button id="accountsNav" class="hidden">Accounts</button></nav></div><h1 id="pageTitle"></h1><span id="crumb"></span><main><section id="overviewView" class="view"></section></main></div>`,{runScripts:'outside-only'});
const {window:w}=dom,d=w.document;
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;};
const id='10000000-0000-4000-8000-000000000003';
let allowed=false,deleted=0,cleanupAttempts=0,jobs=[],searchFails=false,blockDeletion=false;
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const click=selector=>{assert.ok(d.querySelector(selector),selector);d.querySelector(selector).click();};
const submit=el=>el.dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
w.ZomealAPI={configured:true,accountAccess:async()=>allowed,
  accountSearch:async()=>{if(searchFails)throw new Error('Search unavailable');return{total:1,results:[{kind:'user',id,name:'<img src=x onerror=alert(1)>',phone:'9999999993',status:'ACTIVE'}]};},
  accountDetail:async()=>({profile:{id,full_name:'Test customer'},auth:{phone:'919999999993'},roles:['CUSTOMER'],wallet:{balance_paise:12345},addresses:[],providers:[],subscriptions:[],payments:[],wallet_entries:[],payouts:[],subscription_count:0,payment_count:0,wallet_entry_count:0,payout_count:0,advance_count:0}),
  accountDeletePreview:async()=>({name:'Test customer',allowed:!blockDeletion,scope:'Deletes this login.',counts:{addresses:0},confirmation:`DELETE ${id}`,blockers:['Wallet history must be retained.']}),
  accountDelete:async(kind,target,confirmation,reason)=>{assert.equal(kind,'user');assert.equal(target,id);assert.equal(confirmation,`DELETE ${id}`);assert.ok(reason.length>=10);deleted++;jobs=[{id:'job',target_kind:'user',target_id:id}];return{cleanup_pending:true,job_id:'job'};},
  accountCleanup:async(action)=>{if(action==='list')return{jobs};cleanupAttempts++;if(cleanupAttempts===1)throw new Error('Storage unavailable');jobs=[];return{complete:true};}
};
async function main(){
  // Load the real stylesheet cascade: the inner menu must not trap wheel events.
  for(const file of ['styles.css','improvements.css','typography.css','staff.css','accounts.css']) {
    const style=d.createElement('style');style.textContent=fs.readFileSync(path.join(__dirname,'../admin',file),'utf8');d.head.append(style);
  }
  assert.equal(w.getComputedStyle(d.querySelector('.sidebar')).overflowY,'auto');
  assert.equal(w.getComputedStyle(d.querySelector('#nav')).overflowY,'visible');
  assert.equal(w.getComputedStyle(d.querySelector('#nav')).getPropertyValue('overscroll-behavior'),'auto');
  w.eval(fs.readFileSync(path.join(__dirname,'../admin/accounts.js'),'utf8'));
  await settle();assert.ok(d.querySelector('#accountsNav').classList.contains('hidden'));
  allowed=true;d.querySelector('#dashboard').classList.add('hidden');await settle();d.querySelector('#dashboard').classList.remove('hidden');await settle();
  assert.equal(d.querySelector('#accountsNav').classList.contains('hidden'),false);
  click('#accountsNav');await settle();assert.equal(d.querySelector('#accountsView').classList.contains('hidden'),false);
  assert.equal(d.querySelector('#pageTitle').textContent,'Users data');
  assert.match(fs.readFileSync(path.join(__dirname,'../admin/index.html'),'utf8'),/id="accountsNav"[^>]*>.*Users data<\/button>/);
  const form=d.querySelector('.account-search');form.elements.query.value='9999999993';submit(form);await settle();
  assert.equal(d.querySelector('.account-results img'),null); // Stored XSS is escaped.
  click('[data-index]');await settle();assert.match(d.querySelector('.account-stats').textContent,/123\.45/);
  blockDeletion=true;click('[data-delete]');await settle();assert.match(d.querySelector('dialog').textContent,/Deletion blocked/);assert.equal(d.querySelector('dialog form'),null);click('[data-cancel]');
  blockDeletion=false;click('[data-delete]');await settle();
  const deleteForm=d.querySelector('dialog form'),button=deleteForm.querySelector('[type=submit]');assert.ok(button.disabled);
  deleteForm.elements.confirmation.value=`DELETE ${id}`;deleteForm.elements.reason.value='Reset customer for testing';deleteForm.dispatchEvent(new w.Event('input',{bubbles:true}));assert.equal(button.disabled,false);
  submit(deleteForm);await settle();await settle();assert.equal(deleted,1);assert.equal(d.querySelector('dialog').open,false);
  assert.match(d.querySelector('.account-cleanup').textContent,/Pending file cleanup/);
  click('[data-job]');await settle();assert.equal(cleanupAttempts,2);assert.equal(d.querySelector('[data-job]'),null);
  searchFails=true;submit(form);await settle();assert.match(d.querySelector('#accountsView [role=status]').textContent,/Search unavailable/);assert.equal(form.querySelector('button').disabled,false);
  d.querySelector('#dashboard').classList.add('hidden');await settle();assert.equal(d.querySelector('.account-detail').textContent,'');assert.equal(form.elements.query.value,'');assert.equal(d.querySelector('dialog').textContent,'');
  console.log('PASS: role visibility, navigation, search, XSS escaping, wallet display, blocked deletion, typed confirmation, cleanup failure/retry, errors and logout clearing.');
}
main().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>w.close());
