// Offline DOM tests: no live customers, browser storage or network calls.
const {JSDOM}=require('jsdom');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const dom=new JSDOM('<div id="dashboard"><div class="sidebar"><nav id="nav"><button id="accountsNav"></button></nav></div><h1 id="pageTitle"></h1><span id="crumb"></span><main><section id="overviewView" class="view"></section></main></div>',{runScripts:'outside-only'});
const w=dom.window,d=w.document,tick=()=>new Promise(resolve=>setImmediate(resolve));
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};
const user={id:'70000000-0000-4000-8000-000000000001',full_name:'<img src=x onerror=alert(1)>',phone:'+919123456789',pincode:'757043',city:'Rairangpur',balance_paise:12345,is_registered:true,account_enabled:true,active_subscription:true,subscription_state:'ACTIVE',registered_at:'2026-09-20T00:00:00Z',subscription:{provider_name:'Test kitchen',package_name:'Monthly both',package_kind:'LUNCH_AND_DINNER',duration_days:30,start_date:'2026-09-20',end_date:'2026-10-19'}};
const summary={total_accounts:27,registered:26,active:10,paused:3,no_plan:13,incomplete:1,inactive:0};
const response=(overrides={})=>({customers:[user],summary,total:27,page:0,page_size:25,as_of_date:'2026-09-20',...overrides});
let requests=[],details=[],error=false,defer=false,pending=[];
w.ZomealAPI={configured:true,accountAccess:async()=>true,accountCleanup:async()=>({jobs:[]}),
  customerDirectory:async(query,filter,page)=>{requests.push({query,filter,page});if(error)throw new Error('Database unavailable');if(defer)return new Promise(resolve=>pending.push(resolve));return response({page});},
  accountDetail:async(kind,id)=>{details.push({kind,id});return{profile:{full_name:'Selected customer'},wallet:{balance_paise:12345},subscription_count:0,payment_count:0,wallet_entry_count:0,payout_count:0,advance_count:0};}
};
const click=s=>{assert.ok(d.querySelector(s),s);d.querySelector(s).click();};
const submit=()=>d.querySelector('.customer-directory-search').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
(async()=>{
  w.eval(fs.readFileSync(path.join(__dirname,'../admin/accounts.js'),'utf8'));await tick();
  assert.equal(requests.length,0);click('#accountsNav');await tick();
  assert.deepEqual(requests.at(-1),{query:'',filter:'ALL',page:0});
  assert.equal(d.querySelector('.account-directory-stats [data-customer-filter=REGISTERED] strong').textContent,'26');
  assert.equal(d.querySelector('.account-directory-stats [data-customer-filter=ACTIVE] strong').textContent,'10');
  assert.match(d.querySelector('.account-directory-definitions').textContent,/not app downloads/);
  assert.match(d.querySelector('.account-directory-table').textContent,/9123456789.*757043.*123\.45.*Test kitchen.*Monthly both/s);
  assert.equal(d.querySelector('.account-directory-table img'),null);
  assert.match(d.querySelector('.account-directory-pages').textContent,/Showing 1–25 of 27/);
  click('[data-directory-next]');await tick();assert.equal(requests.at(-1).page,1);
  assert.ok(d.querySelector('[data-directory-next]').disabled);
  const input=d.querySelector('#customerDirectoryQuery');input.value='Asha';submit();await tick();
  assert.deepEqual(requests.at(-1),{query:'Asha',filter:'ALL',page:0});
  click('.account-directory-filters [data-customer-filter=ACTIVE]');await tick();
  assert.deepEqual(requests.at(-1),{query:'Asha',filter:'ACTIVE',page:0});
  click('[data-clear-directory]');await tick();assert.equal(input.value,'');assert.equal(requests.at(-1).query,'');
  click('[data-customer-index]');await tick();assert.deepEqual(details,[{kind:'user',id:user.id}]);
  assert.match(d.querySelector('.account-detail').textContent,/Selected customer/);
  assert.ok(d.querySelector('.account-layout').classList.contains('account-detail-only'));
  error=true;click('.account-directory-refresh');await tick();
  assert.match(d.querySelector('.account-directory-notice').textContent,/Database unavailable/);
  assert.equal(d.querySelector('.account-directory-table').textContent,'');assert.equal(d.querySelector('.account-directory-stats').textContent,'');
  assert.equal(d.querySelector('.account-directory-refresh').disabled,false);error=false;
  // A late response to an old search must never overwrite the newer search.
  defer=true;input.value='Old';submit();input.value='New';submit();
  pending[1](response({customers:[],total:0}));await tick();
  pending[0](response());await tick();assert.match(d.querySelector('.account-directory-table').textContent,/No customers match/);
  assert.equal(d.querySelector('.account-directory-table img'),null);
  // An authenticated request completing after logout must not restore private data.
  pending=[];input.value='Logout';submit();d.querySelector('#dashboard').classList.add('hidden');await tick();
  pending[0](response());await tick();
  for(const s of ['.account-directory-table','.account-directory-stats','.account-directory-pages','.account-detail'])assert.equal(d.querySelector(s).textContent,'');
  assert.equal(input.value,'');
  console.log('PASS: automatic directory load, totals, search, filters, paging, details, escaping, retry, stale-response protection and logout clearing.');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>w.close());
