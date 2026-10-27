package com.astarworks.astera.core.application.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/** Schema hint: the annotated list property gets `minItems` in the generated JSON Schema. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class MinItems(public val value: Int)
