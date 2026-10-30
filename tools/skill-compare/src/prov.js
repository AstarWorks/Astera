// Provenance badge (shared by the case page and the animation review page).
// `p` = { branch, commit, capturedAt, fov, fovMode, resolution, published, reviewRequired, repeated,
//         contentRevision, contentDirty, codeDirty, stale, kind }; a red chip = the view did not record that field.
// kind: "preview" | "lint" | "round" (published review views; no FOV) or absent (a real-client capture).
export function renderProv(box, p) {
  const chip = (txt, cls, title) => { const e = document.createElement("span"); e.className = "pv " + (cls || ""); e.textContent = txt; if (title) e.title = title; box.appendChild(e); };
  const when = { preview: "プレビュー生成", lint: "オーバーレイ生成", round: "メモ公開" }[p.kind] || "撮影";
  box.hidden = false;
  if (p.kind === "preview") chip("PREVIEW・実機ではありません", "pvw", "skill-preview（クライアント非使用の近似描画）。実機の見た目の判断材料にしない");
  if (p.caseRev === "unknown") chip("case 版 不明", "warn", "この撮影は参照動画/ケースの revision を記録していない。ケースと一致するか未確認");
  else if (p.caseRev === "stale") chip("case 版 STALE", "bad", "参照動画/トリムがこの撮影の後に変わった(skill content の版とは無関係)");
  if (p.failure) chip("旧版・最新の撮影は失敗 " + (p.failure.sha || "").slice(0, 7), "bad", "farm の撮影が失敗したため、この映像は以前の撮影のまま: " + (p.failure.reason || "") + " (" + (p.failure.at || "") + ")");
  if (p.auto) {
    chip("auto " + (p.auto.sha ? p.auto.sha.slice(0, 7) : "?"), "ok", "capture farm: push 後に自動撮影・自動公開 (" + (p.auto.at || "") + ")");
    if (p.auto.kind === "preview") chip("PREVIEW（リアルタイム・暫定）", "pvw", "リアルタイム撮影の暫定版。tick 抜けがあり得る。段階撮影版が後で置き換える");
  }
  chip(p.branch || "branch ?", p.branch ? "" : "warn");
  chip(p.commit ? p.commit.slice(0, 8) : "commit ?", p.commit ? "" : "warn", "git revision");
  if ("stale" in p) {
    const c = p.contentRevision;
    chip(c ? "content " + c.replace("+dirty-", "+d-").slice(0, 14) : "content ?", c ? "" : "warn", "content revision: " + (c || "未記録"));
    if (p.contentDirty != null || p.codeDirty != null) chip(p.contentDirty || p.codeDirty ? `dirty${p.contentDirty ? " content" : ""}${p.codeDirty ? " code" : ""}` : "clean", p.contentDirty || p.codeDirty ? "warn" : "ok", "作成時の作業ツリーに未コミットの変更があったか");
    // No STALE here: whether a video is outdated depends on the skill's own content since this video, which only the server can say
    // (lib/freshness.mjs -> renderFresh on the video itself).
  }
  chip(p.capturedAt ? `${when} ` + p.capturedAt.replace("T", " ").slice(0, 16) + " UTC" : when + "日時 ?", p.capturedAt ? "" : "warn");
  if (p.kind) return;
  chip(p.fov ? `FOV ${p.fov}°${p.fovMode ? " (" + p.fovMode + ")" : ""}` : "FOV 記録なし", p.fov ? "" : "warn");
  if (p.resolution) chip(p.resolution);
  chip(p.published ? "公開版" : "未公開（作業中の撮影）", p.published ? "ok" : "warn");
  if (p.reviewRequired) chip(`静止フレーム最大 ${p.repeated}`, "warn");
}

// ---- freshness (server: lib/freshness.mjs) ----------------------------------------------------------------------------
export const sha7 = (s) => (s ? String(s).slice(0, 7) : "?");
/** "3分前" style age of an ISO instant */
export function agoText(iso, now = Date.now()) {
  const t = iso ? Date.parse(iso) : NaN;
  if (Number.isNaN(t)) return "";
  const m = Math.max(0, Math.round((now - t) / 60000));
  return m < 1 ? "たった今" : m < 60 ? `${m}分前` : m < 2880 ? `${Math.round(m / 60)}時間前` : `${Math.round(m / 1440)}日前`;
}
/** The sha a video was made from: the capture farm's deployed sha, else the tree sha it recorded. */
export const videoSha = (prov) => prov?.auto?.sha || prov?.commit || null;
/**
 * Overlay on a video. f = server freshness for that video: building -> 「生成中… (旧版を表示中)」, failed -> 「失敗」 + reason; others hide it.
 * Rebuilt only when `f` changes (called every frame).
 */
export function renderFresh(box, f) {
  const show = !!f && (f.state === "building" || f.state === "failed");
  const key = show ? JSON.stringify(f) : "";
  if (box.dataset.k === key) return;
  box.dataset.k = key; box.textContent = "";
  box.hidden = !show;
  if (!show) return;
  box.className = "fresh " + f.state;
  const b = document.createElement("b"), small = document.createElement("small");
  const versions = `表示中 ${sha7(f.videoSha)} → dev ${sha7(f.devSha)}`;
  if (f.state === "building") {
    b.textContent = "生成中… (旧版を表示中)";
    small.textContent = [f.stage === "running" ? "描画/撮影を実行中" : "順番待ち", f.stalled ? "デプロイから30分以上: 生成が止まっている可能性" : "", versions].filter(Boolean).join(" · ");
  } else {
    b.textContent = `旧版表示中 · 最新の撮影は失敗${f.failedSha ? " (" + sha7(f.failedSha) + ")" : ""}`;
    small.textContent = [f.reason ? String(f.reason).slice(0, 220) : "原因不明 (ログ参照)", versions].join(" · ");
  }
  box.append(b, small);
}
