package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.ItemFrames

/** `content/frames`: the point table of each item. */
public interface FrameTables {
    public fun item(item: String): ItemFrames?
}
