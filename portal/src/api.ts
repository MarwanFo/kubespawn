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

export interface Membership {
  tenantId: string;
  tenantKey: string;
  tenantName: string;
  role: 'DEVELOPER' | 'QA_REVIEWER' | 'PORTAL_ADMIN' | 'VIEWER' | 'MAINTAINER' | 'TENANT_ADMIN';
  status: 'ACTIVE' | 'SUSPENDED';
}

export interface Project {
  id: string;
  tenantId: string;
  projectKey: string;
  name: string;
  description: string | null;
  status: 'ACTIVE' | 'ARCHIVED';
  createdAt: string;
}

export interface Profile {
  id: string;
  email: string | null;
  displayName: string;
  avatarUrl: string | null;
  status: 'ACTIVE' | 'SUSPENDED';
  onboardingStatus?: 'PENDING_ONBOARDING' | 'COMPLETED';
  platformAdmin: boolean;
  authMode: 'local' | 'oidc';
  memberships: Membership[];
}

export interface AdminOverview {
  users: number;
  activeUsers: number;
  tenants: number;
  activeEnvironments: number;
  queuedEnvironments: number;
  failedEnvironments: number;
}

export interface AdminUser {
  id: string;
  email: string | null;
  displayName: string;
  status: 'ACTIVE' | 'SUSPENDED';
  onboardingStatus?: 'PENDING_ONBOARDING' | 'COMPLETED';
  platformAdmin: boolean;
  createdAt: string;
  lastSeenAt: string | null;
  memberships: Membership[];
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
  const method = (options.method ?? 'GET').toUpperCase();
  const csrf = document.cookie.split('; ').find(value => value.startsWith('KUBESPAWN-XSRF='))?.split('=')[1];
  const response = await fetch(apiPath(path), {
    ...options,
    credentials: 'same-origin',
    signal: AbortSignal.timeout(15_000),
    headers: { Accept: 'application/json', ...(!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrf
      ? { 'X-KUBESPAWN-XSRF': decodeURIComponent(csrf) } : {}), ...options.headers },
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { detail?: string } | null;
    throw new ApiError(response.status === 401
      ? 'Your session is not authenticated. Sign in again, or restart the local proxy after changing its token.'
      : problem?.detail || `API request failed (${response.status}). Check the backend and database.`, response.status);
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as unknown as T;
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

export function getScopedEnvironments(tenantId: string, projectId: string, limit = 50): Promise<EnvironmentPage> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments?limit=${limit}`);
}

export function createScopedEnvironment(
  tenantId: string,
  projectId: string,
  body: CreateEnvironment,
  key: string
): Promise<MutationResult> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(body),
  });
}

export function extendScopedEnvironment(
  tenantId: string,
  projectId: string,
  environment: Environment,
  expiresAt: string
): Promise<MutationResult> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments/${environment.id}/expiry`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', 'If-Match': `"${environment.intentVersion}"` },
    body: JSON.stringify({ expiresAt }),
  });
}

export function deleteScopedEnvironment(
  tenantId: string,
  projectId: string,
  environment: Environment
): Promise<MutationResult> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments/${environment.id}`, {
    method: 'DELETE',
    headers: { 'If-Match': `"${environment.intentVersion}"` },
  });
}

export function getScopedAudit(
  tenantId: string,
  projectId: string,
  environmentId: string
): Promise<AuditEvent[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments/${environmentId}/audit`);
}

