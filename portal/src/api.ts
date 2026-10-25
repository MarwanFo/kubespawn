export interface PlatformStatus {
  name: string;
  version: string;
  mode: string;
  controllerEnabled: boolean;
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
}

export interface EnvironmentPage { items: Environment[] }

export async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(response.status === 401
      ? 'API authentication failed. Check the local token in .env and restart the portal.'
      : `API request failed (${response.status}). Check the backend and database.`);
  }
  return response.json() as Promise<T>;
}
