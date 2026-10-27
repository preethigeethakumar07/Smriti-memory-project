package com.smriti.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ModelInfo(
    val name: String,
    val role: String,
    val backend: String,
    val state: String,          // loading | ready | failed | not installed
    val loadMs: Long = -1,
    val note: String = ""
)

/** What the Sensors tab shows under "Models". Engines register themselves here. */
object ModelRegistry {

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    fun put(info: ModelInfo) = _models.update { list -> list.filterNot { it.name == info.name } + info }
}
