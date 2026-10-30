// Author-only review views inside the collapsed 作者向け section: preview frame, hit-volume overlay, round notes.
// Data comes from /api/cases/<id> `views` (server.mjs reviewViews); everything is optional and the page works without it.
import { renderProv, renderFresh } from "./prov.js";
import { renderMarkdown } from "./markdown.js";

const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

/** ctx: { S, $, clamp, msToTick, tickToMs, castOf, seek } */
export function createViews(ctx) {
  const { S, $, clamp, msToTick, tickToMs, castOf, seek } = ctx;
  const V = () => S.data.views || {};
  const state = { overlayAt: new Map() };

  function setupRound() {
    const r = V().round, box = $("aRound");
    box.hidden = !r;
    if (!r) return;
    $("roundText").textContent = ""; $("roundProv").textContent = "";
    $("roundText").append(renderMarkdown(r.text, { images: r.images || [], imgBase: r.imgBase || "" }));
    renderProv($("roundProv"), { ...r.prov, published: true });
  }

  // ---- preview ----------------------------------------------------------------------------------------------------
  function previewFrame(ms) {
    const src = V().preview;
    if (!src) return null;
    const tick = msToTick(ms, castOf());
    const f = tick + (src.castFrame ?? 0), n = clamp(f, 0, src.count - 1);
    return { url: `${src.base}frame_${String(n).padStart(3, "0")}.${src.extension || "png"}?v=${encodeURIComponent(src.revision)}`, dim: f < 0 || f >= src.count, tick, n };
  }
  function setupPreview() {
    const src = V().preview;
    if (!src) return;
    const box = $("pvprov"); box.textContent = ""; renderProv(box, src.prov);
    const warn = [];
    if (src.referenceStale) warn.push("参照映像の revision がこのプレビューと異なる");
    if (!src.particles) warn.push("パーティクル未描画");
    for (const w of src.warnings || []) warn.push(w);
    $("pvwarn").textContent = warn.join(" / ");
    $("pvwarn").hidden = !warn.length;
  }
  function showPreview(ms) {
    if (!V().preview) return;
    renderFresh($("pfresh"), S.data.freshness?.views?.preview);
    const f = previewFrame(ms), img = $("pimg");
    $("pinfo").textContent = f.dim ? `範囲外 (t+${f.tick})` : `t+${f.tick} (frame ${f.n})`;
    $("pbadge").hidden = !f.dim; $("pbadge").textContent = f.tick < 0 ? "発動前" : "プレビュー範囲の終端";
    const entry = S.frameCache.get(f.url);
    if (entry?.state !== "ready") return;
    img.src = entry.img.src; img.classList.toggle("dim", f.dim); img.style.visibility = "visible"; $("pph").classList.remove("on");
  }

  // ---- hit-volume overlay (published as kind lint; no checks table: the check producer is gone) -------------
  function setupLint() {
    const ov = V().overlay, panel = $("aLint");
    state.overlayAt.clear();
    panel.hidden = !ov;
    if (!ov) return;
    for (const o of ov.overlays) state.overlayAt.set(o.tick, { beat: "hit volume", url: `${ov.base}${o.name}?v=${ov.revision}` });
    const head = $("lintsum"); head.textContent = "";
    head.append(el("span", "lc", `${ov.overlays.length} ticks`));
    const prov = el("span", "prov inline"); head.append(prov);
    renderProv(prov, ov.prov);
  }
  const overlayAtTick = (tick) => state.overlayAt.get(tick) || null;
  function showOverlay(tick) {
    const o = overlayAtTick(tick), pane = $("ovPane");
    pane.hidden = !state.overlayAt.size;
    if (!o) { $("vov").style.visibility = "hidden"; $("ovlabel").textContent = "この tick に注釈なし"; return; }
    $("ovlabel").textContent = o.beat || "";
    const entry = S.frameCache.get(o.url);
    if (entry?.state === "ready") { $("vov").src = entry.img.src; $("vov").style.visibility = "visible"; }
  }

  const hasAny = () => !!(V().preview || V().overlay);
  return {
    setup() {
      setupRound(); setupPreview(); setupLint();
      $("aPreview").hidden = !(V().preview || state.overlayAt.size);
    },
    /** image URLs to prefetch for time `ms` (only while the author section is open) */
    urls: (ms) => (S.authorOpen ? [previewFrame(ms)?.url, overlayAtTick(msToTick(ms, castOf()))?.url] : []),
    previewUrl: (ms) => (S.authorOpen ? previewFrame(ms)?.url : null),
    show(ms) {
      if (!S.authorOpen || !hasAny()) return;
      const tick = msToTick(ms, castOf());
      showPreview(ms); showOverlay(tick);
    },
  };
}
