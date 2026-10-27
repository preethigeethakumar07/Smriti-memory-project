package com.smriti.app.pipeline

/**
 * What the pipeline is currently allowed to do. Changed at runtime from Settings or the dashboard.
 * The privacy stage is intentionally NOT here: face blurring cannot be turned off or swapped out.
 */
data class PipelineConfig(
    val preset: String = "balanced",
    val detectorId: String = "mlkit-objects",
    val labelerId: String = "mlkit-labeler-balanced",
    val ocrId: String = "mlkit-ocr",
    val detectOn: Boolean = true,
    val labelOn: Boolean = true,
    val ocrOn: Boolean = true,
    /** Stream the already-blurred frame to the dashboard. In memory only, never stored. Off by default. */
    val preview: Boolean = false
) {
    fun withStage(stage: String, on: Boolean): PipelineConfig = when (stage) {
        Stage.DETECT -> copy(preset = "custom", detectOn = on)
        Stage.LABEL -> copy(preset = "custom", labelOn = on)
        Stage.OCR -> copy(preset = "custom", ocrOn = on)
        else -> this
    }

    companion object {
        val PRESETS = listOf("fast", "balanced", "rich")

        /** "rich" will add the NPU captioner once one is installed; until then it equals balanced. */
        fun preset(name: String, keep: PipelineConfig = PipelineConfig()): PipelineConfig = when (name) {
            "fast" -> keep.copy(preset = "fast", detectOn = false, labelOn = true, ocrOn = false)
            "rich" -> keep.copy(preset = "rich", detectOn = true, labelOn = true, ocrOn = true)
            else -> keep.copy(preset = "balanced", detectOn = true, labelOn = true, ocrOn = true)
        }
    }
}
