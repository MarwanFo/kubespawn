export interface PlatformStatus {
  name: string;
  version: string;
  mode: string;
  controllerEnabled: boolean;
  workerEnabled: boolean;
  githubConfigured: boolean;
  capabilities: string[];
  nextMilestone: string;
}

export interface Environment {
  id: string;
  repository: string;
  namespace: string;
  desiredState: 'ACTIVE' | 'ABSENT';
  phase: string;
  requestedRevision: string | null;
  servingRevision: string | null;
  createdAt: string;
  expiresAt: string;
  requestedRef: string;
  intentVersion: number;
}

export interface EnvironmentPage { items: Environment[] }

export interface CreateEnvironment { repository: string; ref: string; ttlHours: number }
export interface MutationResult { environment: Environment; operationId: string }
export interface AuditEvent { id: string; action: string; actor: string; requestId: string; createdAt: string }
export interface BuildProgress {
  environmentId: string;
  state: string;
  sourceSha: string | null;
  runId: number | null;
  runUrl: string | null;
  apiImage: string | null;
  apiDigest: string | null;
  webImage: string | null;
  webDigest: string | null;
}
export interface Operation {
  id: string;
  environmentId: string;
  type: string;
  state: 'PENDING' | 'RUNNING' | 'WAITING' | 'COMPLETE' | 'FAILED' | 'SUPERSEDED';
  intentVersion: number;
  attempts: number;
  failureCount: number;
  reason: string;
  message: string;
  createdAt: string;
  updatedAt: string;
  availableAt: string;
  leaseExpiresAt: string | null;
  lastHeartbeatAt: string | null;
  finishedAt: string | null;
}
export interface ProvisioningProgress {
  environmentId: string;
  stage: string | null;
  message: string | null;
  namespace: string;
  application: string;
  capacityReserved: boolean;
  observedAt: string | null;
  previewUrl: string | null;
}

export function retryOperation(operation: Operation, environment: Environment): Promise<Operation> {
  return requestJson(`/api/v1/operations/${operation.id}/retry`, {
    method: 'POST', headers: { 'If-Match': `"${environment.intentVersion}"` },
  });
}

export class ApiError extends Error {
  constructor(message: string, public readonly status: number) { super(message); }
}

function apiPath(path: string): string {
  const preview = window.location.pathname.match(/^\/e\/[0-9a-f-]{36}(?:\/|$)/)?.[0];
  return preview ? `${preview.replace(/\/$/, '')}${path}` : path;
}

export async function requestJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(apiPath(path), {
    ...options,
    signal: AbortSignal.timeout(15_000),
    headers: { Accept: 'application/json', ...options.headers },
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { detail?: string } | null;
    throw new ApiError(response.status === 401
      ? 'API authentication failed. Check the local token in .env and restart the portal.'
      : problem?.detail || `API request failed (${response.status}). Check the backend and database.`, response.status);
  }
  return response.json() as Promise<T>;
}

export function getJson<T>(path: string): Promise<T> { return requestJson<T>(path); }

export function createEnvironment(body: CreateEnvironment, key: string): Promise<MutationResult> {
  return requestJson('/api/v1/environments', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(body),
  });
}

export function extendEnvironment(environment: Environment, expiresAt: string): Promise<MutationResult> {
  return requestJson(`/api/v1/environments/${environment.id}/expiry`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', 'If-Match': `"${environment.intentVersion}"` },
    body: JSON.stringify({ expiresAt }),
  });
}

export function deleteEnvironment(environment: Environment): Promise<MutationResult> {
  return requestJson(`/api/v1/environments/${environment.id}`, {
    method: 'DELETE', headers: { 'If-Match': `"${environment.intentVersion}"` },
  });
}
