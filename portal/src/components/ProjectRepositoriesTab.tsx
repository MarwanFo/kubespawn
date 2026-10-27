import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  FolderGit2, GitBranch, ShieldCheck, Trash2, Settings2, Plus,
  CheckCircle2, AlertCircle, FileCode, Layers
} from 'lucide-react';
import {
  getProjectRepositories,
  enrollProjectRepository,
  removeProjectRepository,
  updateProjectPolicy,
  type ProjectRepository,
  type EnrollProjectRepositoryRequest,
  type PolicyUpdateRequest
} from '../api';
import { useWorkspace } from '../context/WorkspaceContext';

export function ProjectRepositoriesTab() {
  const { activeTenant, activeProject, role } = useWorkspace();
  const client = useQueryClient();

  const [showEnroll, setShowEnroll] = useState(false);
  const [editingRepo, setEditingRepo] = useState<ProjectRepository | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);

  const tenantId = activeTenant?.tenantId;
  const projectId = activeProject?.id;
  const isAdmin = role === 'PORTAL_ADMIN';

  const reposQuery = useQuery({
    queryKey: ['project-repositories', tenantId, projectId],
    queryFn: () => (tenantId && projectId ? getProjectRepositories(tenantId, projectId) : Promise.resolve([])),
    enabled: Boolean(tenantId && projectId),
  });

  const removeMutation = useMutation({
    mutationFn: (repositoryId: number) => {
      if (!tenantId || !projectId) throw new Error('Missing tenant or project context');
      return removeProjectRepository(tenantId, projectId, repositoryId);
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['project-repositories', tenantId, projectId] });
      setActionSuccess('Repository disabled successfully.');
      setTimeout(() => setActionSuccess(null), 4000);
    },
  });

  return (
    <div className="project-repositories-tab">
      <div className="section-header-row">
        <div>
          <h3>Project Repositories &amp; Preview Policies</h3>
          <p className="section-subtext">
            Repositories connected to <strong>{activeProject?.name || 'Default Project'}</strong>.
            Previews are strictly policy-governed by branch and verified workflow signatures.
          </p>
        </div>
        {isAdmin && (
          <button
            className="spawn-button small"
            onClick={() => {
              setShowEnroll(!showEnroll);
              setEditingRepo(null);
            }}
          >
            <Plus size={16} /> Enroll Repository
          </button>
        )}
      </div>

      {actionSuccess && (
        <div className="success-banner">
          <CheckCircle2 size={16} />
          <span>{actionSuccess}</span>
        </div>
      )}

      {/* Enroll Repository Form */}
      {showEnroll && tenantId && projectId && (
        <EnrollRepoForm
          tenantId={tenantId}
          projectId={projectId}
          onSuccess={() => {
            setShowEnroll(false);
            void client.invalidateQueries({ queryKey: ['project-repositories', tenantId, projectId] });
            setActionSuccess('Repository enrolled successfully with Policy v1.');
            setTimeout(() => setActionSuccess(null), 4000);
          }}
          onCancel={() => setShowEnroll(false)}
        />
      )}

      {/* Edit Policy Form */}
      {editingRepo && tenantId && projectId && (
        <EditPolicyForm
          tenantId={tenantId}
          projectId={projectId}
          repo={editingRepo}
          onSuccess={(updated) => {
            setEditingRepo(null);
            void client.invalidateQueries({ queryKey: ['project-repositories', tenantId, projectId] });
            setActionSuccess(`Policy updated to Version ${updated.policyVersion} for ${updated.name}.`);
            setTimeout(() => setActionSuccess(null), 4000);
          }}
          onCancel={() => setEditingRepo(null)}
        />
      )}

      {/* Repositories List */}
      {reposQuery.isPending ? (
        <p className="muted">Loading repositories…</p>
      ) : reposQuery.isError ? (
        <div className="error-banner">
          <AlertCircle size={16} />
          <span>{reposQuery.error instanceof Error ? reposQuery.error.message : 'Failed to load repositories.'}</span>
        </div>
      ) : reposQuery.data && reposQuery.data.length > 0 ? (
        <div className="repo-grid">
          {reposQuery.data.map((repo) => (
            <article key={repo.id} className="repo-card">
              <div className="repo-card-top">
                <div className="repo-title-row">
                  <FolderGit2 size={20} className="text-accent" />
                  <span className="repo-name">{repo.name}</span>
                  <span className={`policy-badge ${repo.enabled ? 'active' : 'disabled'}`}>
                    Policy v{repo.policyVersion}
                  </span>
                </div>
                {isAdmin && (
                  <div className="repo-card-actions">
                    <button
                      className="icon-action-btn"
                      title="Update Preview Policy"
                      onClick={() => {
                        setEditingRepo(repo);
                        setShowEnroll(false);
                      }}
                    >
                      <Settings2 size={16} />
                    </button>
                    {repo.enabled && (
                      <button
                        className="icon-action-btn danger"
                        title="Disable Repository"
                        disabled={removeMutation.isPending}
                        onClick={() => {
                          if (confirm(`Disable repository ${repo.name}? Existing previews will be retained, but new builds will be rejected.`)) {
                            removeMutation.mutate(repo.id);
                          }
                        }}
                      >
                        <Trash2 size={16} />
                      </button>
                    )}
                  </div>
                )}
              </div>

              <div className="repo-specs-grid">
                <div className="repo-spec">
                  <span className="spec-label"><GitBranch size={13} /> Allowed Branch</span>
                  <code>{repo.branch}</code>
                </div>
                <div className="repo-spec">
                  <span className="spec-label"><FileCode size={13} /> Workflow Path</span>
                  <code>{repo.workflowPath}</code>
                </div>
                <div className="repo-spec">
                  <span className="spec-label"><Layers size={13} /> API Container</span>
                  <code title={repo.apiImage}>{repo.apiImage.split('/').pop()}</code>
                </div>
                <div className="repo-spec">
                  <span className="spec-label"><Layers size={13} /> Web Container</span>
                  <code title={repo.webImage}>{repo.webImage.split('/').pop()}</code>
                </div>
              </div>

              <div className="repo-card-footer">
                <div className="security-tag">
                  <ShieldCheck size={14} className="text-success" />
                  <span>Installation #{repo.installationId}</span>
                </div>
                <span className="policy-status-text">
                  {repo.enabled ? 'Accepting Signed Webhooks' : 'Disabled'}
                </span>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="empty-state-box">
          <FolderGit2 size={36} className="text-muted" />
          <h4>No repositories connected yet</h4>
          <p>
            Connect a GitHub repository to {activeProject?.name || 'this project'} to automate ephemeral preview deployments on pull requests.
          </p>
          {isAdmin && (
            <button className="spawn-button small" onClick={() => setShowEnroll(true)}>
              <Plus size={16} /> Connect Your First Repository
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function EnrollRepoForm({
  tenantId,
  projectId,
  onSuccess,
  onCancel,
}: {
  tenantId: string;
  projectId: string;
  onSuccess: () => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<EnrollProjectRepositoryRequest>({
    repositoryId: Math.floor(10000000 + Math.random() * 90000000),
    installationId: 1001,
    repository: '',
    branch: 'main',
    workflowPath: '.github/workflows/preview.yml',
    workflowSha256: 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    apiImage: 'ghcr.io/org/api',
    webImage: 'ghcr.io/org/web',
    chartRevision: '0123456789abcdef0123456789abcdef01234567',
    templateHash: 'b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
  });

  const mutation = useMutation({
    mutationFn: () => enrollProjectRepository(tenantId, projectId, form),
    onSuccess,
  });

  return (
    <div className="settings-card enroll-card">
      <div className="settings-card-heading">
        <FolderGit2 size={20} />
        <div>
          <h4>Enroll Project Repository</h4>
          <p>Register a repository with its verified workflow definition and container targets.</p>
        </div>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          mutation.mutate();
        }}
        className="form-grid"
      >
        <label>
          Repository Full Name (e.g. <code>acme/web-frontend</code>)
          <input
            required
            pattern="[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
            value={form.repository}
            onChange={(e) => setForm({ ...form, repository: e.target.value })}
            placeholder="org/repo-name"
          />
        </label>

        <label>
          Allowed Preview Branch
          <input
            required
            value={form.branch}
            onChange={(e) => setForm({ ...form, branch: e.target.value })}
            placeholder="main"
          />
        </label>

        <label>
          Workflow Path
          <input
            required
            value={form.workflowPath}
            onChange={(e) => setForm({ ...form, workflowPath: e.target.value })}
            placeholder=".github/workflows/preview.yml"
          />
        </label>

        <label>
          GitHub App Installation ID
          <input
            required
            type="number"
            value={form.installationId}
            onChange={(e) => setForm({ ...form, installationId: parseInt(e.target.value, 10) || 1001 })}
          />
        </label>

        <label>
          API Image
          <input
            required
            value={form.apiImage}
            onChange={(e) => setForm({ ...form, apiImage: e.target.value })}
            placeholder="ghcr.io/org/api"
          />
        </label>

        <label>
          Web Image
          <input
            required
            value={form.webImage}
            onChange={(e) => setForm({ ...form, webImage: e.target.value })}
            placeholder="ghcr.io/org/web"
          />
        </label>

        {mutation.error && (
          <div className="error-banner">
            <AlertCircle size={16} />
            <span>{mutation.error instanceof Error ? mutation.error.message : 'Enrollment failed.'}</span>
          </div>
        )}

        <div className="form-actions">
          <button type="button" className="secondary-button" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" className="primary-button" disabled={mutation.isPending}>
            {mutation.isPending ? 'Enrolling…' : 'Enroll Repository'}
          </button>
        </div>
      </form>
    </div>
  );
}

function EditPolicyForm({
  tenantId,
  projectId,
  repo,
  onSuccess,
  onCancel,
}: {
  tenantId: string;
  projectId: string;
  repo: ProjectRepository;
  onSuccess: (updated: ProjectRepository) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<PolicyUpdateRequest>({
    allowedBranch: repo.branch,
    workflowPath: repo.workflowPath,
  });

  const mutation = useMutation({
    mutationFn: () => updateProjectPolicy(tenantId, projectId, repo.id, form),
    onSuccess,
  });

  return (
    <div className="settings-card edit-policy-card">
      <div className="settings-card-heading">
        <Settings2 size={20} />
        <div>
          <h4>Update Policy for {repo.name}</h4>
          <p>
            Modifying the preview policy increments the policy version to <strong>v{repo.policyVersion + 1}</strong>.
            New webhook deliveries will require verification against this version.
          </p>
        </div>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          mutation.mutate();
        }}
        className="form-grid"
      >
        <label>
          Allowed Branch Pattern
          <input
            required
            value={form.allowedBranch}
            onChange={(e) => setForm({ ...form, allowedBranch: e.target.value })}
            placeholder="main"
          />
        </label>

        <label>
          Workflow Path
          <input
            required
            value={form.workflowPath}
            onChange={(e) => setForm({ ...form, workflowPath: e.target.value })}
            placeholder=".github/workflows/preview.yml"
          />
        </label>

        {mutation.error && (
          <div className="error-banner">
            <AlertCircle size={16} />
            <span>{mutation.error instanceof Error ? mutation.error.message : 'Policy update failed.'}</span>
          </div>
        )}

        <div className="form-actions">
          <button type="button" className="secondary-button" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" className="primary-button" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving Policy…' : 'Save & Increment Policy Version'}
          </button>
        </div>
      </form>
    </div>
  );
}
