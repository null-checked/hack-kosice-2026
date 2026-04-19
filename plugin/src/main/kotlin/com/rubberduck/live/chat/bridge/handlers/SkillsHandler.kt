package com.rubberduck.live.chat.bridge.handlers

import com.rubberduck.live.chat.bridge.ChatBridgeHandler
import com.rubberduck.live.chat.bridge.ChatHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Surfaces the enabled skill catalog to the web UI and, on demand, the
 * body of a single skill (frontmatter stripped).
 *
 * Two actions share this handler because they logically operate over
 * the same [com.rubberduck.live.services.SkillsManager] and the web UI
 * calls them from the same `@skill:` autocomplete flow. This mirrors
 * cc-gui's `SkillHandler` grouping.
 *
 * Dispatcher registration: this handler declares its primary [name]
 * (`listSkills`); the secondary `getSkillBody` action is provided by
 * the sibling [SkillBodyHandler] class so the dispatcher's
 * "one action per handler" invariant holds.
 */
class SkillsHandler : ChatBridgeHandler() {
    override val name: String = "listSkills"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        // Only enabled skills (per RubberDuckSettings.enabledSkills) are
        // exposed so the @ popup matches the Skills config.
        val skills = runCatching { ctx.skills.getEnabledSkills() }.getOrDefault(emptyList())
        return buildJsonArray {
            for (s in skills) {
                add(buildJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("trigger", s.trigger)
                })
            }
        }
    }
}

/**
 * Returns the body of a named skill (frontmatter stripped) or [JsonNull]
 * when the name doesn't resolve.
 */
class SkillBodyHandler : ChatBridgeHandler() {
    override val name: String = "getSkillBody"

    override fun handle(payload: String, ctx: ChatHandlerContext): JsonElement {
        val skillName = unquoteString(payload)
        if (skillName.isBlank()) return JsonNull
        val mgr = runCatching { ctx.skills }.getOrNull() ?: return JsonNull
        // Match by frontmatter `name` first, then by `id` (filename stem) as
        // a fallback so `@skill:code-explainer` also resolves.
        val skill = mgr.getSkillByName(skillName)
            ?: mgr.getAllSkills().find { it.id.equals(skillName, ignoreCase = true) }
            ?: return JsonNull
        val body = skill.content
        val m = Regex("(?s)^---\\s*\\n.*?\\n---\\s*\\n?(.*)$").find(body)
        return JsonPrimitive(m?.groupValues?.get(1) ?: body)
    }
}
