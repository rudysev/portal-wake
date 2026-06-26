package com.portal.wake.wake

/**
 * One parsed wake spec from a [WakeContract.META_KEYWORDS] meta-data string. See [WakeKeywordParser]
 * for the wire format and parsing rules.
 */
data class WakeKeywordSpec(
    val id: String,
    val phrase: String,
    val keyword: String,
    val minConf: Double,
)

/**
 * Parses [WakeContract.META_KEYWORDS] meta-data: specs separated by '|', each
 * `id;phrase;keyword[;minConf]`. Fields are trimmed; `id`/`phrase`/`keyword` are lowercased and
 * `minConf` is clamped to `[0, 1]`. A segment missing any of the three required fields is dropped.
 *
 * [onRejected] is invoked with the raw segment for each non-blank segment we could not fully honor —
 * one that is dropped, or one kept with a defaulted [WakeKeywordSpec.minConf] because its fourth field
 * wasn't a number — so callers can log without pulling in Android. (An omitted or empty fourth field is
 * the normal "use the default" case and is *not* reported.)
 *
 * Pure Kotlin/JVM: no Android dependency, fully unit-tested.
 */
object WakeKeywordParser {

    /**
     * Default [WakeKeywordSpec.minConf] when the fourth field is omitted, empty, or unparseable. The
     * wake app's matcher passes its own baseline via [parse]'s [defaultMinConf], so the two never have
     * to track each other.
     */
    const val DEFAULT_MIN_CONF = 0.50

    fun parse(
        value: String,
        defaultMinConf: Double = DEFAULT_MIN_CONF,
        onRejected: (raw: String) -> Unit = {},
    ): List<WakeKeywordSpec> = value.split('|').mapNotNull { raw ->
        val p = raw.trim().split(';').map { it.trim() }
        if (p.size < 3 || p[0].isEmpty() || p[1].isEmpty() || p[2].isEmpty()) {
            if (raw.isNotBlank()) onRejected(raw)
            return@mapNotNull null
        }
        val confField = p.getOrNull(3)
        val minConf = when {
            confField.isNullOrEmpty() -> defaultMinConf

            else -> confField.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: run {
                onRejected(raw)
                defaultMinConf
            }
        }
        WakeKeywordSpec(
            id = p[0].lowercase(),
            phrase = p[1].lowercase(),
            keyword = p[2].lowercase(),
            minConf = minConf,
        )
    }
}
