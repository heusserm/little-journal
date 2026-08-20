package com.xndev.littlejournal.app

/**
 * Cleanup for text that came out of the speech recognizer.
 *
 * `SpeechTranscriber` gets prose and proper nouns right but mangles times:
 * "six thirty" arrives as `6.30` and "eleven forty five" as `1145`. Fixing that
 * here was the deliberate trade -- the alternative recognizer formats times
 * correctly but drops people's names, which is not recoverable downstream.
 *
 * Conservative by design. A bare number is only read as a time when a
 * time-ish preposition precedes it and the hour is 1-12, because "around 1500
 * dollars" must not become "around 15:00 dollars". That means some real times
 * are missed. Missing one is a shrug; corrupting a number is a bug.
 */
object SpokenText {

    private const val LEAD = "(at|around|by|until|till|before|after|since|from)"

    private const val FIRST_HOUR = 1
    private const val LAST_HOUR = 12      // 12-hour only: see isClock
    private const val LAST_MINUTE = 59
    private const val SINGLE_DIGIT = 10

    /** Units that prove a number is a quantity, not a clock reading. */
    private val UNITS = listOf(
        "dollars", "bucks", "pounds", "euros", "cents", "feet", "foot", "inches",
        "miles", "km", "kg", "lbs", "people", "words", "calories", "steps", "years", "days",
    )
    private val NOT_TIME = "(?!\\s*(${UNITS.joinToString("|")}))"

    private val DOTTED = Regex("""\b$LEAD\s+(\d{1,2})\.(\d{2})\b$NOT_TIME""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""\b$LEAD\s+(\d{3,4})\b$NOT_TIME""", RegexOption.IGNORE_CASE)

    fun tidy(raw: String): String = fixBare(fixDotted(raw))

    private fun fixDotted(text: String): String = DOTTED.replace(text) { m ->
        val (lead, hourText, minuteText) = m.destructured
        val hour = hourText.toInt()
        val minute = minuteText.toInt()
        if (isClock(hour, minute)) "$lead $hour:${pad(minute)}" else m.value
    }

    private fun fixBare(text: String): String = BARE.replace(text) { m ->
        val (lead, digits) = m.destructured
        val hour = digits.dropLast(2).toInt()
        val minute = digits.takeLast(2).toInt()
        if (isClock(hour, minute)) "$lead $hour:${pad(minute)}" else m.value
    }

    /** 1-12 only: a 24-hour reading is rare in speech and too easy to confuse with a quantity. */
    private fun isClock(hour: Int, minute: Int) =
        hour in FIRST_HOUR..LAST_HOUR && minute in 0..LAST_MINUTE

    private fun pad(n: Int) = if (n < SINGLE_DIGIT) "0$n" else "$n"
}
