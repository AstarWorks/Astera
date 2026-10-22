fetch("/api/cases").then((r) => r.json()).then((list) => {
  const box = document.getElementById("cases");
  box.textContent = "";
  if (!list.length) box.textContent = "no cases in tools/skill-compare/data/";
  for (const c of list) {
    const a = document.createElement("a");
    a.className = "card";
    a.href = "/case.html?id=" + encodeURIComponent(c.id);
    const t = (tag, txt, cls) => { const e = document.createElement(tag); e.textContent = txt; if (cls) e.className = cls; a.appendChild(e); };
    t("b", c.skill?.name || c.id);
    t("span", `${c.id} - video ${c.videoId} ${c.start_s}-${c.end_s} s - Astera ${c.skill?.asteraSkillId || "-"}`);
    t("span", `${c.hits} hit markers - ${c.edits} edits`);
    if (!c.media) t("span", "media not cached: make skill-compare-fetch VIDEO=" + c.videoId, "warn");
    document.getElementById("cases").appendChild(a);
  }
});
