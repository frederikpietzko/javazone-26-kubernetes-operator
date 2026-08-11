package com.example

import com.example.reviewer.toFabric8
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewResultOwnerReferenceMapperTest {
    @Test
    fun `maps shared owner reference to Fabric8 owner reference`() {
        val reference =
            Review.OwnerReference(
                apiVersion = "example.com/v1",
                kind = "AgentReviewRequest",
                name = "request-7",
                uid = "uid-7",
            ).toFabric8()

        assertEquals("example.com/v1", reference.apiVersion)
        assertEquals("AgentReviewRequest", reference.kind)
        assertEquals("request-7", reference.name)
        assertEquals("uid-7", reference.uid)
        assertEquals(true, reference.controller)
        assertEquals(false, reference.blockOwnerDeletion)
    }
}
