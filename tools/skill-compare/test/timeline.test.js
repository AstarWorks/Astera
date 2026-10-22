import { describe, expect, test } from "bun:test";
import {
  msToVideoFrame, videoFrameToMs, msToTick, tickToMs, msToIntentTick, sToMs, msToS, castMs, focusZones, effectiveSpeed, validateCase, diffSummary,
} from "../src/timeline.js";

const base = () => ({
  id: "t1", videoId: "Svaaq9F7Ty8", start_s: 18.6, end_s: 24, skill: { name: "x" },
  segments: [{ id: "skill", kind: "skill", label: "s", start_s: 18.6, end_s: 23.7 }, { id: "hit1", kind: "hit", label: "h", t_s: 19.7 }],
});

describe("ms <-> frame <-> tick", () => {
  test("native 30 fps frames are 33.3 ms, never resampled", () => {
    expect(msToVideoFrame(0, 30)).toBe(0);
    expect(msToVideoFrame(33.3, 30)).toBe(0);
    expect(msToVideoFrame(33.4, 30)).toBe(1);
    expect(msToVideoFrame(1000, 30)).toBe(30);
    // 1.1 s of a 20 fps resample would be frame 22; native keeps 33 distinct frames in the same span
    expect(msToVideoFrame(1099, 30)).toBe(32);
    expect(videoFrameToMs(3, 30)).toBeCloseTo(100, 6);
  });
  test("frame index clamps to the cached count", () => {
    expect(msToVideoFrame(99999, 30, 162)).toBe(161);
    expect(msToVideoFrame(-5, 30, 162)).toBe(0);
  });
  test("ticks are 50 ms around the cast moment", () => {
    expect(msToTick(1000, 1000)).toBe(0);
    expect(msToTick(1049, 1000)).toBe(0);
    expect(msToTick(1050, 1000)).toBe(1);
    expect(msToTick(999, 1000)).toBe(-1);
    expect(tickToMs(4, 1000)).toBe(1200);
    expect(msToTick(tickToMs(7, 250), 250)).toBe(7);
  });
  test("cast moment = skill start + offset on the case clock", () => {
    const c = base();
    expect(castMs(c)).toBe(0);
    c.mc = { offset_ms: 120 };
    expect(castMs(c)).toBe(120);
    c.segments[0].start_s = 19.1;
    expect(castMs(c)).toBe(620);
  });
  test("intent tick is anchored on intent.source.start_s", () => {
    expect(msToIntentTick(0, 18.6, 18.6)).toBe(0);
    expect(msToIntentTick(1100, 18.6, 18.6)).toBe(22);
    expect(msToIntentTick(0, 19.6, 18.6)).toBe(20);
  });
  test("seconds <-> ms", () => {
    expect(sToMs(19.7, 18.6)).toBe(1100);
    expect(msToS(1100, 18.6)).toBe(19.7);
  });
});

describe("focus zones", () => {
  const onsets = [{ t: 19.7, s: 10 }, { t: 20.0, s: 2 }, { t: 20.95, s: 9 }];
  const cuts = [{ t: 19.75, s: 40 }];
  test("strong onsets and all cuts get merged +-window zones", () => {
    const z = focusZones(onsets, cuts, 18.6, 150);
    expect(z).toEqual([{ from: 950, to: 1300 }, { from: 2200, to: 2500 }]); // 19.7/19.75 merge; weak 20.0 skipped
  });
  test("slow-mo only inside a zone and never speeds up", () => {
    const z = [{ from: 100, to: 200 }];
    expect(effectiveSpeed(150, 1, z, true, 0.2)).toBe(0.2);
    expect(effectiveSpeed(150, 0.1, z, true, 0.2)).toBe(0.1);
    expect(effectiveSpeed(300, 1, z, true, 0.2)).toBe(1);
    expect(effectiveSpeed(150, 1, z, false, 0.2)).toBe(1);
  });
});

describe("case.json validation", () => {
  test("seed shape is valid", () => expect(validateCase(base())).toEqual([]));
  test("rejects bad id, range, kinds and duplicates", () => {
    const c = base();
    c.id = "../x"; c.end_s = 10; c.segments.push({ id: "hit1", kind: "nope", label: "" });
    const p = validateCase(c).join("|");
    expect(p).toContain("id must match");
    expect(p).toContain("start_s < end_s");
    expect(p).toContain("duplicate segment id hit1");
    expect(p).toContain("kind must be one of");
  });
  test("hit needs t_s, range needs ordered start/end", () => {
    const c = base();
    c.segments = [{ id: "a", kind: "hit", label: "" }, { id: "b", kind: "range", label: "", start_s: 5, end_s: 4 }];
    expect(validateCase(c).length).toBe(2);
  });
  test("diff summary names added/changed/removed", () => {
    const a = base(), b = base();
    b.segments[1].t_s = 19.8; b.segments.push({ id: "hit2", kind: "hit", label: "", t_s: 20 }); b.segments.shift();
    expect(diffSummary(a, b)).toBe("~hit1 +hit2 -skill");
  });
});