export function getProjects(tenantId: string): Promise<Project[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects`);
}

export interface EffectivePermissions {
  tenantId: string;
  role: string;
  canSpawnPreviews: boolean;
  canReviewPreviews: boolean;
  canManageOrganization: boolean;
  canManageProjects: boolean;
  canAdministerQuotas: boolean;
}

export function createOrganization(name: string, tenantKey?: string): Promise<Profile> {
  return requestJson('/api/v1/onboarding/organizations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, tenantKey: tenantKey || '' }),
  });
}

export function acceptInvitation(invitationToken: string): Promise<Profile> {
  return requestJson('/api/v1/onboarding/invitations/accept', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ invitationToken }),
  });
}

export function getPermissions(tenantId: string): Promise<EffectivePermissions> {
  return requestJson(`/api/v1/tenants/${tenantId}/permissions`);
}

export function updateProfile(displayName: string, avatarUrl: string): Promise<Profile> {
  return requestJson('/api/v1/me', {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ displayName, avatarUrl }),
  });
}

export function provisionUser(body: { issuer: string; subject: string; email: string; displayName: string;
  tenantKey: string; role: Membership['role']; platformAdmin: boolean }): Promise<AdminUser> {
  return requestJson('/api/v1/admin/users', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
}

export function updateMembership(tenantId: string, userId: string, role: Membership['role'],
  status: Membership['status']): Promise<AdminUser> {
  return requestJson(`/api/v1/admin/tenants/${tenantId}/members/${userId}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role, status }),
  });
}

export interface TenantMember {
  userId: string;
  email: string | null;
  displayName: string;
  avatarUrl: string | null;
  role: 'DEVELOPER' | 'QA_REVIEWER' | 'PORTAL_ADMIN';
  status: 'ACTIVE' | 'SUSPENDED';
  joinedAt: string;
  lastSeenAt: string | null;
}

export interface TenantInvitation {
  id: string;
  tenantId: string;
  email: string;
  role: 'DEVELOPER' | 'QA_REVIEWER' | 'PORTAL_ADMIN';
  status: 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';
  expiresAt: string;
  createdAt: string;
  token?: string | null;
}

export interface SessionSummary {
  sessionId: string;
  creationTime: string;
  lastAccessTime: string;
  current: boolean;
}

export function getMembers(tenantId: string): Promise<TenantMember[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/members`);
}

export function updateMemberRole(tenantId: string, userId: string, role: TenantMember['role']): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/members/${userId}/role`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  });
}

export function updateMemberStatus(tenantId: string, userId: string, status: TenantMember['status']): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/members/${userId}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  });
}

export function removeMember(tenantId: string, userId: string): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/members/${userId}`, {
    method: 'DELETE',
  });
}

export function getInvitations(tenantId: string): Promise<TenantInvitation[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/invitations`);
}

export function createInvitation(tenantId: string, email: string, role: TenantInvitation['role']): Promise<TenantInvitation> {
  return requestJson(`/api/v1/tenants/${tenantId}/invitations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, role }),
  });
}

export function revokeInvitation(tenantId: string, invitationId: string): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/invitations/${invitationId}`, {
    method: 'DELETE',
  });
}

export function getSessions(): Promise<SessionSummary[]> {
  return requestJson('/api/v1/me/sessions');
}

export function revokeOtherSessions(): Promise<{ revokedCount: number }> {
  return requestJson('/api/v1/me/sessions/revoke-others', {
    method: 'POST',
  });
}

export interface ProjectRepository {
  id: number;
  tenantId?: string | null;
  projectId?: string | null;
  installationId: number;
  name: string;
  branch: string;
  workflowId: number;
  workflowPath: string;
  workflowHash: string;
  apiImage: string;
  webImage: string;
  chartRevision: string;
  templateHash: string;
  policyVersion: number;
  enabled: boolean;
}

export interface EnrollProjectRepositoryRequest {
  repositoryId: number;
  installationId: number;
  repository: string;
  branch: string;
  workflowPath: string;
  workflowSha256: string;
  apiImage: string;
  webImage: string;
  chartRevision: string;
  templateHash: string;
}

export interface PolicyUpdateRequest {
  allowedBranch: string;
  workflowPath: string;
}

export function getProjectRepositories(tenantId: string, projectId: string): Promise<ProjectRepository[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/repositories`);
}

export function enrollProjectRepository(
  tenantId: string,
  projectId: string,
  body: EnrollProjectRepositoryRequest
): Promise<ProjectRepository> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/repositories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export function removeProjectRepository(
  tenantId: string,
  projectId: string,
  repositoryId: number
): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/repositories/${repositoryId}`, {
    method: 'DELETE',
  });
}

