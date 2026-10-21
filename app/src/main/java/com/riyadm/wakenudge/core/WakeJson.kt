package com.riyadm.wakenudge.core

import kotlinx.serialization.json.Json

/** Single JSON configuration shared by storage, mission params and backup so formats never drift apart. */
object WakeJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }
}
