package com.riyadm.wakenudge.update

/** A release number, MAJOR.MINOR.PATCH, compared numerically (so 1.0.10 is newer than 1.0.9). */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private const val MAX_LENGTH = 40
        private const val MAX_DIGITS = 9

        /**
         * Reads a tag or versionName such as "v1.0.42", "1.0.42" or "1.0.42-debug". A leading "v" is tolerated and a
         * suffix after "-" or "+" (debug, rc.1, build metadata) is ignored. Anything that is not exactly three
         * numbers separated by dots is not a version: the result is null and callers treat that as "no update".
         */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.length > MAX_LENGTH) return null
            val core = text.removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                // Plain ASCII digits only: toIntOrNull alone would also accept other scripts' digits and a sign.
                if (part.isEmpty() || part.length > MAX_DIGITS || !part.all { it in '0'..'9' }) return null
                part.toInt()
            }
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}
