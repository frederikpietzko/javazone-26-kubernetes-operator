---
theme: kotlin
transition: view-transition
title: "Building Production-Ready Kubernetes Operators: A Practical Guide"
class: text-center
drawings:
  persist: false
comark: true
duration: 60min
addons:
  - slidev-addon-excalidraw
---

---
layout: intro
kodee:
  variant: greeting
  size: small
  position: corner
---

# Building Production-Ready Kubernetes Operators: A Practical Guide

Frederik Pietzko

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# What is a Kubernetes Operator?

- Encode **domain expertise** as custom controllers
- e.g.: a DB Operator for failovers, backups, migrations...
- Operators you might know:
  - ArgoCD
  - cert-manager
  - Keycloak Operator
  - Crossplane

<!--
- Operators encode domain expertise
- logic not covered by generic k8s primitives like deployments, pods, jobs, configMaps etc

- example DB operator that handles failovers, backups, schema migrations, etc.

- operators you might know
-->

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# The Reconciliation Loop

<Excalidraw
  drawFilePath="./painting1.excalidraw"
  class="w-70%"
  :darkMode="true"
  :background="false"
/>

<!--
- k8s reconciles desired state into actual state in a loop
- does so through controllers
- and kubernetes itself does that for e.g. deployments
- if you write an operator, you need to implement a controller with a reconciliation loop
-->


---
kodee:
  variant: drinking
  size: small
  position: corner
---

# What are Operators made of?

<Excalidraw
  drawFilePath="./painting2.excalidraw"
  class="w-70% mt-10%"
  :darkMode="true"
  :background="false"
/>

<!--
- Operators are made of 2 parts
- first we already briefly mentioned -> controllers
- second are custom resource definitions
-->

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# Custom Resource Definitions

Describe the API of the operator

````md magic-move

```yaml
apiVersion: "apiextensions.k8s.io/v1"
kind: "CustomResourceDefinition"
```

```yaml
apiVersion: "apiextensions.k8s.io/v1"
kind: "CustomResourceDefinition"
spec:
  group: "example.com"
  names:
    kind: "Example"
    plural: "examples"
    singular: "example"
  scope: "Namespaced"
  versions:
    - name: "v1"
      schema:
        openAPIV3Schema:
```

```yaml
apiVersion: "apiextensions.k8s.io/v1"
kind: "CustomResourceDefinition"
spec:
  ...
  versions:
    - name: "v1"
      schema:
        openAPIV3Schema:
          properties:
            spec:
              properties:
                imageName:
                  type: "string"
              type: "object"
```

````
<!--
- unless your Operator can interact with built-in resoruces
- you need to define Custom Resources
- think of them as the API of your operator
- and what openapi and swagger are for rest
- CRDs are for Custom Resources
- even use openapi v3 schema as format
-->

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# How do Controllers work?

<Excalidraw
  drawFilePath="./painting3.excalidraw"
  class="w-100% mt--10%"
  :darkMode="true"
  :background="false"
/>

<!--
- Controllers are essentially just normal pods with elevated permissions interacting with the k8s api
- k8s api exposes http streaming for resources

- when operator starts up it connects to these
- and diffs get put into FIFO Queue

- next the "Informer" caches the state
- and triggers event listeners which put namespaced name into Rate Limiting Work Queue

- You operator runs reconciliation loop to process them
- inside reconciliation loop you can interact with k8s api & update the CR that is being reconciled
-->

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# Reconciliation


<Excalidraw
  drawFilePath="./painting4.excalidraw"
  class="w-100% mt--30%"
  :darkMode="true"
  :background="false"
/>

<!--
- Reconciliation mostly follows this diagram

- first compute dependen resources
- eg. A Pod, Deployment, Job, ConfigMap, etc

- next check if they exist in the cluster

- if they don't exist create them

- if they do exist check if their state is correct
- if it isn't then update them

- if it is correct then do nothing
-->

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# The whole thing in action

<v-clicks>

- Build Small Demo
- Operator that dispatches Code Review Agents in a Job

</v-clicks>

<!--
- easiest to show some code & do some live coding

- build small operator that dispatches code review agents in a job
- for that we need to create Custom Resource Definitions
- and write a reconciliation loop
- the demo will use kotlin but you can also do it in Java if you prefer
-->

---
kodee:
  variant: drinking
  size: small
  position: corner
---

# Target Flow


<v-clicks>

- Apply custom resource `AgentReviewRequest` to the default namespace
- Operator creates ConfigMap with Repo & PR Details
- Operator spawns Agent Job and mounts ConfigMap
- Agent Job create custom resource `ReviewResult` with comments
- Operator continuously updates status on `AgentReviewRequest`

</v-clicks>

---
layout: cover
kodee:
  variant: jumping
  size: large
  position: featured
---

# Demo

Let's write some code!

---
kodee:
  variant: sitting
  size: small
  position: corner
---

# Deploying the Operator

- Apply CRDs to cluster
- setup RBAC & Service Account
- Deploy operator as a normal Deployment with a single replica

<!--
- Deployment is pretty easy
- you can just apply CRDs to the cluster
- remember to setup RBAC & Service Account for the operator
- deploy the operator as normal Deployment with a single replica
-->

---
kodee:
  variant: sitting
  size: small
  position: corner
---

# But what about high availability? 

<v-clicks>

- Scale horizontally!
- **BUT**: we don't want multiple controllers reconciling the same resource
- Operators do leader election

```kotlin
@Bean
fun operatorConfiguration(): Consumer<ConfigurationServiceOverrider> = Consumer { overrider ->
  val leaderConfig =
    LeaderElectionConfigurationBuilder.aLeaderElectionConfiguration("review-agent-operator")
      .withLeaseNamespace("default")
      .build()
  overrider.withLeaderElectionConfiguration(leaderConfig)
}
```
</v-clicks>

<!--
- But what about high availability? 
- Well it's just a Deployment, so we can just increase the replica count
- but we don't want multiple controllers reconciling the same resource
- To solve this operators can do leader election by aquireing a Kubernetes Lease Object
- only the pod that aquired the lease will reconcile the resources
- others will be on standby
-->

---
kodee:
  variant: in-love
  size: large
  position: featured
---

# Thank you for listening!

- Time for Q&A

<div class="mt-5 flex flex-col w-40% items-center">
  <img class="rounded" src="/qr.png" />
  <p >Code & Slides</p>
</div>
