package com.stastnarodina.mujrozhlas

import kotlin.test.Test
import kotlin.test.assertEquals

class ResolverTest {

    @Test
    fun `parseSlug extracts slug and strips trailing numeric ID`() {
        val slug = Resolver.parseSlug(
            "https://www.mujrozhlas.cz/radioserial/umberto-eco-foucaultovo-kyvadlo-3453094"
        )
        assertEquals("umberto-eco-foucaultovo-kyvadlo", slug)
    }

    @Test
    fun `parseSlug handles URL without trailing ID`() {
        val slug = Resolver.parseSlug(
            "https://www.mujrozhlas.cz/radioserial/mistr-a-marketka"
        )
        assertEquals("mistr-a-marketka", slug)
    }

    @Test
    fun `parseSlug handles trailing slash`() {
        val slug = Resolver.parseSlug(
            "https://www.mujrozhlas.cz/radioserial/some-show-123/"
        )
        assertEquals("some-show", slug)
    }

    @Test
    fun `slugToSearchQueries generates progressive windows`() {
        val queries = Resolver.slugToSearchQueries("umberto-eco-foucaultovo-kyvadlo")
        // Full 4-word query first
        assertEquals("umberto eco foucaultovo kyvadlo", queries[0])
        // Should contain the 2-word window "foucaultovo kyvadlo"
        assert(queries.contains("foucaultovo kyvadlo"))
        // Single words at the end (longest first, min 4 chars)
        assert(queries.contains("foucaultovo"))
        assert(queries.contains("kyvadlo"))
        assert(queries.contains("umberto"))
        // "eco" is only 3 chars, should not appear as single-word query
        assert(!queries.contains("eco"))
    }

    @Test
    fun `titleToSlug normalizes Czech diacritics`() {
        val slug = Resolver.titleToSlug("Umberto Eco: Foucaultovo kyvadlo")
        assertEquals("umberto-eco-foucaultovo-kyvadlo", slug)
    }

    @Test
    fun `titleToSlug normalizes special characters`() {
        val slug = Resolver.titleToSlug("Příběh — s háčky & čárkami!")
        assertEquals("pribeh-s-hacky-carkami", slug)
    }

    @Test
    fun `scoreMatch returns 1 for exact prefix`() {
        val score = Resolver.scoreMatch(
            "umberto-eco-foucaultovo-kyvadlo",
            "Umberto Eco: Foucaultovo kyvadlo"
        )
        assertEquals(1.0, score)
    }

    @Test
    fun `scoreMatch returns 0_8 for contained slug`() {
        val score = Resolver.scoreMatch(
            "foucaultovo-kyvadlo",
            "Umberto Eco: Foucaultovo kyvadlo. Napínavý příběh"
        )
        assertEquals(0.8, score)
    }

    @Test
    fun `scoreMatch returns word overlap for partial match`() {
        val score = Resolver.scoreMatch(
            "eco-roman-kyvadlo",
            "Umberto Eco: Foucaultovo kyvadlo"
        )
        // "eco" and "kyvadlo" overlap = 2/3
        assertEquals(2.0 / 3.0, score, 0.01)
    }
}
