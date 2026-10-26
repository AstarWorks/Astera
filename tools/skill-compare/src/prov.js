// Provenance badge (shared by the case page and the animation review page).
// `p` = { branch, commit, capturedAt, fov, fovMode, resolution, published, reviewRequired, repeated,
//         contentRevision, contentDirty, codeDirty, stale, kind }; a red chip = the view did not record that field.
// kind: "preview" | "lint" | "round" (published review views; no FOV) or absent (a real-client capture).
export function renderProv(box, p) {
  const chip = (txt, cls, title) => { const e = document.createElement("span"); e.className = "pv " + (cls || ""); e.textContent = txt; if (title) e.title = title; box.appendChild(e); };
  const when = { preview: "プレビュー生成", lint: "lint 実行", round: "メモ公開" }[p.kind] || "撮影";
  box.hidden = false;
  if (p.kind === "preview") chip("PREVIEW・実機ではありません", "pvw", "skill-preview（クライアント非使用の近似描画）。実機の見た目の判断材料にしない");
  if (p.caseRev === "unknown") chip("case 版 不明", "warn", "この撮影は参照動画/ケースの revision を記録していない。ケースと一致するか未確認");
  else if (p.caseRev === "stale") chip("case 版 STALE", "bad", "参照動画またはケースの content revision がこの撮影の時点と異なる");
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
    if (p.stale === true) chip("STALE", "bad", "現在のツリーの content と異なる版から作られています。現在: " + (p.currentContent || "?"));
    else if (p.stale === false) chip("最新", "ok", "現在のツリーの content と一致");
    else chip("鮮度不明", "warn", "content revision が記録されていない");
  }
  chip(p.capturedAt ? `${when} ` + p.capturedAt.replace("T", " ").slice(0, 16) + " UTC" : when + "日時 ?", p.capturedAt ? "" : "warn");
  if (p.kind) return;
  chip(p.fov ? `FOV ${p.fov}°${p.fovMode ? " (" + p.fovMode + ")" : ""}` : "FOV 記録なし", p.fov ? "" : "warn");
  if (p.resolution) chip(p.resolution);
  chip(p.published ? "公開版" : "未公開（作業中の撮影）", p.published ? "ok" : "warn");
  if (p.reviewRequired) chip(`静止フレーム最大 ${p.repeated}`, "warn");
}
