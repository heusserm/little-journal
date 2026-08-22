package com.xndev.littlejournal.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tokeniser decides what search can and cannot find, and it runs on both
 * sides of the index. Testing it directly — with no database in sight — is
 * what makes the query behavior further down cheap to reason about.
 */
class SearchTokensTest {

    @Test
    fun `words are lowercased and punctuation is dropped`() {
        assertEquals(listOf("don", "t", "at", "11", "45"), searchTokens("Don't — at 11:45!"))
    }

    @Test
    fun `runs of separators do not produce empty terms`() {
        assertEquals(listOf("one", "two"), searchTokens("  one  ---  two  "))
    }

    @Test
    fun `text with nothing indexable yields no terms at all`() {
        assertEquals(emptyList(), searchTokens("!!! ??? ... "))
    }

    /**
     * The reason the query side needs no escaping: nothing that could be
     * syntax survives. Note `OR` comes back as an ordinary term rather than
     * an operator — search has no query language, only words.
     */
    @Test
    fun `characters that would be FTS or LIKE syntax are stripped, not escaped`() {
        assertEquals(listOf("rain", "or", "spain"), searchTokens("rain* OR \"spain\" -%_"))
    }

    @Test
    fun `accented letters survive as letters`() {
        assertEquals(listOf("café", "señor"), searchTokens("Café Señor"))
    }
}
