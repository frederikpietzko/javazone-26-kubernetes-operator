package com.example

import java.io.File
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperatorRbacManifestTest {
    private val mapper = YAMLMapper.builder().build()

    @Test
    fun `operator uses namespaced least privilege role`() {
        val role = manifest("role.yaml")

        assertEquals("Role", role.at("/kind").asText())
        assertEquals("agent-review-operator", role.at("/metadata/name").asText())
        assertEquals("default", role.at("/metadata/namespace").asText())
        assertTrue(roleRules(role).any { rule ->
            rule.at("/apiGroups/0").asText() == "example.com" &&
                rule.at("/resources/0").asText() == "agentreviewrequests" &&
                rule.at("/verbs").toList().map(JsonNode::asText) == listOf("get", "list", "watch")
        })
        assertTrue(roleRules(role).any { rule ->
            rule.at("/resources/0").asText() == "agentreviewrequests/status" &&
                rule.at("/verbs").toList().map(JsonNode::asText) == listOf("get", "update", "patch")
        })
        assertTrue(roleRules(role).none { it.at("/apiGroups/0").asText() == "rbac.authorization.k8s.io" })
        assertFalse(File("k8s/operator/cluster-role.yaml").exists())
        assertFalse(File("k8s/operator/cluster-role-binding.yaml").exists())
    }

    @Test
    fun `review agent has only result publisher permissions`() {
        val serviceAccount = manifest("review-agent-service-account.yaml")
        val role = manifest("review-agent-role.yaml")
        val binding = manifest("review-agent-role-binding.yaml")

        assertEquals("review-agent", serviceAccount.at("/metadata/name").asText())
        assertEquals("default", serviceAccount.at("/metadata/namespace").asText())
        assertEquals("review-agent-result-publisher", role.at("/metadata/name").asText())
        assertEquals("default", role.at("/metadata/namespace").asText())
        assertEquals(2, roleRules(role).size)
        assertEquals("reviewresults", roleRules(role)[0].at("/resources/0").asText())
        assertEquals("reviewresults/status", roleRules(role)[1].at("/resources/0").asText())
        assertTrue(roleRules(role).all { it.at("/apiGroups/0").asText() == "example.com" })
        assertEquals("review-agent", binding.at("/subjects/0/name").asText())
        assertEquals("review-agent-result-publisher", binding.at("/roleRef/name").asText())
        assertEquals("Role", binding.at("/roleRef/kind").asText())
    }

    @Test
    fun `admission policies enforce default namespace and immutable spec scope`() {
        val defaultPolicy = manifest("validating-admission-policy-default-namespace.yaml")
        val defaultBinding = manifest("validating-admission-policy-default-namespace-binding.yaml")
        val immutablePolicy = manifest("validating-admission-policy.yaml")

        assertEquals("agent-review-request-default-namespace", defaultPolicy.at("/metadata/name").asText())
        assertEquals("request.namespace == \"default\"", defaultPolicy.at("/spec/validations/0/expression").asText())
        assertEquals("Deny", defaultBinding.at("/spec/validationActions/0").asText())
        assertEquals("agent-review-request-default-namespace", defaultBinding.at("/spec/policyName").asText())
        assertEquals(
            "request.namespace == \"default\"",
            immutablePolicy.at("/spec/matchConstraints/matchConditions/0/expression").asText(),
        )
    }

    @Test
    fun `deployment uses operator service account in default`() {
        val deployment = manifest("deployment.yaml")

        assertEquals("default", deployment.at("/metadata/namespace").asText())
        assertEquals("agent-review-operator", deployment.at("/spec/template/spec/serviceAccountName").asText())
    }

    private fun manifest(name: String): JsonNode {
        val file = listOf(File("k8s/operator/$name"), File("../k8s/operator/$name"))
            .firstOrNull(File::exists)
            ?: error("k8s/operator/$name not found")
        return mapper.readTree(file.readText())
    }

    private fun roleRules(role: JsonNode): List<JsonNode> = role.at("/rules").toList()
}
