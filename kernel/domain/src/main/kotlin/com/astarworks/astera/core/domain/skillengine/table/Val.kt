package com.astarworks.astera.core.domain.skillengine.table

/** A table cell. Known at compile time, or a named live value with a declared bound (ADR-0303 C4c, 11). */
public sealed interface Val<out T> {
    public data class Known<T>(val value: T) : Val<T>

    /** [bound] == null only where 11.4 check 9 permits (non-timing). */
    public data class Live<T>(val name: String, val bound: Bound<T>?) : Val<T>
}

public data class Bound<T>(val lo: T, val hi: T)

public typealias Num = Val<Double>
public typealias Vec = Val<com.astarworks.astera.core.domain.skillengine.math.Vector3d>
public typealias Rot = Val<com.astarworks.astera.core.domain.skillengine.math.Quaterniond>
