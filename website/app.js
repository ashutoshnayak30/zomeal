"use strict";
const cfg = window.ZOMEAL_WEB_CONFIG || {};
const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];
const grid = $('#provider-grid'), state = $('#search-state'), count = $('#result-count');
const providerDialog = $('#provider-dialog'), leadDialog = $('#lead-dialog');
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money = p => `₹${(Number(p || 0) / 100).toLocaleString('en-IN', {maximumFractionDigits: 2})}`;
const label = s => String(s || '').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const validPin = value => /^[1-9][0-9]{5}$/.test(value);
let latestProviders = [], searchController, lastPin = '';
function photoUrl(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && url.origin === new URL(cfg.supabaseUrl).origin ? url.href : ''; } catch { return ''; }
}
function period(p) {
  const duration = Number(p.duration_days || p.plan_days || (String(p.name || '').toLowerCase().includes('week') ? 7 : 30));
  return duration === 7 ? 'week' : duration === 30 ? 'month' : `${duration} days`;
}
function message(title, text, retry = false) {
  grid.replaceChildren(); state.hidden = false; count.hidden = true;
  state.innerHTML = `<div class="search-tiffin" aria-hidden="true">⌖</div><h3>${escapeHtml(title)}</h3><p>${escapeHtml(text)}</p>${retry ? '<button type="button" class="button button-dark" data-retry>Try again</button>' : ''}`;
}
function imageFallbacks(root) {
  $$('img[data-provider-image]', root).forEach(img => img.addEventListener('error', () => {
    const fallback = document.createElement('div'); fallback.className = 'fallback'; fallback.textContent = 'Photo unavailable';
    fallback.style.fontSize = '1rem'; img.replaceWith(fallback);
  }, {once:true}));
}
function card(p) {
  const packages = (p.packages || []).filter(x => Number(x.price_paise) > 0);
  const cheapest = [...packages].sort((a,b) => Number(a.price_paise) - Number(b.price_paise))[0];
  const img = photoUrl(p.primary_photo_url || p.meal_photo_url);
  return `<article class="provider-card"><div class="provider-photo">${img ? `<img data-provider-image src="${escapeHtml(img)}" alt="${escapeHtml(p.display_name)}" loading="lazy" decoding="async">` : '<div class="fallback" aria-label="Kitchen photo unavailable">⌖</div>'}<span class="verified">✓ ZOMEAL REVIEWED</span></div><div class="provider-body"><h3>${escapeHtml(p.display_name)}</h3><p>⌖ ${escapeHtml(p.locality || p.city || 'Serving your pincode')}</p><div class="chips"><span class="chip">${escapeHtml(label(p.dietary_type))}</span><span class="chip">${packages.length} plan${packages.length === 1 ? '' : 's'}</span></div><div class="price-line"><div><small>Plans from</small><br><b>${cheapest ? money(cheapest.price_paise) : 'See details'}</b>${cheapest ? `<small> / ${period(cheapest)}</small>` : ''}</div><button type="button" data-provider="${escapeHtml(p.provider_id)}">View details</button></div></div></article>`;
}
async function search(pin) {
  searchController?.abort();
  if (!validPin(pin)) { message('Check your pincode', 'Please enter a valid six-digit Indian pincode.'); $('#providers').scrollIntoView({block:'start'}); return; }
  lastPin = pin; $('#hero-pincode').value = pin; $('#provider-pincode').value = pin;
  searchController = new AbortController(); const controller = searchController;
  const timeout = setTimeout(() => controller.abort(), 15000);
  grid.replaceChildren(); count.hidden = true; state.hidden = false;
  state.innerHTML = '<div class="loading-ring"></div><h3>Finding your nearby kitchens…</h3><p>Checking availability for your pincode.</p>';
  $('#providers').scrollIntoView({block:'start'});
  try {
    const res = await fetch(`${cfg.supabaseUrl}/functions/v1/website-provider-search`, {method:'POST', headers:{'Content-Type':'application/json',apikey:cfg.supabasePublishableKey}, body:JSON.stringify({pincode:pin}), signal:controller.signal});
    const body = await res.json(); if (!res.ok) throw new Error('Search unavailable');
    if (controller !== searchController) return;
    latestProviders = Array.isArray(body.providers) ? body.providers : [];
    if (!latestProviders.length) {
      message('We’re still growing in your area', `No approved kitchens currently serve ${pin}. Leave your details and we can let you know about availability.`);
      const button = document.createElement('button'); button.type = 'button'; button.className = 'button button-dark'; button.dataset.openLead = ''; button.textContent = 'Notify me about my area'; state.append(button); return;
    }
    state.hidden = true; count.hidden = false; count.textContent = `${latestProviders.length} kitchen${latestProviders.length === 1 ? '' : 's'} found`;
    grid.innerHTML = latestProviders.map(card).join(''); imageFallbacks(grid);
  } catch (error) {
    if (controller !== searchController) return;
    message('We couldn’t load kitchens', error.name === 'AbortError' ? 'The request took too long. Please check your connection and try again.' : 'Please check your connection and try again in a moment.', true);
  } finally { clearTimeout(timeout); }
}
function showProvider(id) {
  const p = latestProviders.find(x => String(x.provider_id) === id); if (!p) return;
  const photo = photoUrl(p.meal_photo_url || p.primary_photo_url);
  const packages = (p.packages || []).map(x => `<div class="detail-package"><b>${escapeHtml(x.name)}</b><br><span>${money(x.price_paise)} / ${period(x)}</span></div>`).join('');
  const menu = (p.weekly_menu || []).map(row => `<div class="menu-day"><b>${escapeHtml(days[Number(row.day_of_week || 1)-1] || '')}</b><span>${escapeHtml(label(row.meal_slot))}</span><span>${escapeHtml((row.items || []).map(i => i.name).join(' · '))}</span></div>`).join('');
  $('#provider-detail').innerHTML = `<div class="detail-cover">${photo ? `<img data-provider-image src="${escapeHtml(photo)}" alt="Meal from ${escapeHtml(p.display_name)}">` : '⌖'}</div><div class="detail-title"><h2>${escapeHtml(p.display_name)}</h2><p>${escapeHtml([p.locality,p.city].filter(Boolean).join(', '))} · Zomeal reviewed</p></div><h3>Available packages</h3><div class="detail-packages">${packages || 'Package details are not available yet.'}</div><div class="detail-menu"><h3>Weekly menu</h3>${menu || '<p>Menu details are being prepared.</p>'}</div><p>Manage subscriptions and payments in the Zomeal Android app. Join early access for testing availability.</p><button type="button" class="submit-lead" data-open-lead>Join early access</button>`;
  imageFallbacks(providerDialog); providerDialog.showModal();
}
$('#hero-search').addEventListener('submit', e => { e.preventDefault(); search($('#hero-pincode').value.trim()); });
$('#provider-search').addEventListener('submit', e => { e.preventDefault(); search($('#provider-pincode').value.trim()); });
$$('input[inputmode="numeric"]').forEach(input => input.addEventListener('input', () => { input.value = input.value.replace(/\D/g,''); }));
function openLead(provider = false) {
  if (providerDialog.open) providerDialog.close();
  const pin = $('#hero-pincode').value.trim(); if (validPin(pin)) $('[name="lead_pincode"]').value = pin;
  $('[name="lead_interest"]').value = provider ? 'PROVIDER' : 'CUSTOMER'; $('#lead-message').textContent = '';
  if (!leadDialog.open) leadDialog.showModal();
}
document.addEventListener('click', e => {
  if (e.target.closest('[data-open-provider-lead]')) { e.preventDefault(); openLead(true); }
  else if (e.target.closest('[data-open-lead]')) { e.preventDefault(); openLead(); }
  const detail = e.target.closest('[data-provider]'); if (detail) showProvider(detail.dataset.provider);
  if (e.target.closest('[data-retry]')) search(lastPin);
  if (e.target.closest('[data-close-dialog]')) e.target.closest('dialog').close();
});
$$('dialog').forEach(dialog => dialog.addEventListener('click', e => {
  const r = dialog.getBoundingClientRect();
  if (e.target === dialog && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom)) dialog.close();
}));
$('#lead-form').addEventListener('submit', async e => {
  e.preventDefault(); const form = e.currentTarget, button = $('.submit-lead',form), status = $('#lead-message');
  const data = Object.fromEntries(new FormData(form)); data.accepted_consent = Boolean(new FormData(form).get('accepted_consent'));
  data.lead_name = data.lead_name.trim(); data.lead_phone = data.lead_phone.trim(); data.lead_pincode = data.lead_pincode.trim();
  if (data.lead_name.length < 2 || !/^[6-9][0-9]{9}$/.test(data.lead_phone) || !validPin(data.lead_pincode) || !data.accepted_consent) { status.textContent = 'Please enter your name, a valid 10-digit mobile number and 6-digit pincode, and accept the contact consent.'; return; }
  button.disabled = true; status.textContent = 'Saving your details…';
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const res = await fetch(`${cfg.supabaseUrl}/rest/v1/rpc/website_join_waitlist`, {method:'POST',headers:{apikey:cfg.supabasePublishableKey,'Content-Type':'application/json'},body:JSON.stringify(data),signal:controller.signal});
    if (!res.ok) throw new Error('Could not save');
    status.textContent = 'You’re on the list. We’ll contact you about availability.'; form.reset();
  } catch { status.textContent = 'Your details were not confirmed. Please check your connection and try again.'; }
  finally { clearTimeout(timeout); button.disabled = false; }
});

