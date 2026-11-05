/* Asphalt site scripts shared by every page. */
(() => {
  /* copy buttons: copy the text of the element named by data-copy */
  document.querySelectorAll('[data-copy]').forEach(btn => {
    const src = document.getElementById(btn.dataset.copy);
    btn.addEventListener('click', () => {
      const done = l => { btn.textContent = l; setTimeout(() => btn.textContent = 'Copy', 1600); };
      const select = () => { const r = document.createRange(); r.selectNodeContents(src); const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r); done('Selected'); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(src.innerText).then(() => done('Copied'), select); else select();
    });
  });

  /* header: a firmer rule once the page scrolls, and the small-screen menu */
  const top = document.querySelector('.top');
  if (top) {
    const onScroll = () => top.classList.toggle('scrolled', scrollY > 8);
    addEventListener('scroll', onScroll, { passive: true }); onScroll();
    const btn = top.querySelector('.menu-btn');
    if (btn) {
      const set = open => { top.classList.toggle('open', open); btn.setAttribute('aria-expanded', open); btn.textContent = open ? 'Close' : 'Menu'; };
      btn.addEventListener('click', () => set(!top.classList.contains('open')));
      top.querySelectorAll('.nav a').forEach(a => a.addEventListener('click', () => set(false)));
      addEventListener('keydown', e => { if (e.key === 'Escape') set(false); });
    }
  }

  /* reveal sections as they enter the viewport */
  const items = document.querySelectorAll('[data-reveal]');
  if (!('IntersectionObserver' in window)) { items.forEach(el => el.classList.add('in')); return; }
  const io = new IntersectionObserver(entries => entries.forEach(e => {
    if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  }), { rootMargin: '0px 0px -8% 0px' });
  items.forEach(el => io.observe(el));
})();
