package com.example

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperatorRbacManifestTest {
    private val mapper = YAMLMapper.builder().build()
    private val repositoryRoot: Path = locateRepositoryRoot()

    @Test
    fun `operator uses exact namespaced least privilege role`() {
        val role = manifest("role.yaml")

        assertEquals("Role", text(role, "/kind"))
        assertEquals("agent-review-operator", text(role, "/metadata/name"))
        assertEquals("default", text(role, "/metadata/namespace"))
        assertEquals(
            setOf(
                Rule(listOf("example.com"), listOf("agentreviewrequests"), listOf("get", "list", "watch")),
                Rule(listOf("example.com"), listOf("agentreviewrequests/status"), listOf("get", "update", "patch")),
                Rule(listOf("example.com"), listOf("reviewresults"), listOf("get", "list", "watch")),
                Rule(listOf(""), listOf("configmaps"), listOf("get", "list", "watch", "create")),
                Rule(listOf("batch"), listOf("jobs"), listOf("get", "list", "watch", "create")),
            ),
            rules(role).toSet(),
        )
        assertEquals(5, rules(role).size)
        assertTrue(rules(role).none { it.apiGroups == listOf("rbac.authorization.k8s.io") })
        assertTrue(rules(role).none { it.resources.any { resource -> resource == "serviceaccounts" } })
        assertTrue(rules(role).none { it.verbs.any { verb -> verb == "escalate" || verb == "bind" } })
        assertTrue(rules(role).none { it.resources.any { resource -> resource == "reviewresults" } && it.verbs.any { verb -> verb in setOf("create", "update", "patch") } })
        assertFalse(Files.exists(repositoryRoot.resolve("k8s/operator/cluster-role.yaml")))
        assertFalse(Files.exists(repositoryRoot.resolve("k8s/operator/cluster-role-binding.yaml")))
    }

    @Test
    fun `review agent has exact result publisher permissions`() {
        val serviceAccount = manifest("review-agent-service-account.yaml")
        val role = manifest("review-agent-role.yaml")
        val binding = manifest("review-agent-role-binding.yaml")

        assertEquals("ServiceAccount", text(serviceAccount, "/kind"))
        assertEquals("review-agent", text(serviceAccount, "/metadata/name"))
        assertEquals("default", text(serviceAccount, "/metadata/namespace"))
        assertEquals("Role", text(role, "/kind"))
        assertEquals("review-agent-result-publisher", text(role, "/metadata/name"))
        assertEquals("default", text(role, "/metadata/namespace"))
        assertEquals(
            setOf(
                Rule(listOf("example.com"), listOf("reviewresults"), listOf("get", "create", "update", "patch")),
                Rule(listOf("example.com"), listOf("reviewresults/status"), listOf("get", "update", "patch")),
            ),
            rules(role).toSet(),
        )
        assertEquals(2, rules(role).size)
        assertEquals("RoleBinding", text(binding, "/kind"))
        assertEquals("default", text(binding, "/metadata/namespace"))
        assertEquals("ServiceAccount", text(binding, "/subjects/0/kind"))
        assertEquals("review-agent", text(binding, "/subjects/0/name"))
        assertEquals("default", text(binding, "/subjects/0/namespace"))
        assertEquals("Role", text(binding, "/roleRef/kind"))
        assertEquals("rbac.authorization.k8s.io", text(binding, "/roleRef/apiGroup"))
        assertEquals("review-agent-result-publisher", text(binding, "/roleRef/name"))
    }

    @Test
    fun `admission policies enforce exact default namespace behavior`() {
        val defaultPolicy = manifest("validating-admission-policy-default-namespace.yaml")
        val defaultBinding = manifest("validating-admission-policy-default-namespace-binding.yaml")
        val immutablePolicy = manifest("validating-admission-policy.yaml")
        val immutableBinding = manifest("validating-admission-policy-binding.yaml")

        assertEquals("ValidatingAdmissionPolicy", text(defaultPolicy, "/kind"))
        assertEquals("agent-review-request-default-namespace", text(defaultPolicy, "/metadata/name"))
        assertEquals("Fail", text(defaultPolicy, "/spec/failurePolicy"))
        assertEquals("example.com", text(defaultPolicy, "/spec/matchConstraints/resourceRules/0/apiGroups/0"))
        assertEquals("v1", text(defaultPolicy, "/spec/matchConstraints/resourceRules/0/apiVersions/0"))
        assertEquals(setOf("CREATE", "UPDATE"), strings(defaultPolicy, "/spec/matchConstraints/resourceRules/0/operations").toSet())
        assertEquals("agentreviewrequests", text(defaultPolicy, "/spec/matchConstraints/resourceRules/0/resources/0"))
        assertEquals("Namespaced", text(defaultPolicy, "/spec/matchConstraints/resourceRules/0/scope"))
        assertEquals("request.namespace == \"default\"", text(defaultPolicy, "/spec/validations/0/expression"))
        assertEquals("agent-review-request-default-namespace", text(defaultBinding, "/spec/policyName"))
        assertEquals(listOf("Deny"), strings(defaultBinding, "/spec/validationActions"))
        assertEquals(setOf("CREATE", "UPDATE"), strings(defaultBinding, "/spec/matchResources/resourceRules/0/operations").toSet())

        assertEquals("Fail", text(immutablePolicy, "/spec/failurePolicy"))
        assertEquals("request.namespace == \"default\"", text(immutablePolicy, "/spec/matchConstraints/matchConditions/0/expression"))
        assertEquals(listOf("UPDATE"), strings(immutablePolicy, "/spec/matchConstraints/resourceRules/0/operations"))
        assertEquals("agentreviewrequests", text(immutablePolicy, "/spec/matchConstraints/resourceRules/0/resources/0"))
        assertEquals(listOf("Deny"), strings(immutableBinding, "/spec/validationActions"))
        assertEquals(listOf("UPDATE"), strings(immutableBinding, "/spec/matchResources/resourceRules/0/operations"))
        assertEquals("agent-review-request-spec-immutable", text(immutableBinding, "/spec/policyName"))
    }

    @Test
    fun `deployment uses operator service account in default`() {
        val deployment = manifest("deployment.yaml")

        assertEquals("default", text(deployment, "/metadata/namespace"))
        assertEquals("agent-review-operator", text(deployment, "/spec/template/spec/serviceAccountName"))
    }

    private fun manifest(name: String): JsonNode = mapper.readTree(
        Files.readString(repositoryRoot.resolve("k8s/operator/$name")),
    )

    private fun rules(role: JsonNode): List<Rule> = role.at("/rules").toList().map { rule ->
        Rule(
            strings(rule, "/apiGroups"),
            strings(rule, "/resources"),
            strings(rule, "/verbs"),
        )
    }

    private fun text(node: JsonNode, pointer: String): String = node.at(pointer).asText()

    private fun strings(node: JsonNode, pointer: String): List<String> = node.at(pointer).toList().map(JsonNode::asText)

    private data class Rule(
        val apiGroups: List<String>,
        val resources: List<String>,
        val verbs: List<String>,
    )

    private fun locateRepositoryRoot(): Path {
        var current = Paths.get("").toAbsolutePath().normalize()
        while (!Files.isDirectory(current.resolve("k8s/operator"))) {
            current = current.parent ?: error("repository root not found from ${Paths.get("").toAbsolutePath()}")
        }
        return current
    }
}
