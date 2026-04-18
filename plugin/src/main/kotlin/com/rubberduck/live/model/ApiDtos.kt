package com.rubberduck.live.model

import kotlinx.serialization.Serializable

/** Request/response DTOs for the three HTTP AI endpoints. */

@Serializable
data class ContextFile(val path: String, val content: String)

@Serializable
data class GenerateTestsRequest(
    val language: String = "python",
    val code: String,
    val function_name: String? = null,
    val file_path: String? = null,
    val context_files: List<ContextFile> = emptyList(),
)

@Serializable
data class GenerateTestsResponse(
    val test_code: String,
    val test_file_name: String,
    val framework: String,
    val reasoning: String,
)

@Serializable
data class SelectionRange(val start_line: Int, val end_line: Int)

@Serializable
data class SuggestRefactorRequest(
    val language: String = "python",
    val code: String,
    val file_path: String? = null,
    val selection_range: SelectionRange? = null,
    val smell_hints: List<String> = emptyList(),
)

@Serializable
data class DetectedSmell(val type: String, val line: Int, val description: String)

@Serializable
data class SuggestRefactorResponse(
    val refactored_code: String,
    val diff_unified: String,
    val smells_detected: List<DetectedSmell>,
    val reasoning: String,
)

@Serializable
data class SocraticRequest(
    val recent_events: List<DeveloperEvent> = emptyList(),
    val current_code: String = "",
    val stuck_reason: String = "unknown",
)

@Serializable
data class SocraticResponse(
    val question: String,
    val follow_ups: List<String> = emptyList(),
    val confidence: Double = 0.7,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val llm_provider: String = "unknown",
    val llm_model: String = "unknown",
    val llm_available: Boolean = false,
    val demo_mock: Boolean = false,
    val version: String = "0.0.0",
)
