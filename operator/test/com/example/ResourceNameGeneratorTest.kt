package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceNameGeneratorTest {
    @Test
    fun `creates stable base name from request name`() {
        assertEquals("agent-review-ebfs-jpa-pr-1", ResourceNameGenerator.baseName("ebfs-jpa-pr-1"))
        assertEquals("agent-review-ebfs-jpa-pr-1", ResourceNameGenerator.baseName("EBFS_JPA_PR_1"))
    }

    @Test
    fun `sanitizes and bounds generated name deterministically`() {
        val first = ResourceNameGenerator.baseName("A".repeat(300))
        val second = ResourceNameGenerator.baseName("A".repeat(299) + "B")

        assertTrue(first.length <= 63)
        assertTrue(first.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")))
        assertNotEquals(first, second)
    }

    @Test
    fun `rejects empty request name`() {
        assertFailsWith<IllegalArgumentException> {
            ResourceNameGenerator.baseName("")
        }
    }
}