export function updateProjectPolicy(
  tenantId: string,
  projectId: string,
  repositoryId: number,
  body: PolicyUpdateRequest
): Promise<ProjectRepository> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/policy/${repositoryId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export interface PreviewTicketResponse {
  ticket: string;
  exchangeUrl: string;
  previewPath: string;
  expiresAt: string;
}

export interface PreviewCondition {
  type: string;
  status: string;
  reason: string;
  message: string;
}

export interface PreviewDiagnostics {
  environmentId: string;
  tenantId: string;
  projectId: string;
  repository: string;
  requestedRef: string;
  namespace: string;
  phase: string;
  desiredState: string;
  servingRevision: string | null;
  createdAt: string;
  expiresAt: string;
  isExpired: boolean;
  previewUrl: string | null;
  previewRoutingReady: boolean;
  stage: string;
  stageMessage: string;
  conditions: PreviewCondition[];
  recentLogs: string[];
}

export function createPreviewTicket(
  tenantId: string,
  projectId: string,
  environmentId: string
): Promise<PreviewTicketResponse> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments/${environmentId}/preview-ticket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  });
}

export function getPreviewDiagnostics(
  tenantId: string,
  projectId: string,
  environmentId: string
): Promise<PreviewDiagnostics> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/environments/${environmentId}/diagnostics`);
}

export function getScopedProvisioningProgress(
  tenantId: string,
  projectId: string
): Promise<ProvisioningProgress[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/provisioning-progress`);
}

export interface ReviewChecklistItem {
  id: string;
  itemKey: string;
  title: string;
  passed: boolean;
  updatedBy: string;
  updatedAt: string;
}

export interface ReviewComment {
  id: string;
  authorActor: string;
  authorRole: string;
  content: string;
  createdAt: string;
}

export interface ReviewDecision {
  id: string;
  decision: 'APPROVED' | 'CHANGES_REQUESTED';
  decisionActor: string;
  reviewedRevision: string;
  notes?: string;
  createdAt: string;
}

export interface ReviewRequest {
  id: string;
  tenantId: string;
  projectId: string;
  environmentId: string;
  requesterActor: string;
  targetRevision: string;
  prNumber?: number;
  title: string;
  status: 'PENDING' | 'APPROVED' | 'CHANGES_REQUESTED' | 'STALE' | 'CANCELLED';
  createdAt: string;
  updatedAt: string;
  environmentServingRevision?: string;
  isStale: boolean;
  assignedReviewers: string[];
  checklist: ReviewChecklistItem[];
  comments: ReviewComment[];
  decisions: ReviewDecision[];
}

export interface CreateReviewPayload {
  environmentId: string;
  title?: string;
  prNumber?: number;
  reviewerActors?: string[];
  checklistItems?: string[];
}

export interface SubmitDecisionPayload {
  decision: 'APPROVED' | 'CHANGES_REQUESTED';
  reviewedRevision: string;
  notes?: string;
}

export function fetchReviews(
  tenantId: string,
  projectId: string,
  status?: string
): Promise<ReviewRequest[]> {
  const query = status && status !== 'ALL' ? `?status=${encodeURIComponent(status)}` : '';
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/qa-inbox${query}`);
}

export function fetchReview(
  tenantId: string,
  projectId: string,
  reviewId: string
): Promise<ReviewRequest> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/reviews/${reviewId}`);
}

