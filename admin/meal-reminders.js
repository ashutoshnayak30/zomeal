/* One personalised reminder per customer / service day / meal. */
(() => {
  const api=window.ZomealAPI, view=document.querySelector('#notificationsView'), dashboard=document.querySelector('#dashboard');
  if(!api||!view||!dashboard)return;
  const panel=document.createElement('section'); panel.className='panel campaign-manager meal-reminder-manager';
  panel.innerHTML='<div class="meal-reminder-heading"><div><span class="meal-reminder-eyebrow">CUSTOMER COMMUNICATIONS · IST</span><h2>A timely reminder. A delicious meal.</h2><p>Choose when lunch and dinner reminders reach your customers.</p></div><button type="button" data-refresh>↻ Refresh</button></div><div class="meal-reminder-note">One reminder per meal, per day · Phone + app inbox · Skipped meals stay skipped</div><form><div data-fields class="meal-reminder-grid"></div><div class="meal-reminder-footer"><p>Saving a new time will not resend a reminder already sent today. Payment alerts are separate.</p><button type="submit" disabled>Save reminder settings</button></div></form><p role="status" aria-live="polite"></p>';
  view.prepend(panel);
  const fields=panel.querySelector('[data-fields]'), status=panel.querySelector('[role=status]'), save=panel.querySelector('[type=submit]');
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let busy=false, revision=0;
  async function load(){
    if(busy)return; const current=++revision; busy=true; save.disabled=true;
    try {
      const result=await api.mealReminders();
      if(current!==revision)return;
      fields.innerHTML=(result.items||[]).map(s=>'<fieldset data-slot="'+esc(s.slot)+'"><legend>'+esc(s.slot==='LUNCH'?'☀ Lunch reminder':'☾ Dinner reminder')+'</legend><label class="campaign-checkbox"><input name="enabled" type="checkbox" '+(s.enabled?'checked':'')+'> Send every day</label><label>Send time · India (IST)<input name="send_time" type="time" min="08:00" max="21:00" step="60" required value="'+esc(String(s.send_time).slice(0,5))+'"></label><small>Choose between 8:00 AM and 9:00 PM IST.</small>'+[['title','Scheduled meal title',100],['message','Scheduled meal message',500],['low_title','Low-wallet title',100],['low_message','Low-wallet message',500]].map(([key,label,max])=>'<label>'+label+'<textarea required name="'+key+'" maxlength="'+max+'" rows="'+(key.endsWith('title')?'1':'3')+'">'+esc(s[key])+'</textarea></label>').join('')+'</fieldset>').join('');
      status.textContent='Available placeholders: {main_course}, {provider}, {meal}. The low-wallet message replaces the normal message; it is not an extra notification.';
      save.disabled=false;
    } catch(e){if(current===revision)status.textContent='Could not load settings: '+e.message;} finally{busy=false;}
  }
  panel.querySelector('[data-refresh]').onclick=load;
  panel.querySelector('form').onsubmit=async e=>{
    e.preventDefault();if(busy||!fields.children.length)return;
    if(!e.target.reportValidity())return;
    const payload=[...fields.children].map(f=>Object.fromEntries([['slot',f.dataset.slot],['send_time',f.querySelector('[name="send_time"]').value],...['enabled','title','message','low_title','low_message'].map(k=>[k,k==='enabled'?f.querySelector('[name="'+k+'"]').checked:f.querySelector('[name="'+k+'"]').value.trim()])]));
    if(!confirm('Save daily meal reminder settings?\n'+payload.map(s=>s.slot+': '+(s.enabled?s.send_time+' IST':'Disabled')).join('\n')+'\nAlready-sent reminders will not be repeated.'))return;
    busy=true;save.disabled=true;
    try {await api.mealReminders(payload);status.textContent='Saved. Each customer can receive at most one meal reminder per slot per day. No meal status or wallet balance was changed.';}
    catch(err){status.textContent='Not saved: '+err.message;}finally{busy=false;save.disabled=false;}
  };
  const observe=()=>{if(dashboard.classList.contains('hidden')){revision++;fields.textContent='';save.disabled=true;}else if(!view.classList.contains('hidden'))load();};
  new MutationObserver(observe).observe(view,{attributes:true,attributeFilter:['class']});
  new MutationObserver(observe).observe(dashboard,{attributes:true,attributeFilter:['class']});
})();
