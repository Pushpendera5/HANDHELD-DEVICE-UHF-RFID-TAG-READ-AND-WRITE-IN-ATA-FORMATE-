
package com.rfidsoftwares.testing.fixtures

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Phase 8 scaffolding for fixture contract snapshot validation.
 *
 * Phase 9/10 will schedule and harden these checks. For Phase 8 we keep it lightweight:
 * - ensure JSON parses
 * - ensure required top-level keys exist for the caller to map deterministically
 */
object FixtureContractValidator {
    fun validateObjectHasKeys(obj: JsonObject, requiredKeys: List<String>, fixturePathForErrors: String) {
        val missing = requiredKeys.filter { !obj.has(it) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Fixture '$fixturePathForErrors' missing required keys: ${missing.joinToString(", ")}"
            )
        }
    }

    fun getStringOrNull(obj: JsonObject, key: String): String? {
        val el: JsonElement = obj.get(key) ?: return null
        return el.asString
    }
}

