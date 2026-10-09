
package com.rfidsoftwares.testing.fixtures

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfidsoftwares.RfidInventoryApp
import java.nio.charset.StandardCharsets

/**
 * Loads JSON fixtures from `assets/test-fixtures/...`.
 *
 * Phase 8 invariant: when test mode is ON, adapter HTTP calls must be bypassed and fixture JSON must drive responses.
 */
object FixtureJsonLoader {
    fun loadJsonObject(fixturePathFromAssetsRoot: String): JsonObject {
        val normalized = fixturePathFromAssetsRoot.trim().removePrefix("/")
        val context = RfidInventoryApp.AppContextHolder.requireContext()

        val assetRelativePath =
            if (normalized.startsWith("test-fixtures/")) normalized else "test-fixtures/$normalized"

        val jsonText = context.assets.open(assetRelativePath).use { input ->
            val bytes = input.readBytes()
            String(bytes, StandardCharsets.UTF_8)
        }

        val parsed = JsonParser.parseString(jsonText)
        return parsed.asJsonObject
    }

    /**
     * Convenience overload when the fixture is already known to be an array.
     */
    fun loadJsonAsObjectOrThrow(fixturePathFromAssetsRoot: String): JsonObject {
        // Kept for readability at call sites.
        return loadJsonObject(fixturePathFromAssetsRoot)
    }
}

