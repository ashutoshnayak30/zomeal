(() => {
  const api=window.ZomealAPI,nav=document.querySelector('#notificationsNav'),dashboard=document.querySelector('#dashboard');
  const view=document.createElement('section');view.id='notificationsView';view.className='view hidden';
  view.innerHTML=`<div class="notification-head"><div><h2>Push notification centre</h2><p>Send an app alert to all active customers or a precise city, pincode or provider audience.</p></div><button id="refreshNotificationCentre">↻ Refresh audiences</button></div>
    <div class="notification-layout">
      <section class="panel notification-compose">
        <div class="notification-step"><span>1</span><div><h3>Choose audience</h3><p>Only active customer accounts matching this selection will receive the alert.</p></div></div>
        <div id="notificationAudienceTypes" class="audience-types">
          <button class="active" data-type="ALL"><b>All users</b><small>Every active customer</small></button>
          <button data-type="PINCODE"><b>By pincode</b><small>Customers at one pincode</small></button>
          <button data-type="CITY"><b>By city</b><small>Customers in one city</small></button>
          <button data-type="PROVIDER"><b>By provider</b><small>Current subscribers</small></button>
          <button data-type="USER"><b>Single user</b><small>Find by mobile number</small></button>
        </div>
        <label id="notificationSegmentField" class="notification-field hidden">Select a segment<select id="notificationSegment"></select></label>
        <section id="notificationPhoneField" class="hidden">
          <label class="notification-field">Customer mobile number<input id="notificationPhone" type="tel" inputmode="tel" maxlength="22" placeholder="10 digits or +91 mobile number"></label>
          <button id="notificationFindPhone" type="button">Find customer</button>
          <div id="notificationPhoneResults" aria-live="polite"></div>
        </section>
        <div class="notification-recipient-summary"><strong id="notificationRecipientCount">Loading recipients…</strong><small id="notificationRecipientHint">Audience is checked again when you send.</small></div>
        <div class="notification-step"><span>2</span><div><h3>Write notification</h3><p>This exact text appears in the push and the customer’s in-app notification feed.</p></div></div>
        <label class="notification-field">Notification title<input id="notificationTitle" maxlength="100" placeholder="For example: Tomorrow’s menu is ready"></label>
        <label class="notification-field">Message<textarea id="notificationMessage" maxlength="500" rows="5" placeholder="Write a clear, useful message for your customers."></textarea><small><b id="notificationCharacters">0</b>/500 characters</small></label>
        <div class="notification-preview"><span>Preview</span><article><b id="notificationPreviewTitle">Zomeal</b><p id="notificationPreviewMessage">Your message will appear here.</p><small>Tap to open Zomeal notifications</small></article></div>
        <button id="sendTargetedNotification" class="primary notification-send" disabled>Send notification</button>
      </section>
      <aside>
        <section class="panel notification-users"><div class="notification-side-head"><div><h3>Active users</h3><p id="notificationUserTotal">Loading…</p></div><div class="input-search">⌕ <input id="notificationUserSearch" type="search" placeholder="Search name or phone"></div></div><div id="notificationUserList"></div></section>
        <section class="panel notification-history"><div class="notification-side-head"><div><h3>Recent sends</h3><p>Audited notification campaigns</p></div></div><div id="notificationHistory"></div></section>
      </aside>
    </div>`;
  document.querySelector('#overviewView').parentElement.append(view);
  const types=view.querySelector('#notificationAudienceTypes'),segmentField=view.querySelector('#notificationSegmentField'),segment=view.querySelector('#notificationSegment'),recipientCount=view.querySelector('#notificationRecipientCount'),title=view.querySelector('#notificationTitle'),message=view.querySelector('#notificationMessage'),send=view.querySelector('#sendTargetedNotification');
  let data=null,audience='ALL',searchTimer,accessRevision=0,loadRevision=0,lookupRevision=0,selectedUser=null,busy=false,loading=false;
  const phoneField=view.querySelector('#notificationPhoneField'),phone=view.querySelector('#notificationPhone'),phoneResults=view.querySelector('#notificationPhoneResults');
  const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  async function checkAccess(){const revision=++accessRevision;if(dashboard.classList.contains('hidden')){nav.classList.add('hidden');view.classList.add('hidden');return}try{const allowed=api.configured&&await api.accountAccess();if(revision===accessRevision)nav.classList.toggle('hidden',!allowed)}catch{if(revision===accessRevision)nav.classList.add('hidden')}}
  new MutationObserver(checkAccess).observe(dashboard,{attributes:true,attributeFilter:['class']});checkAccess();
  nav.onclick=event=>{event.stopPropagation();document.querySelectorAll('.view').forEach(item=>item.classList.add('hidden'));document.querySelectorAll('#nav button').forEach(button=>button.classList.toggle('active',button===nav));document.querySelector('.sidebar').classList.remove('open');document.querySelector('#pageTitle').textContent='Push notifications';document.querySelector('#crumb').textContent='Communications / Push notifications';view.classList.remove('hidden');load()};
  function options(){if(!data)return[];return audience==='PINCODE'?data.pincodes||[]:audience==='CITY'?data.cities||[]:audience==='PROVIDER'?data.providers||[]:[]}
  function selectedCount(){
    if(audience==='USER')return selectedUser?1:0;
    if(audience==='ALL')return Number(data?.active_user_count||0);
    return Number(options().find(item=>String(item.value)===segment.value)?.count||0);
  }
  function updateAudience(){
    const previous=segment.value;
    segmentField.classList.toggle('hidden',['ALL','USER'].includes(audience));
    phoneField.classList.toggle('hidden',audience!=='USER');
    segment.innerHTML=options().map(item=>`<option value="${escape(item.value)}">${escape(item.label)} (${Number(item.count||0)} customers)</option>`).join('');
    if(options().some(item=>String(item.value)===previous))segment.value=previous;
    validate();
  }
  function validate(){
    const count=selectedCount(),valid=title.value.trim()&&message.value.trim()&&count>0&&(audience==='ALL'||audience==='USER'&&selectedUser||segment.value);
    send.disabled=busy||loading||!valid;
    recipientCount.textContent=`${count} active customer${count===1?'':'s'} selected`;
    view.querySelector('#notificationRecipientHint').textContent=audience==='PINCODE'?'All currently approved service pincodes are listed. Counts use customer addresses, not the kitchen’s coverage. Zero means no matching customers.':'Audience is checked again when you send.';
    view.querySelector('#notificationCharacters').textContent=message.value.length;
    view.querySelector('#notificationPreviewTitle').textContent=title.value.trim()||'Zomeal';
    view.querySelector('#notificationPreviewMessage').textContent=message.value.trim()||'Your message will appear here.';
    send.textContent=busy?'Sending…':count?`Send to ${count} customer${count===1?'':'s'}`:'No recipients available';
  }
  function customerDetails(user){
    return `<b>${escape(user.full_name||'Name not saved')}</b><small>Phone: ${escape(user.phone||'Not saved')}</small><small>Pincode: ${escape(user.pincode||'Not saved')}</small><small>City: ${escape(user.city||'Not saved')}</small><small class="notification-user-id">User ID: ${escape(user.user_id)}</small><em>${escape(user.provider_name||'No active provider')}</em>`;
  }
  function render(){
    updateAudience();
    view.querySelector('#notificationUserTotal').textContent=`${data?.active_user_count||0} active customer accounts · Showing ${data?.users?.length||0}. Missing fields mean they were not saved; no details are invented.`;
    view.querySelector('#notificationUserList').innerHTML=(data?.users||[]).map(user=>`<article><span>${escape((user.full_name||'?').charAt(0).toUpperCase())}</span><div>${customerDetails(user)}</div></article>`).join('')||'<p class="notification-empty">No active users match this search.</p>';
    view.querySelector('#notificationHistory').innerHTML=(data?.campaigns||[]).map(item=>`<article><div><b>${escape(item.title)}</b><small>${escape(item.audience_label||item.audience_type)} · ${new Date(item.created_at).toLocaleString('en-IN')}</small></div><strong>${Number(item.sent_count||0)}/${Number(item.recipient_count||0)} sent</strong></article>`).join('')||'<p class="notification-empty">No notification campaigns yet.</p>';
  }
  async function load(){
    const revision=++loadRevision;loading=true;validate();
    try{const result=await api.notificationCenter(view.querySelector('#notificationUserSearch').value.trim());if(revision===loadRevision){data=result;render()}}
    catch(error){if(revision===loadRevision){data=null;render();window.showToast?.(error.message)}}
    finally{if(revision===loadRevision){loading=false;validate()}}
  }
  function resetLookup(){++lookupRevision;selectedUser=null;phoneResults.replaceChildren();validate()}
  phone.oninput=resetLookup;
  view.querySelector('#notificationFindPhone').onclick=async()=>{
    resetLookup();const revision=lookupRevision;
    phoneResults.textContent='Looking up this number…';
    try{
      const users=await api.notificationPhoneCustomer(phone.value.trim());
      if(revision!==lookupRevision)return;
      phoneResults.innerHTML=users.length?`<p>${users.length===1?'Confirm the recipient below.':'More than one account uses this number. Select the correct user ID.'}</p>`:'<p>No active customer matches this number.</p>';
      for(const user of users){
        const card=document.createElement('article');card.innerHTML=customerDetails(user);
        const choose=document.createElement('button');choose.type='button';choose.textContent='Select this customer';
        choose.onclick=()=>{selectedUser=user;phoneResults.querySelectorAll('button').forEach(b=>{b.textContent=b===choose?'Selected':'Select this customer';b.setAttribute('aria-pressed',String(b===choose))});validate()};
        card.append(choose);phoneResults.append(card);
      }
    }catch(error){if(revision===lookupRevision)phoneResults.textContent=error.message}
  };
  phone.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();view.querySelector('#notificationFindPhone').click()}};
  types.querySelectorAll('button').forEach(button=>button.onclick=()=>{
    types.querySelectorAll('button').forEach(item=>item.classList.toggle('active',item===button));
    audience=button.dataset.type;resetLookup();updateAudience();
  });
  segment.onchange=validate;title.oninput=validate;message.oninput=validate;
  view.querySelector('#notificationUserSearch').placeholder='Search name, phone, pincode or user ID';
  view.querySelector('#notificationUserSearch').oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(load,300)};
  view.querySelector('#refreshNotificationCentre').onclick=load;
  send.onclick=async()=>{
    if(send.disabled||busy)return;
    const count=selectedCount(),targetValue=audience==='ALL'?null:audience==='USER'?selectedUser?.user_id:segment.value;
    const targetLabel=audience==='ALL'?'all active customers':audience==='USER'?`${selectedUser.full_name||'Name not saved'} · ${selectedUser.phone||'Phone not saved'} · ${selectedUser.user_id}`:segment.selectedOptions[0]?.textContent;
    if(!count||!confirm(`Send “${title.value.trim()}” to ${count} customer(s) in ${targetLabel}?`))return;
    busy=true;validate();
    try{
      const result=await api.sendPushNotification({audience:'TARGETED_CUSTOMERS',target_type:audience,target_value:targetValue,app_kind:'CUSTOMER',title:title.value.trim(),body:message.value.trim(),destination:'notifications'});
      window.showToast?.(`Saved for ${result.recipients||0} customers · Push sent to ${result.sent||0} device(s)`);
      title.value='';message.value='';await load();
    }catch(error){window.showToast?.(error.message)}finally{busy=false;validate()}
  };
})();
