package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceNameGeneratorTest {
    private val generator = ResourceNameGenerator()

    @Test
    fun `creates stable base name from request name`() {
        assertEquals("agent-review-ebfs-jpa-pr-1", generator.generateName("ebfs-jpa-pr-1"))
        assertEquals("agent-review-ebfs-jpa-pr-1", generator.generateName("EBFS_JPA_PR_1"))
    }

    @Test
    fun `sanitizes and bounds generated name deterministically`() {
        val first = generator.generateName("A".repeat(300))
        val second = generator.generateName("A".repeat(299) + "B")

        assertTrue(first.length <= 63)
        assertTrue(first.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")))
        assertNotEquals(first, second)
    }

    @Test
    fun `rejects empty request name`() {
        assertFailsWith<IllegalArgumentException> {
            generator.generateName("")
        }
    }
}
