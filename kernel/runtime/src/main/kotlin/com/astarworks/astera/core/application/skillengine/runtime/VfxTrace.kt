package com.astarworks.astera.core.application.skillengine.runtime

/**
 * Switch for the `[vfx-trace]` server log rows the Paper adapters emit per tick (the driven puppet's hand, every rig mesh write):
 * what the server actually sent, with the world's `gameTime` as `tick=`, so a capture (`tools/vfx-shot`, `trace.log` +
 * `rig_trace_check.py`) can assert from the sent entity data, not from the model, that the weapon is in the fist. Off by default; the
 * vfx-shot compose sets `ASTERA_VFX_TRACE=1`.
 */
public object VfxTrace {
    @Volatile
    public var enabled: Boolean = System.getenv("ASTERA_VFX_TRACE") != null || System.getProperty("vfx.trace") == "true"
}
