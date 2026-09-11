(() => {
  const api=window.ZomealAPI, nav=document.querySelector('#homeBannersNav'), dashboard=document.querySelector('#dashboard');
  const view=document.createElement('section');view.id='homeBannersView';view.className='view hidden';
  view.innerHTML=`<div class="banner-heading"><div><h2>Home banners</h2><p>Upload artwork, choose where a tap goes, then publish to the users app.</p></div><button id="bannerRefresh">Refresh list</button></div>
  <p class="banner-notice">Published changes appear within about 30 seconds while Home is open, or when customers reopen the app. The new banner-enabled APK is required once. Upload public marketing images only.</p>
  <div class="banner-layout"><form id="bannerForm" class="panel banner-editor">
  <h3 id="bannerEditorTitle">New banner</h3>
  <label>Banner title / image description<input name="title" required maxlength="100" placeholder="Example: Try a monthly meal plan"></label>
  <label>Banner image<input name="image" type="file" accept="image/png,image/jpeg,image/webp"><small>Recommended 1200 × 450 px (8:3). JPEG, PNG or WebP, up to 5 MB. Images are resized and compressed to WebP.</small></label>
  <div class="banner-preview"><img id="bannerPreview" alt="Banner preview" hidden><span id="bannerPreviewEmpty">Your image preview</span></div>
  <p id="bannerImageInfo" class="banner-muted"></p>
  <label>Open when tapped<select name="destination"><option value="NONE">Image only — no action</option><option value="PROVIDERS">Browse service providers</option><option value="WALLET">Wallet / recharge</option><option value="REFERRALS">Referral rewards in wallet</option><option value="PLAN">My Plan (or choose a plan)</option><option value="HTTPS">Website link (HTTPS)</option></select></label>
  <label id="bannerLinkField" hidden>Website URL<input name="destination_value" type="url" maxlength="1000" placeholder="https://zomeal.in/"></label>
  <label>Who sees this?<select name="audience"><option value="ALL">All signed-in customers</option><option value="SUBSCRIBED">Current subscribers</option><option value="NO_PLAN">Customers without a current plan</option><option value="WEEKLY">Weekly subscribers</option><option value="MONTHLY">Monthly subscribers</option></select></label>
  <div class="banner-fields"><label>Display order<input name="sort_order" type="number" min="0" max="9999" value="0" required><small>Lower numbers appear first.</small></label><label class="banner-check"><input name="enabled" type="checkbox"> Publish / enabled</label></div>
  <label>Start date and time (optional)<input name="starts_at" type="datetime-local"></label><label>End date and time (optional)<input name="ends_at" type="datetime-local"></label>
  <small id="bannerTimezone"></small><p id="bannerFormStatus" role="status" aria-live="polite"></p>
  <div class="banner-actions"><button type="submit" class="primary" id="bannerSave">Save banner</button><button type="button" id="bannerReset">New / clear form</button></div></form>
  <section class="panel banner-library"><h3>Banner library</h3><p>Up to 10 matching banners are returned per customer. We recommend keeping 3 or fewer published for each audience.</p><div id="bannerList" aria-live="polite">Loading…</div></section></div>`;
  document.querySelector('#overviewView').parentElement.append(view);
  const form=view.querySelector('form'), fields=form.elements, status=view.querySelector('#bannerFormStatus'), preview=view.querySelector('#bannerPreview'), empty=view.querySelector('#bannerPreviewEmpty'), list=view.querySelector('#bannerList');
  let rows=[],editing=null,blob=null,previewUrl=null,selectedImagePath='',imageVersion=0,busy=false,loadingImage=false,revision=0,accessRevision=0;
  const escape=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const localDate=value=>{if(!value)return '';const d=new Date(value);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16)};
  const instant=value=>value?new Date(value).toISOString():null;
  function showImage(url){preview.hidden=!url;empty.hidden=!!url;if(url)preview.src=url;else preview.removeAttribute('src')}
  function releasePreview(){if(previewUrl){URL.revokeObjectURL(previewUrl);previewUrl=null}}
  function refreshLink(){const isLink=fields.destination.value==='HTTPS';view.querySelector('#bannerLinkField').hidden=!isLink;fields.destination_value.required=isLink;if(!isLink)fields.destination_value.value=''}
  function lock(value){busy=value;Array.from(fields).forEach(el=>el.disabled=value);view.querySelector('#bannerSave').disabled=value||loadingImage}
  function reset(){if(busy)return;imageVersion++;loadingImage=false;editing=null;blob=null;selectedImagePath='';form.reset();releasePreview();showImage('');status.textContent='';view.querySelector('#bannerImageInfo').textContent='';view.querySelector('#bannerEditorTitle').textContent='New banner';view.querySelector('#bannerSave').disabled=false;refreshLink()}
  function edit(row){if(busy)return;reset();editing=row;selectedImagePath=row.image_path;for(const key of ['title','destination','destination_value','audience','sort_order'])fields[key].value=row[key];fields.enabled.checked=row.enabled;fields.starts_at.value=localDate(row.starts_at);fields.ends_at.value=localDate(row.ends_at);showImage(api.homeBannerUrl(row.image_path));view.querySelector('#bannerEditorTitle').textContent='Edit banner';refreshLink();form.scrollIntoView({behavior:'smooth',block:'start'})}
  function publication(row){if(!row.enabled)return 'Draft / disabled';const now=Date.now();if(row.ends_at&&Date.parse(row.ends_at)<=now)return 'Expired';if(row.starts_at&&Date.parse(row.starts_at)>now)return 'Scheduled';return 'Published'}
  function render(){list.innerHTML=rows.map(row=>`<article class="banner-item"><img src="${escape(api.homeBannerUrl(row.image_path))}" alt="${escape(row.title)}" loading="lazy"><div><b>${escape(row.title)}</b><span>${publication(row)} · ${escape(row.audience)} · Order ${Number(row.sort_order)}</span><small>${escape(row.destination)}${row.destination_value?' · '+escape(row.destination_value):''}</small><small>Updated ${escape(new Date(row.updated_at).toLocaleString())}</small><div class="banner-actions"><button data-edit="${escape(row.id)}">Edit</button><button data-delete="${escape(row.id)}">Delete</button></div></div></article>`).join('')||'<p>No banners yet. Upload an image and save your first banner.</p>'}
  async function load(){const current=++revision;try{const result=await api.homeBanners();if(current!==revision)return;rows=result||[];render()}catch(e){if(current===revision)list.textContent=e.message}}
  async function checkAccess(){const current=++accessRevision;if(dashboard.classList.contains('hidden')){nav.classList.add('hidden');view.classList.add('hidden');return}try{const allowed=api.configured&&await api.accountAccess();if(current===accessRevision)nav.classList.toggle('hidden',!allowed)}catch{nav.classList.add('hidden')}}
  new MutationObserver(checkAccess).observe(dashboard,{attributes:true,attributeFilter:['class']});checkAccess();
  nav.onclick=event=>{event.stopPropagation();document.querySelectorAll('.view').forEach(el=>el.classList.add('hidden'));document.querySelectorAll('#nav button').forEach(el=>el.classList.toggle('active',el===nav));document.querySelector('.sidebar').classList.remove('open');document.querySelector('#pageTitle').textContent='Home banners';document.querySelector('#crumb').textContent='Communications / Home banners';view.classList.remove('hidden');load()};
  view.querySelector('#bannerTimezone').textContent='Dates use your browser timezone: '+Intl.DateTimeFormat().resolvedOptions().timeZone+'. Leave blank for no time limit.';
  fields.destination.onchange=refreshLink;view.querySelector('#bannerReset').onclick=reset;view.querySelector('#bannerRefresh').onclick=load;
  fields.image.onchange=async()=>{
    const current=++imageVersion,file=fields.image.files[0];blob=null;selectedImagePath=editing?.image_path||'';releasePreview();showImage(api.homeBannerUrl(selectedImagePath));
    if(!file){loadingImage=false;view.querySelector('#bannerSave').disabled=busy;return}
    loadingImage=true;view.querySelector('#bannerSave').disabled=true;status.textContent='Preparing image…';
    try{
      if(!['image/jpeg','image/png','image/webp'].includes(file.type)||file.size>5242880)throw new Error('Choose a JPEG, PNG or WebP no larger than 5 MB.');
      const image=await createImageBitmap(file);try{
        if(image.width<600||image.height<200||image.width*image.height>40000000)throw new Error('Use an image at least 600 × 200 px and below 40 megapixels.');
        const ratio=image.width/image.height;if(Math.abs(ratio-8/3)>.15)throw new Error('Use an 8:3 landscape image, for example 1200 × 450 px. This prevents cropped text.');
        const canvas=document.createElement('canvas');canvas.width=1200;canvas.height=450;canvas.getContext('2d').drawImage(image,0,0,1200,450);
        const converted=await new Promise(resolve=>canvas.toBlob(resolve,'image/webp',.85));if(!converted||converted.type!=='image/webp'||converted.size>1048576)throw new Error('Could not optimise this image below 1 MB. Try a smaller image.');
        if(current!==imageVersion)return;blob=converted;selectedImagePath='';previewUrl=URL.createObjectURL(blob);showImage(previewUrl);view.querySelector('#bannerImageInfo').textContent=`1200 × 450 WebP · ${Math.ceil(blob.size/1024)} KB`;status.textContent='Image ready. Save to upload and publish.';
      }finally{image.close()}
    }catch(e){if(current===imageVersion){fields.image.value='';status.textContent=e.message}}
    finally{if(current===imageVersion){loadingImage=false;view.querySelector('#bannerSave').disabled=busy}}
  };
  form.onsubmit=async event=>{
    event.preventDefault();if(busy||loadingImage)return;
    try{
      if(!blob&&!selectedImagePath)throw new Error('Choose a banner image first.');
      const payload={id:editing?.id||null,title:fields.title.value.trim(),destination:fields.destination.value,destination_value:fields.destination.value==='HTTPS'?fields.destination_value.value.trim():'',audience:fields.audience.value,sort_order:Number(fields.sort_order.value),enabled:fields.enabled.checked,starts_at:instant(fields.starts_at.value),ends_at:instant(fields.ends_at.value)};
      if(!payload.title)throw new Error('Enter a banner title.');
      if(payload.destination==='HTTPS'){const u=new URL(payload.destination_value);if(u.protocol!=='https:'||u.username||u.password||(u.port&&u.port!=='443'))throw new Error('Use a public HTTPS link without credentials or a custom port.');}
      if(payload.starts_at&&payload.ends_at&&payload.ends_at<=payload.starts_at)throw new Error('End time must be later than start time.');
      lock(true);status.textContent='Saving…';if(blob){selectedImagePath=await api.uploadHomeBanner(blob);blob=null}payload.image_path=selectedImagePath;
      editing=await api.saveHomeBanner(payload,editing?.updated_at||null);status.textContent=editing.enabled?'Saved. Published banners refresh on Home within about 30 seconds, subject to audience and dates.':'Draft saved. Enable it when ready.';view.querySelector('#bannerEditorTitle').textContent='Edit banner';await load();
    }catch(e){status.textContent=e.message}finally{lock(false)}
  };
  list.onclick=async event=>{const button=event.target.closest('button');if(!button||busy)return;const row=rows.find(r=>r.id===(button.dataset.edit||button.dataset.delete));if(!row)return;if(button.dataset.edit){edit(row);return}if(!confirm(`Delete banner “${row.title}”? It will stop appearing after the next app refresh. The uploaded image and audit record are retained.`))return;lock(true);try{await api.deleteHomeBanner(row.id,row.updated_at);if(editing?.id===row.id){lock(false);reset()}await load()}catch(e){status.textContent=e.message}finally{lock(false)}};
})();