export function createReview(
  tenantId: string,
  projectId: string,
  payload: CreateReviewPayload
): Promise<ReviewRequest> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/reviews`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function updateChecklistItem(
  tenantId: string,
  projectId: string,
  reviewId: string,
  itemKey: string,
  passed: boolean
): Promise<ReviewChecklistItem> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/reviews/${reviewId}/checklist/${itemKey}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ passed }),
  });
}

export function addReviewComment(
  tenantId: string,
  projectId: string,
  reviewId: string,
  content: string
): Promise<ReviewComment> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/reviews/${reviewId}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
}

export function submitReviewDecision(
  tenantId: string,
  projectId: string,
  reviewId: string,
  payload: SubmitDecisionPayload
): Promise<ReviewDecision> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/reviews/${reviewId}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export interface QuotaConsumption {
  activeEnvironments: number;
  queuedEnvironments: number;
  usedCpuMillicores: number;
  usedMemoryMb: number;
  usedStorageMb: number;
}

export interface TenantQuota {
  tenantId: string;
  maxEnvironments: number;
  maxQueuedEnvironments: number;
  cpuMillicoresQuota: number;
  memoryMbQuota: number;
  storageMbQuota: number;
  maxTtlHours: number;
  policyVersion: number;
  updatedAt: string;
  updatedBy: string;
  consumption: QuotaConsumption;
}

export interface ProjectQuota {
  projectId: string;
  tenantId: string;
  maxEnvironments: number;
  cpuMillicoresQuota: number;
  memoryMbQuota: number;
  storageMbQuota: number;
  maxTtlHours: number;
  policyVersion: number;
  updatedAt: string;
  updatedBy: string;
  consumption: QuotaConsumption;
}

export interface UpdateQuotaPayload {
  maxEnvironments: number;
  maxQueuedEnvironments?: number;
  cpuMillicoresQuota: number;
  memoryMbQuota: number;
  storageMbQuota?: number;
  maxTtlHours: number;
  policyVersion: number;
}

export interface EnvironmentInventoryItem {
  id: string;
  tenantId: string;
  projectId: string;
  repositoryName: string;
  requestedRef: string;
  phase: string;
  desiredState: string;
  servingRevision: string | null;
  ownerActor: string;
  cpuMillicores: number;
  memoryMb: number;
  storageMb: number;
  expiresAt: string | null;
  createdAt: string | null;
  lastError: string | null;
}

export interface FailedJob {
  id: string;
  environmentId: string;
  repositoryName: string;
  jobType: string;
  intentVersion: number;
  state: string;
  attempts: number;
  failureCount: number;
  lastError: string | null;
  availableAt: string | null;
  updatedAt: string | null;
}

export interface RecoveryRequestPayload {
  operationType: 'RETRY_OPERATION' | 'RECONCILE_ENVIRONMENT' | 'FORCE_CLEANUP';
  reason: string;
  environmentId: string;
}

export interface RecoveryOperation {
  id: string;
  tenantId: string;
  projectId: string;
  environmentId: string;
  operationType: string;
  reason: string;
  actor: string;
  status: string;
  outcomeDetails: string;
  createdAt: string;
}

export interface AuditEventItem {
  id: string;
  tenantId: string | null;
  projectId: string | null;
  environmentId: string | null;
  actor: string;
  action: string;
  requestId: string;
  details: string;
  createdAt: string;
}

export interface AuditPage {
  items: AuditEventItem[];
  total: number;
  limit: number;
  offset: number;
}

export interface IntegrationHealth {
  databaseOk: boolean;
  workerActive: boolean;
  kubernetesConfigured: boolean;
  previewRoutingReady: boolean;
  githubConfigured: boolean;
  authMode: string;
  checkedAt: string;
}

export function getTenantQuotas(tenantId: string): Promise<TenantQuota> {
  return requestJson(`/api/v1/tenants/${tenantId}/quotas`);
}

export function updateTenantQuotas(tenantId: string, payload: UpdateQuotaPayload): Promise<TenantQuota> {
  return requestJson(`/api/v1/tenants/${tenantId}/quotas`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function getProjectQuotas(tenantId: string, projectId: string): Promise<ProjectQuota> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/quotas`);
}

