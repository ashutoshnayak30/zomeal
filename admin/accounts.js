/* Private account data stays in memory, never in URLs or browser storage. */
(() => {
  const api = window.ZomealAPI;
  const nav = document.querySelector('#accountsNav');
  const dashboard = document.querySelector('#dashboard');
  const view = document.createElement('section');
  view.id = 'accountsView'; view.className = 'view hidden';
  view.innerHTML = `<div class="staff-heading"><div><h2>Users data</h2><p class="muted">Search a phone number or user ID, then select a user or provider to see their details, packages and wallet.</p></div></div>
    <form class="panel account-search"><label>Phone number or user / provider ID<input name="query" type="search" maxlength="50" autocomplete="off" placeholder="10-digit phone number, +91 number, or full UUID" required></label><button class="primary" type="submit">Search accounts</button></form>
    <div class="account-cleanup"></div><div class="account-notice" role="status" aria-live="polite">Search to inspect a customer or provider. No accounts are loaded until you search.</div>
    <div class="account-layout"><aside class="panel account-results" aria-label="Search results"></aside><div class="account-detail"></div></div>`;
  document.querySelector('#overviewView').parentElement.append(view);
  const dialog = document.createElement('dialog'); dialog.className = 'account-delete';
  dialog.setAttribute('aria-label', 'Confirm permanent account deletion');
  document.body.append(dialog);
  const form = view.querySelector('form'), results = view.querySelector('.account-results');
  const detail = view.querySelector('.account-detail'), status = view.querySelector('[role=status]');
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

  async function checkAccess() {
    const revision=++accessSequence;
    if (dashboard.classList.contains('hidden')) {
      ++sequence; nav.classList.add('hidden'); view.classList.add('hidden');
      selection=null; results.replaceChildren(); detail.replaceChildren(); view.querySelector('.account-cleanup').replaceChildren(); form.reset(); dialog.close(); dialog.replaceChildren(); return;
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
    view.classList.remove('hidden'); form.elements.query.focus(); loadCleanup();
  });
  form.addEventListener('submit', event=>{event.preventDefault();searchText=form.elements.query.value.trim();searchPage=0;search();});
  async function search() {
    const revision=++sequence; selection=null; detail.replaceChildren(); results.replaceChildren();
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
          if(outcome.cleanup_pending){try{await api.accountCleanup('cleanup',outcome.job_id);}catch{notice('Account deleted. Some uploaded files still need cleanup; use Retry cleanup below.',true);}}
          await loadCleanup();
        }catch(error){dialog.querySelector('[role=status]').textContent=`${error.message} Refresh and check the account before retrying.`;deleteForm.querySelector('[data-cancel]').disabled=false;}
        finally{busy=false;}
      };
    }catch(error){notice(error.message,true);}
  }
  async function loadCleanup() {
    const host=view.querySelector('.account-cleanup');
    try {
      const data=await api.accountCleanup('list');if(dashboard.classList.contains('hidden'))return;
      host.innerHTML=data.jobs.length?`<section class="panel account-card"><h3>Pending file cleanup</h3><p>These accounts were deleted. Their uploaded files still need cleanup.</p>${data.jobs.map(j=>`<div class="account-actions"><span class="account-id">${escape(j.target_kind)} ${escape(j.target_id)}</span><button data-job="${escape(j.id)}">Retry cleanup</button></div>`).join('')}</section>`:'';
      host.querySelectorAll('[data-job]').forEach(b=>b.onclick=async()=>{b.disabled=true;try{await api.accountCleanup('cleanup',b.dataset.job);await loadCleanup();}catch(error){notice(error.message,true);b.disabled=false;}});
    }catch{host.textContent='File-cleanup service unavailable. Deploy admin-account-cleanup to enable cleanup retries.';}
  }
})();
