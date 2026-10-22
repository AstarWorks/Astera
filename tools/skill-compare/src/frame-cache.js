// A shared, bounded queue prevents the two image sequences from starving each other.
export function createFrameCache(onChange, { concurrency = 6, capacity = 48 } = {}) {
  const entries = new Map();
  let active = 0, wanted = [];
  function pump() {
    for (const url of wanted) {
      if (active >= concurrency) break;
      const entry = entries.get(url);
      if (!entry || entry.state !== 'queued') continue;
      entry.state = 'loading'; active++;
      const img = new Image();
      entry.img = img;
      const finish = (state) => {
        clearTimeout(entry.timeout);
        img.onload = img.onerror = null;
        entry.state = state; active--; onChange(); pump();
      };
      img.onload = () => finish('ready');
      img.onerror = () => finish('error');
      entry.timeout = setTimeout(() => { img.src = ''; finish('error'); }, 20000);
      img.src = url;
    }
  }
  return {
    want(urls) {
      wanted = [...new Set(urls.filter(Boolean))];
      for (const url of wanted) {
        const old = entries.get(url);
        if (old) { entries.delete(url); entries.set(url, old); }
        else entries.set(url, { state: 'queued' });
      }
      for (const [url, entry] of entries) {
        if (entries.size <= capacity) break;
        if (!wanted.includes(url) && entry.state !== 'loading') entries.delete(url);
      }
      pump();
    },
    get(url) { return entries.get(url); },
    retry() { for (const entry of entries.values()) if (entry.state === 'error') entry.state = 'queued'; pump(); },
  };
}
