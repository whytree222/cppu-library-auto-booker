(() => {
  const x = Math.round(window.innerWidth * __X_FRACTION__);
  const y = Math.round(window.innerHeight * __Y_FRACTION__);
  const hit = document.elementFromPoint(x, y);
  if (!hit || hit === document.body || hit === document.documentElement) return '';

  let seat = hit;
  for (let i = 0; i < 5 && seat.parentElement; i++) {
    const attributes = [...seat.attributes].map(a => `${a.name}=${a.value}`).join(' ');
    if (/seat|table|chair|座位|onclick|onmousedown/i.test(attributes)) break;
    if (seat.matches('svg, canvas, [role="button"], button')) break;
    seat = seat.parentElement;
  }

  const parts = [];
  for (let el = seat; el && el !== document.body; el = el.parentElement) {
    if (el.id) {
      parts.unshift(`#${CSS.escape(el.id)}`);
      break;
    }
    const siblings = [...el.parentElement.children].filter(item => item.tagName === el.tagName);
    const position = siblings.indexOf(el) + 1;
    parts.unshift(`${el.tagName.toLowerCase()}:nth-of-type(${position})`);
  }
  const selector = parts.join(' > ');
  const label = seat.getAttribute('title') || seat.getAttribute('aria-label') ||
    seat.getAttribute('data-seat-no') || seat.getAttribute('data-table-no') ||
    seat.textContent?.trim().slice(0, 40) || '';
  return JSON.stringify({ label, selector, x: __X_FRACTION__, y: __Y_FRACTION__ });
})();
