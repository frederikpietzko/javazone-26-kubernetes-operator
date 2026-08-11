package com.example

data class Repository(
    val url: String,
)

data class Review(
    val repository: Repository,
    val pr: String,
    val kubernetes: Kubernetes,
) {
    data class Kubernetes(
        val namespace: String,
        val name: String,
        val ownerReference: OwnerReference? = null,
    )

    data class OwnerReference(
        val apiVersion: String,
        val kind: String,
        val name: String,
        val uid: String,
        val controller: Boolean = true,
        val blockOwnerDeletion: Boolean = false,
    )
}