export function updateProjectQuotas(tenantId: string, projectId: string, payload: UpdateQuotaPayload): Promise<ProjectQuota> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/quotas`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function getEnvironmentInventory(tenantId: string, status?: string): Promise<EnvironmentInventoryItem[]> {
  const url = status && status !== 'ALL'
    ? `/api/v1/tenants/${tenantId}/inventory?status=${encodeURIComponent(status)}`
    : `/api/v1/tenants/${tenantId}/inventory`;
  return requestJson(url);
}

export function getFailedJobs(tenantId: string): Promise<FailedJob[]> {
  return requestJson(`/api/v1/tenants/${tenantId}/failed-jobs`);
}

export function executeRecoveryOperation(
  tenantId: string,
  projectId: string,
  payload: RecoveryRequestPayload
): Promise<RecoveryOperation> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/recovery`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function searchAuditEvents(
  tenantId: string,
  filters?: {
    projectId?: string;
    action?: string;
    actorFilter?: string;
    from?: string;
    to?: string;
    limit?: number;
    offset?: number;
  }
): Promise<AuditPage> {
  const params = new URLSearchParams();
  if (filters?.projectId) params.set('projectId', filters.projectId);
  if (filters?.action) params.set('action', filters.action);
  if (filters?.actorFilter) params.set('actorFilter', filters.actorFilter);
  if (filters?.from) params.set('from', filters.from);
  if (filters?.to) params.set('to', filters.to);
  if (filters?.limit) params.set('limit', filters.limit.toString());
  if (filters?.offset) params.set('offset', filters.offset.toString());

  const qs = params.toString();
  return requestJson(`/api/v1/tenants/${tenantId}/audit${qs ? '?' + qs : ''}`);
}

export function getIntegrationHealth(tenantId: string): Promise<IntegrationHealth> {
  return requestJson(`/api/v1/tenants/${tenantId}/health`);
}

export async function logout(): Promise<void> {
  const csrf = document.cookie.split('; ').find(value => value.startsWith('KUBESPAWN-XSRF='))?.split('=')[1];
  await fetch('/logout', { method: 'POST', credentials: 'same-origin',
    headers: csrf ? { 'X-KUBESPAWN-XSRF': decodeURIComponent(csrf) } : {} });
  window.location.assign('/');
}

// Phase 12: Notifications & Preferences
export interface NotificationItem {
  id: string;
  tenantId: string;
  type: 'INVITATION' | 'REVIEW_REQUESTED' | 'REVIEW_DECISION' | 'ENVIRONMENT_EXPIRING' | 'QUOTA_WARNING' | 'SYSTEM_ALERT';
  title: string;
  message: string;
  targetType?: string;
  targetId?: string;
  targetUrl?: string;
  read: boolean;
  readAt?: string;
  createdAt: string;
}

export interface NotificationPreferences {
  userId: string;
  tenantId: string;
  emailEnabled: boolean;
  inAppEnabled: boolean;
  reviewsEnabled: boolean;
  lifecycleEnabled: boolean;
  quotaAlertsEnabled: boolean;
  updatedAt: string;
}

export interface NotificationStats {
  totalCount: number;
  unreadCount: number;
}

export interface NotificationPage {
  items: NotificationItem[];
  total: number;
  page: number;
  size: number;
  unreadCount: number;
}

export function fetchNotifications(
  tenantId: string,
  options?: { unreadOnly?: boolean; type?: string; page?: number; size?: number }
): Promise<NotificationPage> {
  const params = new URLSearchParams();
  if (options?.unreadOnly !== undefined) params.set('unreadOnly', String(options.unreadOnly));
  if (options?.type) params.set('type', options.type);
  if (options?.page !== undefined) params.set('page', String(options.page));
  if (options?.size !== undefined) params.set('size', String(options.size));

  const qs = params.toString();
  return requestJson(`/api/v1/tenants/${tenantId}/notifications${qs ? '?' + qs : ''}`);
}

export function fetchNotificationStats(tenantId: string): Promise<NotificationStats> {
  return requestJson(`/api/v1/tenants/${tenantId}/notifications/stats`);
}

