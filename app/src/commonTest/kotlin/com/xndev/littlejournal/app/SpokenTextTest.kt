package com.xndev.littlejournal.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTextTest {

    @Test
    fun `dotted times become clock times`() {
        assertEquals("I got up around 6:30", SpokenText.tidy("I got up around 6.30"))
        assertEquals("done by 9:05", SpokenText.tidy("done by 9.05"))
    }

    @Test
    fun `run-together digits become clock times`() {
        assertEquals(
            "The release went out at 11:45",
            SpokenText.tidy("The release went out at 1145"),
        )
        assertEquals("we left at 6:30", SpokenText.tidy("we left at 630"))
    }

    @Test
    fun `quantities are left alone`() {
        // The whole reason the rules are conservative.
        assertEquals("it cost around 1500 dollars", SpokenText.tidy("it cost around 1500 dollars"))
        assertEquals("ran around 1500 feet", SpokenText.tidy("ran around 1500 feet"))
        assertEquals("about 2400 words", SpokenText.tidy("about 2400 words"))
    }

    @Test
    fun `impossible clock readings are left alone`() {
        assertEquals("at 1999", SpokenText.tidy("at 1999"))   // minute 99
        assertEquals("at 1372", SpokenText.tidy("at 1372"))   // minute 72
        assertEquals("since 1984", SpokenText.tidy("since 1984")) // hour 19 > 12
    }

    @Test
    fun `numbers without a time preposition are left alone`() {
        assertEquals("I counted 1145 of them", SpokenText.tidy("I counted 1145 of them"))
        assertEquals("version 6.30 shipped", SpokenText.tidy("version 6.30 shipped"))
    }

    @Test
    fun `several times in one entry all get fixed`() {
        assertEquals(
            "up at 6:30, standup at 9:15, shipped by 11:45",
            SpokenText.tidy("up at 6.30, standup at 915, shipped by 1145"),
        )
    }

    @Test
    fun `ordinary text is untouched`() {
        val text = "Dinner was leftover chili. Better on the second day, as always."
        assertEquals(text, SpokenText.tidy(text))
    }
}

/** Edge cases around the deliberately narrow time rules. */
class SpokenTextEdgeTest {

    @Test
    fun `every time preposition is recognised`() {
        listOf("at", "around", "by", "until", "till", "before", "after", "since", "from").forEach {
            assertEquals("$it 9:15", SpokenText.tidy("$it 915"), "preposition '$it' should work")
        }
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals("At 11:45", SpokenText.tidy("At 1145"))
        assertEquals("AROUND 6:30", SpokenText.tidy("AROUND 6.30"))
    }

    @Test
    fun `clock boundaries are handled`() {
        assertEquals("at 12:00", SpokenText.tidy("at 1200"))
        assertEquals("at 1:05", SpokenText.tidy("at 105"))
        assertEquals("at 12:59", SpokenText.tidy("at 1259"))
    }

    @Test
    fun `hours above twelve are treated as quantities`() {
        assertEquals("at 1300", SpokenText.tidy("at 1300"))
        assertEquals("at 2359", SpokenText.tidy("at 2359"))
    }

    @Test
    fun `minute sixty and beyond is not a time`() {
        assertEquals("at 1160", SpokenText.tidy("at 1160"))
        assertEquals("at 999", SpokenText.tidy("at 999"))
    }

    @Test
    fun `units after the number keep it a quantity`() {
        // "around" IS a time preposition, so only the trailing unit can save
        // these from being rewritten -- which is the point of the test.
        listOf("dollars", "miles", "people", "words", "calories", "steps", "years").forEach {
            assertEquals("around 1145 $it", SpokenText.tidy("around 1145 $it"), "unit '$it'")
        }
    }

    @Test
    fun `the same number without a unit is read as a time`() {
        assertEquals("around 11:45 we left", SpokenText.tidy("around 1145 we left"))
    }

    @Test
    fun `an empty string survives`() {
        assertEquals("", SpokenText.tidy(""))
    }

    @Test
    fun `text with no numbers at all is returned unchanged`() {
        val text = "Nothing numeric happened today, which is itself worth noting."
        assertEquals(text, SpokenText.tidy(text))
    }
}
