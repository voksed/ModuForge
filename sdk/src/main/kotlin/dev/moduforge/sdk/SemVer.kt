package dev.moduforge.sdk

/** Semantic version (semver.org 2.0.0). Build metadata is accepted on parse and discarded. */
public data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch).let { if (it != 0) return it }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    override fun toString(): String =
        if (preRelease == null) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    public companion object {
        private val PATTERN = Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        public fun parseOrNull(text: String): SemVer? {
            val groups = PATTERN.matchEntire(text.trim())?.groupValues ?: return null
            return SemVer(
                major = groups[1].toIntOrNull() ?: return null,
                minor = groups[2].toIntOrNull() ?: return null,
                patch = groups[3].toIntOrNull() ?: return null,
                preRelease = groups[4].ifEmpty { null },
            )
        }

        /** @throws IllegalArgumentException when [text] is not a semantic version. */
        public fun parse(text: String): SemVer =
            requireNotNull(parseOrNull(text)) { "Not a semantic version: '$text'" }

        private fun comparePreRelease(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until minOf(left.size, right.size)) {
                val l = left[i]
                val r = right[i]
                val ln = l.toBigIntegerOrNull()
                val rn = r.toBigIntegerOrNull()
                val result = when {
                    ln != null && rn != null -> ln.compareTo(rn)
                    ln != null -> -1
                    rn != null -> 1
                    else -> l.compareTo(r)
                }
                if (result != 0) return result
            }
            return left.size.compareTo(right.size)
        }
    }
}

/**
 * Conjunction of version comparators, e.g. `>=1.0.0 <2.0.0`.
 * Supported operators: `>=`, `>`, `<=`, `<`, `=` (default when omitted).
 */
public class SemVerRange private constructor(private val comparators: List<Comparator>) {

    public operator fun contains(version: SemVer): Boolean = comparators.all { it.matches(version) }

    override fun toString(): String = comparators.joinToString(" ")

    private class Comparator(val operator: String, val version: SemVer) {
        fun matches(candidate: SemVer): Boolean {
            val order = candidate.compareTo(version)
            return when (operator) {
                ">=" -> order >= 0
                ">" -> order > 0
                "<=" -> order <= 0
                "<" -> order < 0
                else -> order == 0
            }
        }

        override fun toString(): String = operator + version
    }

    public companion object {
        private val COMPARATOR = Regex("""^(>=|<=|>|<|=)?(.+)$""")

        public fun parseOrNull(text: String): SemVerRange? {
            val tokens = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val comparators = tokens.map { token ->
                val groups = COMPARATOR.matchEntire(token)?.groupValues ?: return null
                val version = SemVer.parseOrNull(groups[2]) ?: return null
                Comparator(groups[1].ifEmpty { "=" }, version)
            }
            return SemVerRange(comparators)
        }
    }
}