export function markNotificationRead(tenantId: string, notificationId: string): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/notifications/${notificationId}/read`, {
    method: 'PUT',
  });
}

export function markAllNotificationsRead(tenantId: string): Promise<void> {
  return requestJson(`/api/v1/tenants/${tenantId}/notifications/read-all`, {
    method: 'POST',
  });
}

export function fetchNotificationPreferences(tenantId: string): Promise<NotificationPreferences> {
  return requestJson(`/api/v1/tenants/${tenantId}/notifications/preferences`);
}

export function updateNotificationPreferences(
  tenantId: string,
  prefs: {
    emailEnabled: boolean;
    inAppEnabled: boolean;
    reviewsEnabled: boolean;
    lifecycleEnabled: boolean;
    quotaAlertsEnabled: boolean;
  }
): Promise<NotificationPreferences> {
  return requestJson(`/api/v1/tenants/${tenantId}/notifications/preferences`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(prefs),
  });
}

// -------------------------------------------------------------
// Usage, Plans, and Entitlements (Phase 13)
// -------------------------------------------------------------

export interface Plan {
  id: string;
  name: string;
  tier: 'FREE' | 'PRO' | 'ENTERPRISE';
  description: string;
  maxEnvironments: number;
  maxQueuedEnvironments: number;
  cpuMillicoresQuota: number;
  memoryMbQuota: number;
  storageMbQuota: number;
  maxTtlHours: number;
  priceCentsMonthly: number;
  cpuRateCentsPerCoreHour: number;
  memoryRateCentsPerGbHour: number;
  features: string[];
  isActive: boolean;
  version: number;
}

export interface TenantSubscription {
  tenantId: string;
  planId: string;
  planName: string;
  tier: 'FREE' | 'PRO' | 'ENTERPRISE';
  planVersion: number;
  status: 'ACTIVE' | 'TRIALING' | 'PAST_DUE' | 'CANCELED';
  currentPeriodStart: string;
  currentPeriodEnd: string;
  billingProvider: string;
  cancelAtPeriodEnd: boolean;
}

export interface ProjectUsageSummary {
  projectId: string;
  projectName: string;
  activeEnvironments: number;
  totalCoreHours: number;
  totalMemoryGbHours: number;
  estimatedCostCents: number;
}

export interface UsageSummary {
  tenantId: string;
  subscription: TenantSubscription;
  plan: Plan;
  activeEnvironments: number;
  activeEnvironmentsQuota: number;
  usedCpuMillicores: number;
  cpuMillicoresQuota: number;
  usedMemoryMb: number;
  memoryMbQuota: number;
  usedStorageMb: number;
  storageMbQuota: number;
  periodCoreHours: number;
  periodMemoryGbHours: number;
  estimatedPeriodCostCents: number;
  periodStart: string;
  periodEnd: string;
  projectBreakdown: ProjectUsageSummary[];
}

export interface UsageInterval {
  id: string;
  tenantId: string;
  projectId: string;
  environmentId?: string;
  environmentName: string;
  cpuMillicores: number;
  memoryMb: number;
  storageMb: number;
  startedAt: string;
  endedAt?: string;
  durationSeconds?: number;
  coreHours: number;
  memoryGbHours: number;
  estimatedCostCents: number;
  status: 'ACTIVE' | 'CLOSED' | 'RECONCILED';
}

export interface UsageIntervalsPage {
  items: UsageInterval[];
  page: number;
  size: number;
  total: number;
}

export function fetchUsageSummary(tenantId: string): Promise<UsageSummary> {
  return requestJson(`/api/v1/tenants/${tenantId}/usage/summary`);
}

export function fetchProjectUsage(tenantId: string, projectId: string): Promise<ProjectUsageSummary> {
  return requestJson(`/api/v1/tenants/${tenantId}/projects/${projectId}/usage`);
}

export function fetchUsageIntervals(
  tenantId: string,
  options?: { projectId?: string; from?: string; to?: string; page?: number; size?: number }
): Promise<UsageIntervalsPage> {
  const params = new URLSearchParams();
  if (options?.projectId) params.set('projectId', options.projectId);
  if (options?.from) params.set('from', options.from);
  if (options?.to) params.set('to', options.to);
  if (options?.page !== undefined) params.set('page', String(options.page));
  if (options?.size !== undefined) params.set('size', String(options.size));

  const qs = params.toString();
  return requestJson(`/api/v1/tenants/${tenantId}/usage/history${qs ? '?' + qs : ''}`);
}

export function fetchPlans(): Promise<Plan[]> {
  return requestJson('/api/v1/plans');
}

export function fetchTenantPlan(tenantId: string): Promise<TenantSubscription> {
  return requestJson(`/api/v1/tenants/${tenantId}/plan`);
}

export function changeTenantPlan(tenantId: string, planId: string): Promise<TenantSubscription> {
  return requestJson(`/api/v1/tenants/${tenantId}/plan`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ planId }),
  });
}





