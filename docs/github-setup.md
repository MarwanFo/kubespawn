# Connect a new GitHub App

The integration is implemented, but no GitHub App has been registered or installed by this project. Local preparation created ignored `.env.github` and `.secrets/github-webhook.secret`. GitHub stays disabled until real credentials are supplied. Do not paste a private key or webhook secret into chat or commit either file.

## 1. Choose the webhook address

GitHub needs an HTTPS address ending in `/integrations/github/webhook`. Configure an ingress or development relay that forwards only this route to the local API. Do not expose the local bearer proxy, portal, repository enrollment routes, or database. No tunnel or public listener is opened automatically by the setup scripts.

## 2. Register the App

Open [GitHub's new App page](https://github.com/settings/apps/new), or the corresponding settings for your organization. Use a unique name such as `KubeSpawn-MarwanFo`, the repository URL as the homepage, and your chosen HTTPS webhook address. Copy the webhook secret from the private local `.secrets/github-webhook.secret` file into the App's webhook settings.

Use these repository permissions:

| Permission | Access | Purpose |
|---|---|---|
| Actions | Read and write | Inspect builds/artifacts and dispatch the approved workflow |
| Contents | Read | Resolve commits and inspect the approved workflow |
| Metadata | Read | Identify the selected repository |

Subscribe to **Workflow run** events. Installation removal/suspension and repository removal deliveries are also handled. No user OAuth callback or Kubernetes credentials are needed. Keep the App private to your account/organization and install it on selected repositories only.

Record the App ID, generate/download its RSA private key, and install it on `MarwanFo/kubespawn`. Record the installation ID shown in the installation URL. Find the stable repository ID with:

```powershell
(Invoke-RestMethod 'https://api.github.com/repos/MarwanFo/kubespawn').id
```

## 3. Save credentials locally

Replace the placeholders with your values:

```powershell
./scripts/configure-github.ps1 -AppId '<APP_ID>' -PrivateKeyFile '<PATH_TO_DOWNLOADED_PEM>'
```

The script validates the RSA key, preserves the webhook secret, and updates ignored `.env.github`. It prints no credentials. PKCS#1 keys downloaded from GitHub and PKCS#8 RSA keys are supported. Keep the key outside tracked source files; `.secrets/` is an ignored option for local storage.

For the existing Docker API, start the optional overlay:

```powershell
docker compose --env-file .env --env-file .env.github -f compose.yaml -f compose.github.yaml up -d --build api
```

The overlay mounts the private key read-only as a Docker secret. The regular Compose configuration leaves GitHub disabled. For a native backend, `./scripts/dev-api.ps1` loads `.env.github`; stop the Docker API before starting it on the same port.

## 4. Approve and enable the build workflow

Review `.github/workflows/preview-build.yaml`, protect `main`, and require review for changes to that workflow. This first integration accepts the approved branch of a non-fork repository. It does not create environments automatically from pull requests or run fork code.

In repository **Settings → Secrets and variables → Actions → Variables**, set `KUBESPAWN_PUBLISH_IMAGES` to `true`. This enables the supplied workflow. Its publishing job uses the repository's `GITHUB_TOKEN` with package write permission; the GitHub App key is never given to CI. Confirm that repository/organization Actions policies permit those permissions and the pinned actions.

The workflow produces `ghcr.io/marwanfo/kubespawn-preview-api` and `ghcr.io/marwanfo/kubespawn-preview-web`. HIGH or CRITICAL vulnerability findings, including unfixed findings, block publication. Diagnose/update vulnerable dependencies or base images instead of treating a failed scan as a verified build. The current platform Docker images are build-contract examples; deployment still requires the later workload/runtime configuration.

## 5. Enroll the repository

Run from the committed, pushed checkout. Replace the numeric placeholders:

```powershell
./scripts/enroll-github-repository.ps1 -RepositoryId <REPOSITORY_ID> -InstallationId <INSTALLATION_ID>
```

The helper refuses uncommitted workflow/chart changes, computes the approved workflow SHA-256 and template hash, pins the chart to the current full Git revision, and submits the policy to the local authenticated API. The server checks App repository access, stable identity, non-fork status, workflow state/content hash, and chart commit existence before saving it. Re-enroll intentionally after reviewing workflow, image path, installation, repository name, or chart policy changes. Each enrollment increments the policy version and invalidates earlier verification for new provisioning decisions.

Template hashing uses relative forward-slash paths sorted ordinally, each followed by a NUL byte and UTF-8 file content with LF line endings. The template hash is an operator-approved deployment input; independent chart-content admission remains part of Kubernetes integration.

## 6. Check the first request

Refresh the portal. An existing ACTIVE request for the enrolled `main` branch will resolve once to a full SHA, create one durable build request, and dispatch the approved workflow. It does not follow later branch movements. The webhook receiver acknowledges durable receipt; the worker independently retrieves the current run and approved artifact from GitHub.

After a successful run, verification checks the workflow identity/content, attempt, source/request binding, complete image set, allowed image paths, SHA-256 digests, scan/provenance policy, and downloaded artifact integrity. Within approximately one prerequisite interval, the portal should show **Build verified** with the pinned SHA and API/web digests, then **Provisioning pending**. No preview URL or READY state is created.

GitHub may have sent an initial ping before the API was configured. Redeliver it from the App's delivery page after setup. A completed workflow run may also be redelivered safely; delivery and run-attempt deduplication prevent duplicate verification work.

## Diagnose a pending request

| State/reason | Check |
|---|---|
| `GITHUB_SETUP_REQUIRED` | API restarted with credentials/overlay; key is readable and App ID is correct |
| `REPOSITORY_NOT_ENROLLED` | Repository policy has been submitted successfully |
| `REPOSITORY_DISABLED` | Installation/removal event disabled access; review and re-enroll |
| `REF_NOT_APPROVED` | Request uses the enrolled branch; supplied workflow supports `main` |
| `BUILD_VERIFICATION_REQUIRED` | Actions enabled, image publication variable true, run/scan succeeds, webhook reaches the receiver |
| `WORKFLOW_NOT_APPROVED` | Current workflow bytes differ from the reviewed policy; approve intentionally |
| `BUILD_NOT_SUCCESSFUL` | Inspect the GitHub run; retry it after fixing the failure |
| `STALE_RUN_ATTEMPT` | An old delivery arrived after a rerun; wait for/redeliver the current attempt |
| `KUBERNETES_INTEGRATION_REQUIRED` | Build is verified; deployment is the next milestone |

Repository verification/dispatch jobs are internal queue records, separate from owner-scoped environment operations. Inspect controlled `reason`/`last_error` codes and GitHub delivery states in PostgreSQL when diagnosing them; credentials and upstream error bodies are not stored. Exhausted jobs require operator diagnosis; a general administrative retry endpoint remains future work. A rerun creates a new run-attempt identity and can be verified independently.

Sources: [App registration](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app), [private keys](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps), [webhook signatures](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries).
