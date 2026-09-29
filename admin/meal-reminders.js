/* One personalised reminder per customer / service day / meal. */
(() => {
  const api=window.ZomealAPI, view=document.querySelector('#notificationsView'), dashboard=document.querySelector('#dashboard');
  if(!api||!view||!dashboard)return;
  const panel=document.createElement('section'); panel.className='panel campaign-manager';
  panel.innerHTML='<h2>Daily meal reminders</h2><p>One lunch reminder at 10:30 AM and one dinner reminder at 6:30 PM IST. Each appears in the app inbox and is queued for the phone. Payment alerts are separate. Skipped meals stay skipped.</p><button type="button" data-refresh>Load settings</button><form><div data-fields></div><button type="submit" disabled>Save meal reminders</button></form><p role="status" aria-live="polite"></p>';
  view.prepend(panel);
  const fields=panel.querySelector('[data-fields]'), status=panel.querySelector('[role=status]'), save=panel.querySelector('[type=submit]');
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let busy=false, revision=0;
  async function load(){
    if(busy)return; const current=++revision; busy=true; save.disabled=true;
    try {
      const result=await api.mealReminders();
      if(current!==revision)return;
      fields.innerHTML=(result.items||[]).map(s=>'<fieldset data-slot="'+esc(s.slot)+'"><legend>'+esc(s.slot==='LUNCH'?'Lunch · 10:30 AM IST':'Dinner · 6:30 PM IST')+'</legend><label class="campaign-checkbox"><input name="enabled" type="checkbox" '+(s.enabled?'checked':'')+'> Enable daily reminder</label>'+[['title','Scheduled meal title',100],['message','Scheduled meal message',500],['low_title','Low-wallet title',100],['low_message','Low-wallet message',500]].map(([key,label,max])=>'<label>'+label+'<textarea required name="'+key+'" maxlength="'+max+'" rows="2">'+esc(s[key])+'</textarea></label>').join('')+'</fieldset>').join('');
      status.textContent='Available placeholders: {main_course}, {provider}, {meal}. The low-wallet message replaces the normal message; it is not an extra notification.';
      save.disabled=false;
    } catch(e){if(current===revision)status.textContent='Could not load settings: '+e.message;} finally{busy=false;}
  }
  panel.querySelector('[data-refresh]').onclick=load;
  panel.querySelector('form').onsubmit=async e=>{
    e.preventDefault();if(busy||!fields.children.length)return;
    const payload=[...fields.children].map(f=>Object.fromEntries([['slot',f.dataset.slot],['send_time',f.dataset.slot==='LUNCH'?'10:30':'18:30'],...['enabled','title','message','low_title','low_message'].map(k=>[k,k==='enabled'?f.querySelector('[name="'+k+'"]').checked:f.querySelector('[name="'+k+'"]').value.trim()])]));
    if(!confirm('Save daily meal reminder settings? Enabled reminders are sent automatically at their IST times.'))return;
    busy=true;save.disabled=true;
    try {await api.mealReminders(payload);status.textContent='Saved. Each customer can receive at most one meal reminder per slot per day. No meal status or wallet balance was changed.';}
    catch(err){status.textContent='Not saved: '+err.message;}finally{busy=false;save.disabled=false;}
  };
  const observe=()=>{if(dashboard.classList.contains('hidden')){revision++;fields.textContent='';save.disabled=true;}else if(!view.classList.contains('hidden'))load();};
  new MutationObserver(observe).observe(view,{attributes:true,attributeFilter:['class']});
  new MutationObserver(observe).observe(dashboard,{attributes:true,attributeFilter:['class']});
})();
