package com.riyadm.wakenudge.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of "now" and "where". The zone is read on every call, never cached, so a travelling user or a
 * time-zone change is picked up immediately. Tests substitute a fixed implementation.
 */
interface TimeSource {
    fun nowMillis(): Long
    fun zone(): ZoneId
    fun now(): ZonedDateTime = Instant.ofEpochMilli(nowMillis()).atZone(zone())
    /** Forget the JVM's cached default zone; call after the system reports a zone change. */
    fun refreshZone() {}
}

@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun refreshZone() = TimeZone.setDefault(null)
}
