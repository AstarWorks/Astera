package com.astarworks.astera.core.application.skillengine.compose

data class ComposedSkill(val id: String, val tree: Map<String, Any?>, val sources: List<String>)
