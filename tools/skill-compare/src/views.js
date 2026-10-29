// Author-only review views inside the collapsed 作者向け section: preview frame, annotation overlay, lint table, round notes.
// Data comes from /api/cases/<id> `views` (server.mjs reviewViews); everything is optional and the page works without it.
import { renderProv, renderFresh } from "./prov.js";
import { renderMarkdown } from "./markdown.js";

const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

/** ctx: { S, $, clamp, msToTick, tickToMs, castOf, seek } */
export function createViews(ctx) {
  const { S, $, clamp, msToTick, tickToMs, castOf, seek } = ctx;
  const V = () => S.data.views || {};
  const state = { failOnly: false, lintRows: [], overlayAt: new Map() };

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

  // ---- lint + overlay ---------------------------------------------------------------------------------------------
  const inBeat = (c, tick) => (c.ticks ? tick >= c.ticks[0] && tick <= c.ticks[1] : c.tick != null && tick === c.tick);
  function setupLint() {
    const lint = V().lint, panel = $("aLint");
    state.overlayAt.clear();
    panel.hidden = !lint;
    if (!lint) return;
    const report = lint.report;
    if (report) for (const c of report.checks) if (c.overlay && c.tick != null) state.overlayAt.set(c.tick, { beat: c.beat, url: `${lint.base}${c.overlay}?v=${lint.revision}` });
    const head = $("lintsum"); head.textContent = "";
    if (report) {
      const s = report.summary;
      head.append(el("span", "lc " + (s.fail ? "bad" : "ok"), s.fail ? `${s.fail} FAIL` : "ALL PASS"), el("span", "lc", `${s.checks} checks / ${s.pass} pass / ${s.skip} skip`));
      const prov = el("span", "prov inline"); head.append(prov);
      renderProv(prov, lint.prov);
    } else head.append(el("span", "lc warn", "lint 未配信（producer 削除済み）"));
    const body = $("lintbody"); body.textContent = ""; state.lintRows = [];
    for (const c of report?.checks || []) {
      const tr = el("tr", c.status === "FAIL" ? "fail" : "");
      const when = c.ticks ? `t${c.ticks[0]}-${c.ticks[1]}` : c.tick != null ? `t${c.tick}` : "";
      tr.append(el("td", "lst " + c.status.toLowerCase(), c.status), el("td", "", c.check), el("td", "bt", when), el("td", "", c.text));
      const at = c.ticks ? c.ticks[0] : c.tick;
      if (at != null) { tr.classList.add("seekable"); tr.onclick = () => seek(tickToMs(at, castOf())); }
      body.append(tr); state.lintRows.push({ tr, c });
    }
    applyLintFilter();
  }
  function applyLintFilter() { for (const { tr, c } of state.lintRows) tr.hidden = state.failOnly && c.status !== "FAIL"; }
  function updateLint(tick) {
    for (const { tr, c } of state.lintRows) tr.classList.toggle("active", !!((c.ticks || c.tick != null) && inBeat(c, tick)));
    const now = $("lintnow"); now.textContent = "";
    const rep = V().lint?.report;
    if (!rep) return;
    const hits = rep.checks.filter((c) => (c.ticks || c.tick != null) && inBeat(c, tick));
    now.append(el("span", "k", `t+${tick}`));
    for (const c of hits) now.append(el("span", "lc " + (c.status === "FAIL" ? "bad" : c.status === "PASS" ? "ok" : ""), `${c.status} ${c.beat ? c.beat : c.check}`));
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

  const hasAny = () => !!(V().preview || V().lint);
  return {
    setup() {
      setupRound(); setupPreview(); setupLint();
      $("aPreview").hidden = !(V().preview || state.overlayAt.size);
      $("failOnly").onchange = (e) => { state.failOnly = e.target.checked; applyLintFilter(); };
    },
    /** image URLs to prefetch for time `ms` (only while the author section is open) */
    urls: (ms) => (S.authorOpen ? [previewFrame(ms)?.url, overlayAtTick(msToTick(ms, castOf()))?.url] : []),
    previewUrl: (ms) => (S.authorOpen ? previewFrame(ms)?.url : null),
    show(ms) {
      if (!S.authorOpen || !hasAny()) return;
      const tick = msToTick(ms, castOf());
      showPreview(ms); showOverlay(tick); updateLint(tick);
    },
  };
}
