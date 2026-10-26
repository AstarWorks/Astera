// Server-sent events for the live page: GET /api/events.
//   event "hello"   {boot, code}                       on connect (the page reloads itself when `code` differs from the one it loaded with)
//   event "change"  {cases: [id...], all: bool, dev}   something a case page shows changed (data, published revision, dev sha, freshness logs)
// The watcher only runs while somebody is connected; it polls cheap signatures (mtimes), never file contents.
export function makeEvents({ signatures, codeRev, devSha, intervalMs = 2000 }) {
  const clients = new Set();
  const enc = new TextEncoder();
  const boot = Date.now().toString(36);
  let prev = null, timer = null;

  const send = (c, event, data) => { try { c.enqueue(enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)); } catch { clients.delete(c); } };
  const broadcast = (event, data) => { for (const c of [...clients]) send(c, event, data); };

  function tick() {
    let sig;
    try { sig = signatures(); } catch { return; }
    if (prev) {
      const cases = [];
      let all = false;
      if (sig.global !== prev.global) all = true;
      for (const [id, v] of sig.cases) if (prev.cases.get(id) !== v) cases.push(id);
      for (const id of prev.cases.keys()) if (!sig.cases.has(id)) cases.push(id);
      if (all || cases.length) broadcast("change", { cases, all, dev: devSha() });
    }
    prev = sig;
  }

  return {
    response() {
      let ctl, ka;
      const stream = new ReadableStream({
        start(c) {
          ctl = c; clients.add(c);
          send(c, "hello", { boot, code: codeRev() });
          ka = setInterval(() => { try { c.enqueue(enc.encode(": ka\n\n")); } catch { clearInterval(ka); clients.delete(c); } }, 15000);
          if (!timer) { prev = null; tick(); timer = setInterval(tick, intervalMs); }
        },
        cancel() {
          clearInterval(ka); clients.delete(ctl);
          if (!clients.size && timer) { clearInterval(timer); timer = null; }
        },
      });
      return new Response(stream, { headers: { "content-type": "text/event-stream", "cache-control": "no-store", connection: "keep-alive", "x-accel-buffering": "no" } });
    },
    broadcast,
  };
}
