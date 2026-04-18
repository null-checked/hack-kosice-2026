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

/** One turn in the ongoing Socratic dialogue. Role: "coach" or "developer". */
@Serializable
data class SocraticTurn(
    val role: String,
    val text: String,
)

@Serializable
data class SocraticRequest(
    val recent_events: List<DeveloperEvent> = emptyList(),
    val current_code: String = "",
    val stuck_reason: String = "unknown",
    /** Empty on the opening call; non-empty when the user is replying mid-dialogue. */
    val conversation: List<SocraticTurn> = emptyList(),
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

@Serializable
data class CompleteRequest(
    val language: String = "python",
    val prefix: String,
    val suffix: String = "",
    val file_path: String? = null,
    val max_tokens: Int = 96,
)

@Serializable
data class CompleteResponse(
    val completion: String,
    val stop_reason: String = "eol",
    val model: String = "unknown",
)

/**
 * A structured code edit proposed by the assistant in a chat response.
 * `file_path` is redacted (project-relative); `diff_unified` is computed
 * backend-side via difflib. `start_line`/`end_line` are 1-based, inclusive;
 * both null means the edit targets the whole file.
 */
@Serializable
data class CodeEdit(
    val file_path: String,
    val original_code: String,
    val proposed_code: String,
    val explanation: String = "",
    val start_line: Int? = null,
    val end_line: Int? = null,
    val diff_unified: String = "",
)

/** Single turn in the /code-chat conversation. Role = "user" | "assistant". */
@Serializable
data class ChatTurn(
    val role: String,
    val text: String,
    /**
     * Populated from the assistant response for local plugin-side rendering.
     * Still serialized to the backend, but the backend ignores `edits` on
     * inbound `ChatTurn`. Default empty for user turns.
     */
    val edits: List<CodeEdit> = emptyList(),
)

@Serializable
data class CodeChatRequest(
    val conversation: List<ChatTurn> = emptyList(),
    val current_code: String = "",
    val file_path: String? = null,
    val language: String = "python",
    /** Files @-mentioned by the user for additional context. */
    val context_files: List<ContextFile> = emptyList(),
    /** When true, the system prompt asks the LLM for structured edits. */
    val allow_edits: Boolean = false,
)

@Serializable
data class CodeChatResponse(
    val answer: String,
    val model: String = "unknown",
    /** Structured edits proposed by the assistant (empty when `allow_edits` was false). */
    val edits: List<CodeEdit> = emptyList(),
)

@Serializable
data class ExplainCodeRequest(
    val code: String,
    val file_path: String? = null,
    val language: String = "python",
    val context: String = "",
)

@Serializable
data class ExplainCodeResponse(
    val explanation: String,
    val complexity: String = "unknown",
    val suggestions: List<String> = emptyList(),
    val model: String = "unknown",
)
