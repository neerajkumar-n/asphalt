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
})();
