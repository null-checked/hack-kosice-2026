package com.rubberduck.live.chat.bridge.handlers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import com.rubberduck.live.detector.EventEmitter
import com.rubberduck.live.model.EventType
import com.rubberduck.live.util.DiffApplier
import com.rubberduck.live.util.PathRedactor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Previews a proposed code edit in IntelliJ's native diff dialog and
 * applies it on Accept. Owns the primary `applyEdit` action; companion
 * classes [RejectEditHandler] + [EmitInterventionHandler] cover the
 * lifecycle pings (reject / shown) that share the same event schema.
 *
 * Extracted verbatim from `ChatBridge.applyEdit` (lines 516-629 of the
 * pre-refactor monolith). Event emission semantics are unchanged so the
 * dashboard's acceptance-rate metric stays coherent across versions.
 */
class EditHandler : ChatBridgeHandler() {
    override val name: String = "applyEdit"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload) ?: return buildJsonObject {
            put("applied", false)
            put("error", "invalid-payload")
        }
        val editId = (obj["edit_id"] as? JsonPrimitive)?.content ?: ""
        val filePath = (obj["file_path"] as? JsonPrimitive)?.content ?: ""
        val original = (obj["original_code"] as? JsonPrimitive)?.content ?: ""
        val proposed = (obj["proposed_code"] as? JsonPrimitive)?.content ?: ""
        val explanation = (obj["explanation"] as? JsonPrimitive)?.content ?: ""

        if (filePath.isBlank() || proposed.isBlank()) {
            return buildJsonObject {
                put("applied", false)
                put("error", "missing-fields")
            }
        }

        val vf = ctx.fileResolver.resolve(filePath) ?: return buildJsonObject {
            put("applied", false)
            put("error", "file-not-found")
        }
        val redacted = PathRedactor.redact(ctx.project, vf.path)

        var applied = false
        ApplicationManager.getApplication().invokeAndWait {
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@invokeAndWait
            val currentText = doc.text
            val leftText = if (original.isNotBlank()) original else currentText
            val title = if (explanation.isNotBlank()) {
                "RubberDuck chat edit — ${vf.name}\n$explanation"
            } else {
                "RubberDuck chat edit — ${vf.name}"
            }
            val result = DiffApplier.previewAndApply(
                project = ctx.project,
                document = doc,
                title = title,
                original = leftText,
                proposed = proposed,
                windowTitle = "Apply chat edit?",
                commandName = "RubberDuck: Apply chat edit",
            )
            applied = result.applied
        }

        val evtType = if (applied) EventType.INTERVENTION_ACCEPTED else EventType.INTERVENTION_DISMISSED
        EventEmitter.emit(
            evtType,
            file = redacted,
            payload = EventEmitter.json(
                "intervention_id" to editId,
                "kind" to "chat_edit",
            ),
        )
        return buildJsonObject {
            put("applied", applied)
            put("edit_id", editId)
        }
    }
}

/**
 * Emits `INTERVENTION_DISMISSED` when the user clicks Reject on an edit
 * card so the acceptance-rate metric stays in `[0,1]`.
 */
class RejectEditHandler : ChatBridgeHandler() {
    override val name: String = "rejectEdit"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload) ?: return JsonPrimitive(false)
        val editId = (obj["edit_id"] as? JsonPrimitive)?.content ?: ""
        val filePath = (obj["file_path"] as? JsonPrimitive)?.content
        EventEmitter.emit(
            EventType.INTERVENTION_DISMISSED,
            file = filePath,
            payload = EventEmitter.json(
                "intervention_id" to editId,
                "kind" to "chat_edit",
            ),
        )
        return JsonPrimitive(true)
    }
}

/**
 * Generic intervention emitter used for per-card lifecycle pings
 * (e.g. `INTERVENTION_SHOWN` on card render).
 */
class EmitInterventionHandler : ChatBridgeHandler() {
    override val name: String = "emitIntervention"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val obj = parsePayloadObject(payload) ?: return JsonPrimitive(false)
        val typeName = (obj["type"] as? JsonPrimitive)?.content ?: "INTERVENTION_SHOWN"
        val kind = (obj["kind"] as? JsonPrimitive)?.content ?: "chat_edit"
        val editId = (obj["edit_id"] as? JsonPrimitive)?.content ?: ""
        val filePath = (obj["file_path"] as? JsonPrimitive)?.content
        val evt = runCatching { EventType.valueOf(typeName) }.getOrNull()
            ?: EventType.INTERVENTION_SHOWN
        EventEmitter.emit(
            evt,
            file = filePath,
            payload = EventEmitter.json(
                "intervention_id" to editId,
                "kind" to kind,
            ),
        )
        return JsonPrimitive(true)
    }
}
