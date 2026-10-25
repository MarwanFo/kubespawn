# ADR 0001: one owner for runtime environment intent

Status: accepted for the project foundation.

PostgreSQL records environment intent. The KubeSpawn controller will translate it into controller-owned namespaces/baselines and Argo CD Applications. Argo CD will manage application resources from a reviewed, pinned chart and verified image digests.

A PR ApplicationSet must not independently generate those Applications. Otherwise TTL namespace deletion can be reversed by reconciliation. Desired absence is monotonic within a generation; respawn uses a new generation and namespace after verified cleanup.

The API has no Kubernetes mutation identity. The future controller is a separately deployed administrative trust boundary, even when the Java modules initially share one Gradle build. Database jobs/outbox are the initial durable execution infrastructure; Redis is not required.

This is Git-backed declarative workload delivery with database-owned runtime intent. Requiring every lifecycle mutation to be Git-authoritative would require a new ADR and a single-owner migration.
