// Animation review index: one row per content/animations/*.yaml. One JSON (/api/anims), vanilla DOM.
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const STATUS = { unreviewed: ["未レビュー", ""], ok: ["OK", "st-ca"], ng: ["NG", "st-ng"] };

function row(a) {
  const r = el("a", "arow"); r.href = "/anim.html?id=" + encodeURIComponent(a.id);
  const th = el("div", "th");
  if (a.thumb) { const i = el("img"); i.loading = "lazy"; i.alt = a.id; i.src = a.thumb; i.onerror = () => { i.remove(); th.classList.add("empty"); }; th.appendChild(i); } else th.classList.add("empty");
  const mid = el("div", "smid");
  const nm = el("div", "snm"); nm.append(el("b", null, a.id));
  const meta = el("div", "smeta", `${a.lengthTicks} tick (${(a.lengthTicks / 20).toFixed(2)} 秒) ・ ブレンド 入${a.blendInTicks} / 出${a.blendOutTicks}`);
  const chips = el("div", "chips");
  chips.append(el("span", "chip", a.loop ? "ループ" : "1回再生"), el("span", "chip", a.lockMovement ? "移動ロック" : "移動可"));
  if (a.skills.length) chips.append(el("span", "chip", "スキル: " + a.skills.join(", ")));
  const c = a.capture;
  chips.append(c ? el("span", "upd", `撮影 ${c.commit ? c.commit.slice(0, 8) : "commit ?"} ・ ${c.capturedAt ? c.capturedAt.replace("T", " ").slice(0, 16) + " UTC" : "日時 ?"}`) : el("span", "chip st-none", "未撮影"));
  mid.append(nm, meta, chips);
  const rv = el("div", "arev");
  const [txt, cls] = STATUS[a.review.status] ?? STATUS.unreviewed;
  rv.append(el("span", "chip " + cls, txt));
  if (a.review.comment) rv.append(el("div", "acm", a.review.comment));
  if (a.review.updated) rv.append(el("div", "upd", `${a.review.updated.who} ・ ${a.review.updated.at.replace("T", " ").slice(0, 16)}`));
  r.append(th, mid, rv);
  return r;
}

fetch("/api/anims").then((r) => r.json()).then((list) => {
  const box = document.getElementById("list");
  box.textContent = "";
  for (const a of list) box.appendChild(row(a));
  if (!list.length) box.textContent = "content/animations/*.yaml がありません";
  const n = (s) => list.filter((a) => a.review.status === s).length;
  document.getElementById("count").textContent = `${list.length} 件 ・ OK ${n("ok")} / NG ${n("ng")} / 未レビュー ${n("unreviewed")}`;
}).catch((e) => { document.getElementById("list").textContent = "読み込み失敗: " + e.message; });