// The tiffin opens once as the visitor scrolls; its button can always replay it.
const visual = $('#tiffin-visual'), toggle = $('#tiffin-toggle'), motion = matchMedia('(prefers-reduced-motion: reduce)');
let openness = 0, target = 0, animation = 0, manual = false, revealed = false;
function setOpen(open) {
  target = open ? 1 : 0; visual.dataset.state = open ? 'open' : 'closed'; toggle.setAttribute('aria-expanded', String(open));
  $('.toggle-icon',toggle).textContent = open ? '−' : '+';
  $('.toggle-copy b',toggle).textContent = open ? 'Close the tiffin' : 'Open today’s tiffin';
  $('#tiffin-state-label').textContent = open ? 'A little of everything you love' : 'Ready when you are';
  $('.tiffin-open').setAttribute('aria-hidden', String(!open)); $('.tiffin-closed').setAttribute('aria-hidden',String(open));
  cancelAnimationFrame(animation);
  if (motion.matches) { openness = target; visual.style.setProperty('--open',target); return; }
  const start = performance.now(), from = openness;
  const frame = now => { const t = Math.min((now-start)/1050,1), eased = 1-Math.pow(1-t,3); openness = from+(target-from)*eased; visual.style.setProperty('--open',openness); if (t<1) animation=requestAnimationFrame(frame); };
  animation=requestAnimationFrame(frame);
}
toggle.addEventListener('click', () => { manual = true; setOpen(target < .5); });
window.addEventListener('scroll', () => { if (!manual && !revealed && window.scrollY > 75 && !motion.matches) { revealed = true; setOpen(true); } }, {passive:true});
motion.addEventListener('change', () => setOpen(target > .5));
setOpen(false);
const menuToggle = $('.menu-toggle'), navigation = $('#main-navigation');
function closeMenu() { navigation.removeAttribute('data-open'); menuToggle.setAttribute('aria-expanded','false'); }
menuToggle.addEventListener('click', () => { const open = menuToggle.getAttribute('aria-expanded') !== 'true'; menuToggle.setAttribute('aria-expanded',String(open)); navigation.toggleAttribute('data-open',open); });
$$('a',navigation).forEach(a => a.addEventListener('click',closeMenu));
document.addEventListener('keydown',e => { if (e.key === 'Escape') closeMenu(); });
