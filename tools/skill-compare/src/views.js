// Director-published review views on the case page: preview column, lint panel, annotation overlay, round notes.
// Data comes from /api/cases/<id> `views` (server.mjs reviewViews); everything is optional and the page works without it.
import { renderProv } from "./prov.js";

const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

/** ctx: { S, $, clamp, msToTick, tickToMs, castOf, seek, update } */
export function createViews(ctx) {
  const { S, $, clamp, msToTick, tickToMs, castOf, seek } = ctx;
  const V = () => S.data.views || {};
  const state = { provKey: "", overlayOn: true, failOnly: false, lintRows: [], overlayAt: new Map() };

  // ---- round notes (top of the page) ------------------------------------------------------------------------------
  function setupRound() {
    const box = $("round"), r = V().round;
    if (!r) { box.hidden = true; return; }
    box.hidden = false; box.textContent = "";
    const h = el("div", "rhead");
    h.append(el("b", "", "ラウンドメモ（director → founder）"));
    const prov = el("span", "prov inline"); h.append(prov);
    box.append(h, el("div", "rtext", r.text));
    renderProv(prov, { ...r.prov, published: true });
  }

  // ---- preview column ---------------------------------------------------------------------------------------------
  function previewFrame(ms) {
    const src = V().preview;
    if (!src) return null;
    const tick = msToTick(ms, castOf());
    const f = tick + (src.castFrame ?? 0), n = clamp(f, 0, src.count - 1);
    return { url: `${src.base}frame_${String(n).padStart(3, "0")}.${src.extension || "png"}?v=${encodeURIComponent(src.revision)}`, dim: f < 0 || f >= src.count, tick, n };
  }
  function setupPreview() {
    const src = V().preview, col = $("pvcol"), cols = $("cols");
    col.hidden = !src;
    cols.classList.toggle("three", !!src);
    document.querySelector('[data-focus="pv"]').hidden = !src;
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
    const src = V().preview;
    if (!src) return;
    const f = previewFrame(ms), img = $("pimg");
    $("pinfo").textContent = f.dim ? `範囲外 (t+${f.tick})` : `t+${f.tick} (frame ${f.n})`;
    $("pbadge").hidden = !f.dim; $("pbadge").textContent = f.tick < 0 ? "発動前" : "プレビュー範囲の終端";
    const entry = S.frameCache.get(f.url);
    if (entry?.state !== "ready") return;
    img.src = entry.img.src; img.classList.toggle("dim", f.dim); img.style.visibility = "visible"; $("pph").classList.remove("on");
  }

  // ---- lint panel + overlay ---------------------------------------------------------------------------------------
  const inBeat = (c, tick) => (c.ticks ? tick >= c.ticks[0] && tick <= c.ticks[1] : c.tick != null && tick === c.tick);
  function setupLint() {
    const lint = V().lint, panel = $("lintpanel");
    state.overlayAt.clear();
    if (!lint) { panel.hidden = true; $("ovctl").hidden = true; return; }
    panel.hidden = false;
    const report = lint.report;
    if (report) for (const c of report.checks) if (c.overlay && c.tick != null) state.overlayAt.set(c.tick, { beat: c.beat, url: `${lint.base}${c.overlay}?v=${lint.revision}` });
    $("ovctl").hidden = !state.overlayAt.size;
    $("ovn").textContent = `(${state.overlayAt.size})`;
    const head = $("lintsum"); head.textContent = "";
    if (report) {
      const s = report.summary;
      head.append(el("b", "", "Lint "), el("span", "lc " + (s.fail ? "bad" : "ok"), s.fail ? `${s.fail} FAIL` : "ALL PASS"), el("span", "lc", `${s.checks} checks / ${s.pass} pass / ${s.skip} skip`));
      const prov = el("span", "prov inline"); head.append(prov);
      renderProv(prov, lint.prov);
    } else head.append(el("b", "", "Lint "), el("span", "lc warn", "report.json なし（make skill-lint）"), document.createTextNode(` オーバーレイ PNG ${lint.overlays.length} 枚のみ`));
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
  function updateLint(ms) {
    const tick = msToTick(ms, castOf());
    let first = null;
    for (const { tr, c } of state.lintRows) {
      const on = (c.ticks || c.tick != null) && inBeat(c, tick);
      tr.classList.toggle("active", !!on);
      if (on && !first) first = tr;
    }
    const now = $("lintnow"); now.textContent = "";
    const rep = V().lint?.report;
    if (!rep) { now.hidden = true; return; }
    const hits = rep.checks.filter((c) => (c.ticks || c.tick != null) && inBeat(c, tick));
    now.hidden = false;
    now.append(el("span", "k", `lint @ t+${tick}`));
    if (!hits.length) now.append(el("span", "lc", "この tick に該当するビートなし"));
    for (const c of hits) now.append(el("span", "lc " + (c.status === "FAIL" ? "bad" : c.status === "PASS" ? "ok" : ""), `${c.status} ${c.beat ? c.beat : c.check}`));
  }
  // The overlay is a differently framed, annotated crop that replaces the reference picture. During playback it
  // covered the reference for one 50 ms tick per beat (8 flashes in S1) = flicker, so it is shown only while paused.
  function showOverlay(ms) {
    const img = $("vov");
    const o = state.overlayOn && !S.playing ? state.overlayAt.get(msToTick(ms, castOf())) : null;
    if (!o) { img.hidden = true; $("ovlabel").hidden = true; return; }
    const entry = S.frameCache.get(o.url);
    if (entry?.state === "ready") { img.src = entry.img.src; img.hidden = false; }
    $("ovlabel").hidden = false; $("ovlabel").textContent = `注釈オーバーレイ: ${o.beat}`;
  }
  const overlayUrl = (ms) => (state.overlayOn && !S.playing ?state.overlayAt.get(msToTick(ms, castOf()))?.url : null);

  function setupControls() {
    $("ovOn").onchange = (e) => { state.overlayOn = e.target.checked; showOverlay(S.ms); };
    $("failOnly").onchange = (e) => { state.failOnly = e.target.checked; applyLintFilter(); };
  }

  return {
    setup() { setupRound(); setupPreview(); setupLint(); setupControls(); },
    /** image URLs to prefetch for time `ms`: preview frame + overlay */
    urls: (ms) => [previewFrame(ms)?.url, overlayUrl(ms)],
    previewUrl: (ms) => previewFrame(ms)?.url,
    show(ms) { showPreview(ms); showOverlay(ms); updateLint(ms); },
  };
}
