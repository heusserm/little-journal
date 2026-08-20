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
