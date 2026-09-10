/* Native scroll and pointer effects; no scroll hijacking or animation library. */
(() => {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const finePointer = matchMedia('(hover: hover) and (pointer: fine)');
  const root = document.documentElement;
  const stage = document.querySelector('.tiffin-stage');
  const visual = document.querySelector('.tiffin-visual');
  const phone = document.querySelector('.app-preview');
  if (!stage || !visual) return;
  let paused = reduced.matches, frame = 0, observer;
  const pause = document.createElement('button');
  pause.type = 'button'; pause.className = 'motion-toggle';
  pause.setAttribute('aria-pressed', String(paused));
  pause.textContent = paused ? 'Animations off' : 'Pause animations';
  stage.append(pause);
  const cue = document.createElement('p');
  cue.className = 'scroll-indicator'; cue.innerHTML = '<span aria-hidden="true"></span>Scroll to unpack your meal';
  stage.insertBefore(cue, pause);
  const groups = ['.section-heading','.promise','.plan-card','.plan-note','.steps article','.control-copy','.app-preview','.provider-cta-inner','.faq-list details','.launch-inner'];
  const elements = [...document.querySelectorAll(groups.join(','))];
  function revealSetup() {
    if (!('IntersectionObserver' in window)) return;
    root.classList.add('motion-ready');
    observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) { entry.target.classList.add('scene-visible'); observer.unobserve(entry.target); }
      });
    }, {threshold:.08,rootMargin:'0px 0px 25px 0px'});
    elements.forEach((el,index) => {
      el.classList.add('scene-enter'); el.style.setProperty('--scene-delay',`${index%3*75}ms`);
      observer.observe(el);
    });
  }
  function paint() {
    frame = 0; if (paused || reduced.matches || document.hidden) return;
    const vh = innerHeight;
    const box = stage.getBoundingClientRect();
    if (box.bottom > 0 && box.top < vh) {
      // Derive progress from the stage position, including when it starts below
      // the fold on phones. The site's normal scrolling stays untouched.
      const start = Math.min(vh * .65, box.top + window.scrollY);
      const progress = Math.max(0, Math.min(1, (start - box.top) / Math.max(150, box.height * .55)));
      window.zomealTiffin?.scrub(progress);
    }
    if (phone && finePointer.matches) {
      const p = phone.getBoundingClientRect();
      if (p.bottom>0 && p.top<vh) phone.style.setProperty('--phone-y',`${Math.max(-16,Math.min(16,(p.top-vh*.4)*.045))}px`);
    }
  }
  function schedule() { if (!frame && !paused) frame=requestAnimationFrame(paint); }
  function resetTilt() { visual.style.setProperty('--tilt-x','0deg'); visual.style.setProperty('--tilt-y','0deg'); }
  stage.addEventListener('pointermove',e => {
    if (paused || reduced.matches || !finePointer.matches) return;
    const b=stage.getBoundingClientRect(), x=(e.clientX-b.left)/b.width-.5, y=(e.clientY-b.top)/b.height-.5;
    visual.style.setProperty('--tilt-x',`${x*9}deg`); visual.style.setProperty('--tilt-y',`${-y*7}deg`);
    const hero=document.querySelector('.hero'); hero.style.setProperty('--glow-x',`${x*35}px`); hero.style.setProperty('--glow-y',`${y*25}px`);
  },{passive:true});
  stage.addEventListener('pointerleave',resetTilt);
  function applyPause() {
    root.classList.toggle('motion-paused',paused);
    pause.setAttribute('aria-pressed',String(paused));
    pause.textContent=paused ? (reduced.matches ? 'Animations off' : 'Resume animations') : 'Pause animations';
    pause.disabled=reduced.matches; cue.hidden=paused;
    if(paused){cancelAnimationFrame(frame);frame=0;resetTilt();}else schedule();
  }
  pause.addEventListener('click',()=>{paused=!paused;applyPause();});
  reduced.addEventListener('change',()=>{paused=reduced.matches;applyPause();});
  window.addEventListener('scroll',schedule,{passive:true});
  window.addEventListener('resize',schedule,{passive:true});
  document.addEventListener('visibilitychange',()=>{
    root.classList.toggle('motion-paused',paused||document.hidden);
    if(!document.hidden)schedule();
  });
  revealSetup(); applyPause();
})();
