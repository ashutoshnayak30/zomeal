/* Private account data stays in memory, never in URLs or browser storage. */
(() => {
  const api = window.ZomealAPI;
  const nav = document.querySelector('#accountsNav');
  const dashboard = document.querySelector('#dashboard');
  const view = document.createElement('section');
  view.id = 'accountsView'; view.className = 'view hidden';
  view.innerHTML = `<div class="staff-heading"><div><h2>Users data</h2><p class="muted">Registered customers, current subscriptions and wallet balances in one place.</p></div><button type="button" class="account-directory-refresh">Refresh users</button></div>
    <div class="account-directory-stats" aria-label="Customer totals"></div>
    <p class="account-directory-definitions">Registered users have a saved phone number. Active users have an enabled account and an ACTIVE subscription covering today in India. These are account counts, not app downloads or daily app usage.</p>
    <form class="panel customer-directory-search"><label for="customerDirectoryQuery">Search customers<input id="customerDirectoryQuery" name="query" type="search" maxlength="100" autocomplete="off" placeholder="Name, phone number, pincode or user ID"></label><button type="submit">Search users</button><button type="button" data-clear-directory>Clear</button></form>
    <div class="account-directory-filters" role="group" aria-label="Filter customers">
      <button data-customer-filter="ALL" aria-pressed="true">All accounts</button><button data-customer-filter="REGISTERED" aria-pressed="false">Registered</button><button data-customer-filter="ACTIVE" aria-pressed="false">Active subscriptions</button><button data-customer-filter="PAUSED" aria-pressed="false">Paused subscriptions</button><button data-customer-filter="NO_PLAN" aria-pressed="false">No current plan</button><button data-customer-filter="INCOMPLETE" aria-pressed="false">Incomplete profiles</button><button data-customer-filter="INACTIVE" aria-pressed="false">Disabled accounts</button>
    </div>
    <p class="account-directory-notice" role="status" aria-live="polite">Open Users data to load customers.</p>
    <div class="panel account-directory-table" aria-label="Customer directory"></div><div class="account-directory-pages account-pages"></div>
    <details class="account-other-lookup"><summary>Find a provider or staff account by phone / ID</summary>
    <form class="panel account-search"><label>Phone number or user / provider ID<input name="query" type="search" maxlength="50" autocomplete="off" placeholder="10-digit phone number, +91 number, or full UUID" required></label><button class="primary" type="submit">Search accounts</button></form>
    </details><div class="account-notice account-detail-notice hidden" role="status" aria-live="polite"></div>
    <div class="account-layout hidden"><aside class="panel account-results" aria-label="Search results"></aside><div class="account-detail" tabindex="-1"></div></div>
    <div class="account-cleanup"></div>`;
  document.querySelector('#overviewView').parentElement.append(view);
  const dialog = document.createElement('dialog'); dialog.className = 'account-delete';
  dialog.setAttribute('aria-label', 'Confirm permanent account deletion');
  document.body.append(dialog);
  const form = view.querySelector('.account-search'), results = view.querySelector('.account-results');
  const detail = view.querySelector('.account-detail'), status = view.querySelector('.account-detail-notice');
  const directoryForm = view.querySelector('.customer-directory-search');
  const directoryTable = view.querySelector('.account-directory-table');
  const directoryStatus = view.querySelector('.account-directory-notice');
  const directoryStats = view.querySelector('.account-directory-stats');
  const directoryPages = view.querySelector('.account-directory-pages');
  let directorySequence=0, directoryPage=0, directoryQuery='', directoryFilter='ALL', cleanupSequence=0;
  let selection = null, searchText = '', searchPage = 0, sequence = 0, accessSequence = 0;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const label = key => key.replace(/_paise$/, ' (₹)').replace(/_/g, ' ').replace(/^./, c => c.toUpperCase());
  const money = value => new Intl.NumberFormat('en-IN', {style:'currency',currency:'INR',minimumFractionDigits:2}).format(Number(value || 0) / 100);
  const valueText = (key, value) => value == null ? '—' : key.endsWith('_paise') ? money(value) : typeof value === 'boolean' ? (value ? 'Yes' : 'No') : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
  const renderValue = (key,value) => value && typeof value==='object'
    ? Array.isArray(value)
      ? value.map((item,i)=> typeof item==='object' && item!==null ? `<details><summary>${escape(item.name||item.status||`Record ${i+1}`)}</summary>${fields(item)}</details>` : escape(item)).join('<br>') || 'None'
      : fields(value)
    : escape(valueText(key,value));
  const fields = object => `<dl class="account-fields">${Object.entries(object || {}).map(([k,v]) => `<div><dt>${escape(label(k))}</dt><dd>${renderValue(k,v)}</dd></div>`).join('')}</dl>`;
  const cards = (heading, rows) => `<section class="panel account-card"><h3>${escape(heading)}</h3>${rows?.length ? rows.map((row,i) => `<details><summary>${escape(row.name || row.full_name || row.id || `${heading} ${i+1}`)}</summary>${fields(row)}</details>`).join('') : '<p>No records.</p>'}</section>`;
  const table = (heading, rows, columns) => `<section class="panel account-card"><h3>${escape(heading)}</h3>${rows?.length ? `<div class="account-table"><table><thead><tr>${columns.map(c=>`<th>${escape(label(c))}</th>`).join('')}<th>Details</th></tr></thead><tbody>${rows.map(row=>`<tr>${columns.map(c=>`<td>${escape(valueText(c,row[c]))}</td>`).join('')}<td><details><summary>View all fields</summary>${fields(row)}</details></td></tr>`).join('')}</tbody></table></div>` : '<p>No records on this page.</p>'}</section>`;
  const notice = (text, error=false) => {status.textContent=text;status.className=error?'account-error':'account-notice';};
  const dateText = value => {
    if(!value)return '—';
    const date=new Date(value.length===10?`${value}T00:00:00+05:30`:value);
    return Number.isNaN(date.getTime())?'—':new Intl.DateTimeFormat('en-IN',{day:'2-digit',month:'short',year:'numeric',timeZone:'Asia/Kolkata'}).format(date);
  };
  function resetDirectory() {
    ++directorySequence;++cleanupSequence;
    directoryPage=0;directoryQuery='';directoryFilter='ALL';directoryForm.reset();
    directoryTable.replaceChildren();directoryStats.replaceChildren();directoryPages.replaceChildren();
    directoryStatus.textContent='Open Users data to load customers.';
    directoryForm.querySelector('[type=submit]').disabled=false;
    view.querySelector('.account-directory-refresh').disabled=false;
    view.querySelectorAll('[data-customer-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.customerFilter==='ALL')));
    view.querySelector('.account-layout').classList.add('hidden');status.textContent='';status.classList.add('hidden');
  }
  function showDirectoryTotals(summary) {
    const metrics=[['REGISTERED','Registered users',summary.registered,'Accounts with a saved phone'],['ACTIVE','Active users',summary.active,'Active subscription today'],['PAUSED','Paused users',summary.paused,'Current subscription paused'],['NO_PLAN','No current plan',summary.no_plan,'Registered, no in-period plan']];
    directoryStats.innerHTML=metrics.map(([filter,title,count,note])=>`<button type="button" data-customer-filter="${filter}" aria-pressed="${directoryFilter===filter}"><span>${title}</span><strong>${escape(count)}</strong><small>${note}</small></button>`).join('');
  }
  async function loadDirectory() {
    const revision=++directorySequence;
    directoryStatus.className='account-directory-notice';directoryStatus.textContent='Loading customers…';
    directoryTable.replaceChildren();directoryPages.replaceChildren();
    directoryForm.querySelector('[type=submit]').disabled=true;view.querySelector('.account-directory-refresh').disabled=true;
    try {
      const data=await api.customerDirectory(directoryQuery,directoryFilter,directoryPage);
      if(revision!==directorySequence||dashboard.classList.contains('hidden'))return;
      if(directoryPage>0&&directoryPage*data.page_size>=data.total){directoryPage=Math.max(0,Math.ceil(data.total/data.page_size)-1);return loadDirectory();}
      showDirectoryTotals(data.summary);
      view.querySelectorAll('[data-customer-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.customerFilter===directoryFilter)));
      directoryStatus.textContent=`${data.total} matching customer account(s). ${data.summary.total_accounts} accounts overall, including ${data.summary.incomplete} incomplete profile(s) and ${data.summary.inactive} disabled account(s). Totals above are not reduced by search. As of ${dateText(data.as_of_date)} (India).`;
      if(!data.customers.length){directoryTable.innerHTML='<p class="account-directory-empty">No customers match this search and category. Try All accounts or clear the search.</p>';return;}
      const stateLabels={ACTIVE:'Active',PAUSED:'Paused',NONE:'No subscription',UPCOMING:'Starts later',EXPIRED:'Expired',COMPLETED:'Completed',CANCELLED:'Cancelled',CANCEL_PENDING:'Cancellation pending',PENDING:'Pending'};
      const kindLabels={LUNCH_ONLY:'Lunch only',DINNER_ONLY:'Dinner only',LUNCH_AND_DINNER:'Lunch + dinner'};
      directoryTable.innerHTML=`<table><caption class="sr-only">Customer names, phones, locations, wallets and subscriptions</caption><thead><tr><th scope="col">Customer</th><th scope="col">Phone / location</th><th scope="col">Wallet balance</th><th scope="col">Subscription</th><th scope="col">Joined</th><th scope="col">Details</th></tr></thead><tbody>${data.customers.map((customer,index)=>{
        const sub=customer.subscription;
        const state=stateLabels[customer.subscription_state]||customer.subscription_state||'No subscription';
        const tone=customer.active_subscription?'active':customer.paused_subscription?'paused':'neutral';
        return `<tr><td><strong>${escape(customer.full_name||'Name not saved')}</strong><small class="account-id">${escape(customer.id)}</small>${!customer.is_registered?'<span class="account-customer-badge paused">Incomplete registration</span>':''}${!customer.account_enabled?'<span class="account-customer-badge disabled">Account disabled</span>':''}${customer.is_test_account?'<span class="account-customer-badge neutral">Test account</span>':''}</td>
          <td><span>${escape(customer.phone||'Phone not saved')}</span><small>Pincode: ${escape(customer.pincode||'Not saved')}</small>${customer.city?`<small>${escape(customer.city)}</small>`:''}</td>
          <td class="account-directory-money">${escape(money(customer.balance_paise))}</td>
          <td><span class="account-customer-badge ${tone}">${escape(state)}</span>${sub?`<strong>${escape(sub.provider_name||'Provider not available')}</strong><small>${escape(sub.package_name||'Package not available')} · ${escape(kindLabels[sub.package_kind]||sub.package_kind||'')}${sub.duration_days?` · ${escape(sub.duration_days)} days`:''}</small><small>${escape(dateText(sub.start_date))} – ${escape(dateText(sub.end_date))}</small>${sub.pause_reason?`<small>Reason: ${escape(sub.pause_reason.replace(/_/g,' ').toLowerCase())}</small>`:''}`:'<small>No subscription purchased yet</small>'}</td>
          <td>${escape(dateText(customer.registered_at))}</td><td><button type="button" data-customer-index="${index}" aria-label="View details for ${escape(customer.full_name||customer.phone||customer.id)}">View details</button></td></tr>`;
      }).join('')}</tbody></table>`;
      directoryTable.querySelectorAll('[data-customer-index]').forEach(button=>button.onclick=()=>{
        const customer=data.customers[Number(button.dataset.customerIndex)];
        results.replaceChildren();selection={kind:'user',id:customer.id};
        view.querySelector('.account-layout').classList.remove('hidden');
        view.querySelector('.account-layout').classList.add('account-detail-only');
        notice(`Details for ${customer.full_name||customer.phone||customer.id}`);loadDetail();
        detail.focus({preventScroll:true});detail.scrollIntoView?.({behavior:'smooth',block:'start'});
      });
      directoryPages.innerHTML=`<button type="button" data-directory-prev ${directoryPage===0?'disabled':''}>Previous</button><span>Showing ${directoryPage*data.page_size+1}–${Math.min((directoryPage+1)*data.page_size,data.total)} of ${data.total}</span><button type="button" data-directory-next ${(directoryPage+1)*data.page_size>=data.total?'disabled':''}>Next</button>`;
      directoryPages.querySelector('[data-directory-prev]').onclick=()=>{directoryPage--;loadDirectory();};
      directoryPages.querySelector('[data-directory-next]').onclick=()=>{directoryPage++;loadDirectory();};
    } catch(error) {
      if(revision!==directorySequence)return;
      directoryStats.replaceChildren();directoryStatus.className='account-directory-notice account-error';
      directoryStatus.textContent=`Could not load customers. ${error.message} Use Refresh users to retry. If this is a new deployment, apply the customer-directory database migration.`;
    } finally {
      if(revision===directorySequence){directoryForm.querySelector('[type=submit]').disabled=false;view.querySelector('.account-directory-refresh').disabled=false;}
    }
  }
  directoryForm.addEventListener('submit',event=>{event.preventDefault();directoryQuery=directoryForm.elements.query.value.trim();directoryPage=0;loadDirectory();});
  directoryForm.querySelector('[data-clear-directory]').onclick=()=>{directoryForm.reset();directoryQuery='';directoryPage=0;loadDirectory();};
  view.querySelector('.account-directory-refresh').onclick=()=>loadDirectory();
  view.addEventListener('click',event=>{const button=event.target.closest('[data-customer-filter]');if(!button)return;directoryFilter=button.dataset.customerFilter;directoryPage=0;loadDirectory();});

  async function checkAccess() {
    const revision=++accessSequence;
    if (dashboard.classList.contains('hidden')) {
      ++sequence; nav.classList.add('hidden'); view.classList.add('hidden');
      selection=null; results.replaceChildren(); detail.replaceChildren(); view.querySelector('.account-cleanup').replaceChildren(); form.reset(); dialog.close(); dialog.replaceChildren(); resetDirectory(); return;
    }
    try { const allowed = api.configured && await api.accountAccess(); if(revision===accessSequence) nav.classList.toggle('hidden', !allowed); }
    catch { if(revision===accessSequence) nav.classList.add('hidden'); }
  }
  new MutationObserver(checkAccess).observe(dashboard,{attributes:true,attributeFilter:['class']});
  checkAccess();
  nav.addEventListener('click', event => {
    event.stopPropagation();
    document.querySelectorAll('.view').forEach(v=>v.classList.add('hidden'));
    document.querySelectorAll('#nav button').forEach(b=>b.classList.toggle('active',b===nav));
    document.querySelector('.sidebar').classList.remove('open');
    document.querySelector('#pageTitle').textContent='Users data';
    document.querySelector('#crumb').textContent='Administration / Users data';
    view.classList.remove('hidden'); directoryForm.elements.query.focus(); loadDirectory(); loadCleanup();
  });
  form.addEventListener('submit', event=>{event.preventDefault();searchText=form.elements.query.value.trim();searchPage=0;search();});
  async function search() {
    const revision=++sequence; selection=null; detail.replaceChildren(); results.replaceChildren();
    view.querySelector('.account-layout').classList.remove('hidden','account-detail-only');
    notice('Searching…'); form.querySelector('button').disabled=true;
    try {
      const data=await api.accountSearch(searchText,searchPage); if(revision!==sequence)return;
      notice(data.total ? `${data.total} matching record(s). Select one to inspect.` : 'No matching account. Check the complete number or ID.');
      results.innerHTML=data.results.map((r,i)=>`<button type="button" data-index="${i}" aria-pressed="false"><small>${escape(r.kind==='user'?'Customer / user':'Provider')} · ${escape(r.status)}</small><strong>${escape(r.name||'Unnamed account')}</strong><span>${escape(r.phone||'No phone saved')}</span><small>${escape(r.id)}</small></button>`).join('');
      results.querySelectorAll('[data-index]').forEach(b=>b.onclick=()=>{results.querySelectorAll('[data-index]').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));selection=data.results[Number(b.dataset.index)];loadDetail();});
      if(data.total>20){const pager=document.createElement('div');pager.className='account-pages';pager.innerHTML=`<button ${searchPage===0?'disabled':''}>Previous</button><span>Page ${searchPage+1}</span><button ${(searchPage+1)*20>=data.total?'disabled':''}>Next</button>`;pager.firstElementChild.onclick=()=>{searchPage--;search();};pager.lastElementChild.onclick=()=>{searchPage++;search();};results.append(pager);}
    }catch(error){if(revision===sequence)notice(error.message,true);}
    finally{form.querySelector('button').disabled=false;}
  }
  async function loadDetail(page=0) {
    if(!selection)return; const target={...selection},revision=++sequence;
    detail.textContent='Loading account details…';
    try {
      const data=await api.accountDetail(target.kind,target.id,page);if(revision!==sequence)return;
      const stats=data.wallet||{};
      detail.innerHTML=`<section class="panel account-card"><h3>${escape(data.profile.display_name||data.profile.full_name||'Account')}</h3><div class="account-id">${escape(target.id)}</div><div class="account-actions"><button data-refresh>Refresh details</button><button data-delete class="account-danger">Review deletion</button></div></section>
        <div class="account-stats">${Object.entries(stats).filter(([k])=>k.endsWith('_paise')).map(([k,v])=>`<article>${escape(label(k).replace(' (₹)',''))}<strong>${escape(money(v))}</strong></article>`).join('')}</div>
        ${target.kind==='provider'?'<p class="account-notice">Provider balance is ledger-based: available funds exclude reserved payouts. Test ledger entries, if present, are included and identifiable in activity.</p>':''}
        <section class="panel account-card"><h3>Profile details</h3>${fields(data.profile)}${data.auth?`<details><summary>Login information</summary>${fields(data.auth)}<p>Roles: ${escape((data.roles||[]).join(', '))}</p></details>`:''}</section>
        ${target.kind==='user'? cards('Saved addresses',data.addresses)+cards('Linked providers',data.providers)+cards('Referral account',data.referral?[data.referral]:[]) : cards('Provider members',data.members)+cards('Packages and price versions',data.packages)+cards('Weekly menus',data.menus)+cards('Service areas',data.service_areas)+cards('Delivery contacts',data.delivery_people)+cards('Media records',data.media)}
        <div class="panel account-card account-pages"><span>History page ${page+1} · up to 25 records per section</span><button data-prev ${page===0?'disabled':''}>Previous history</button><button data-next ${Math.max(data.subscription_count,data.payment_count,data.wallet_entry_count,data.payout_count,data.advance_count)<=(page+1)*25?'disabled':''}>Next history</button></div>
        ${table('Subscriptions',data.subscriptions,['package_name','package_kind','duration_days','status','start_date','end_date','total_paid_paise'])}
        ${table('Payments',data.payments,['created_at','status','amount_paise','test_mode'])}
        ${table('Wallet / earnings activity',data.wallet_entries,target.kind==='user'?['created_at','entry_type','amount_paise','description']:['created_at','entry_type','gross_paise','commission_paise','provider_net_paise','available_at'])}
        ${target.kind==='provider'?table('Payout history',data.payouts,['requested_at','amount_paise','status','paid_at'])+table('Advance history',data.advances,['requested_at','amount_paise','recovered_paise','status']):''}`;
      detail.querySelector('[data-refresh]').onclick=()=>loadDetail(page);
      detail.querySelector('[data-delete]').onclick=()=>reviewDeletion(target);
      detail.querySelector('[data-prev]').onclick=()=>loadDetail(page-1);
      detail.querySelector('[data-next]').onclick=()=>loadDetail(page+1);
    }catch(error){if(revision===sequence){detail.textContent='';notice(error.message,true);}}
  }
  async function reviewDeletion(target) {
    try {
      const preview=await api.accountDeletePreview(target.kind,target.id);
      if(!selection || selection.id!==target.id || dashboard.classList.contains('hidden'))return;
      dialog.innerHTML=`<h2>Delete ${escape(preview.name||target.kind)}?</h2><p class="account-id">${escape(target.id)}</p><p>${escape(preview.scope)}</p>${fields(preview.counts)}
        ${preview.allowed ? `<p class="account-error">Permanent deletion cannot be undone. Financial and linked-account checks run again before deletion.</p><form><label>Reason<textarea name="reason" minlength="10" maxlength="500" required placeholder="Explain why this account should be deleted"></textarea></label><label>Type ${escape(preview.confirmation)}<input name="confirmation" autocomplete="off" required></label><div class="account-actions"><button type="button" data-cancel>Cancel</button><button type="submit" class="account-danger" disabled>Permanently delete</button></div></form>` : `<div class="account-error"><strong>Deletion blocked</strong><ul>${preview.blockers.map(b=>`<li>${escape(b)}</li>`).join('')}</ul></div><button data-cancel>Close</button>`}<p role="status" aria-live="polite"></p>`;
      dialog.querySelector('[data-cancel]').onclick=()=>dialog.close(); dialog.showModal();
      const deleteForm=dialog.querySelector('form');if(!deleteForm)return;
      const submit=deleteForm.querySelector('[type=submit]');let busy=false;
      dialog.oncancel=event=>{if(busy)event.preventDefault();};
      deleteForm.oninput=()=>{submit.disabled=deleteForm.elements.confirmation.value!==preview.confirmation||deleteForm.elements.reason.value.trim().length<10;};
      deleteForm.onsubmit=async event=>{
        event.preventDefault();if(busy||submit.disabled)return;busy=true;
        deleteForm.querySelectorAll('button,input,textarea').forEach(e=>e.disabled=true);
        dialog.querySelector('[role=status]').textContent='Deleting account…';
        try {
          const outcome=await api.accountDelete(target.kind,target.id,deleteForm.elements.confirmation.value,deleteForm.elements.reason.value);
          dialog.close();selection=null;++sequence;detail.replaceChildren();results.replaceChildren();
          notice('Account deleted. Clear the app’s local data before registering again.');
          loadDirectory();
          if(outcome.cleanup_pending){try{await api.accountCleanup('cleanup',outcome.job_id);}catch{notice('Account deleted. Some uploaded files still need cleanup; use Retry cleanup below.',true);}}
          await loadCleanup();
        }catch(error){dialog.querySelector('[role=status]').textContent=`${error.message} Refresh and check the account before retrying.`;deleteForm.querySelector('[data-cancel]').disabled=false;}
        finally{busy=false;}
      };
    }catch(error){notice(error.message,true);}
  }
  async function loadCleanup() {
    const host=view.querySelector('.account-cleanup');
    const revision=++cleanupSequence;
    try {
      const data=await api.accountCleanup('list');if(revision!==cleanupSequence||dashboard.classList.contains('hidden'))return;
      host.innerHTML=data.jobs.length?`<details class="panel account-card"><summary>Pending file cleanup (${data.jobs.length})</summary><p>These accounts were deleted. Their uploaded files still need cleanup.</p>${data.jobs.map(j=>`<div class="account-actions"><span class="account-id">${escape(j.target_kind)} ${escape(j.target_id)}</span><button data-job="${escape(j.id)}">Retry cleanup</button></div>`).join('')}</details>`:'';
      host.querySelectorAll('[data-job]').forEach(b=>b.onclick=async()=>{b.disabled=true;try{await api.accountCleanup('cleanup',b.dataset.job);await loadCleanup();}catch(error){notice(error.message,true);b.disabled=false;}});
    }catch{if(revision===cleanupSequence&&!dashboard.classList.contains('hidden'))host.textContent='File-cleanup service unavailable. Deploy admin-account-cleanup to enable cleanup retries.';}
  }
})();
