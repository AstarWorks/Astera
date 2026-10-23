// Capture provenance badge (shared by the case page and the animation review page).
// `p` = { branch, commit, capturedAt, fov, fovMode, resolution, published, reviewRequired, repeated }; a red chip = the capture did not record that field.
export function renderProv(box, p) {
  const chip = (txt, cls) => { const e = document.createElement("span"); e.className = "pv " + (cls || ""); e.textContent = txt; box.appendChild(e); };
  box.hidden = false;
  chip(p.branch || "branch ?", p.branch ? "" : "warn");
  chip(p.commit ? p.commit.slice(0, 8) : "commit ?", p.commit ? "" : "warn");
  chip(p.capturedAt ? p.capturedAt.replace("T", " ").slice(0, 16) + " UTC" : "撮影日時 ?", p.capturedAt ? "" : "warn");
  chip(p.fov ? `FOV ${p.fov}°${p.fovMode ? " (" + p.fovMode + ")" : ""}` : "FOV 記録なし", p.fov ? "" : "warn");
  if (p.resolution) chip(p.resolution);
  chip(p.published ? "公開版" : "未公開（作業中の撮影）", p.published ? "ok" : "warn");
  if (p.reviewRequired) chip(`静止フレーム最大 ${p.repeated}`, "warn");
}
