package com.example

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OperatorRbacManifestTest {
    @Test
    fun `operator RBAC permits exact dynamic Role creation without direct result writes`() {
        val manifest = listOf(
            File("k8s/operator/cluster-role.yaml"),
            File("../k8s/operator/cluster-role.yaml"),
        ).firstOrNull(File::exists)
            ?.readText()
            ?: error("k8s/operator/cluster-role.yaml not found")

        assertTrue(manifest.contains("      - escalate"))
        assertTrue(manifest.contains("      - bind"))
        assertFalse(manifest.contains("      - clusterroles"))

        val resultRule = Regex(
            "(?ms)- apiGroups:\\s+- example.com\\s+resources:\\s+- reviewresults\\s+verbs:\\s+((?:\\s+- [a-z]+)+)",
        ).find(manifest)
        assertNotNull(resultRule)
        val resultVerbs = resultRule.groupValues[1]
        assertFalse(resultVerbs.contains("create"))
        assertFalse(resultVerbs.contains("update"))
        assertFalse(resultVerbs.contains("patch"))
    }
}
