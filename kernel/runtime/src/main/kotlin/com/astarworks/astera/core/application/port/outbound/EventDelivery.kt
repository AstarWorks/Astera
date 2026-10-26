package com.astarworks.astera.core.application.port.outbound

/** Entity uses the event's Player route directly, without resolving its instance strand. */
public enum class EventDelivery { SameStrand, Entity, Global, Io }
