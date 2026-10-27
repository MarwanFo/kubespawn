import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Box, X, FolderGit2 } from 'lucide-react';
import {
  createEnvironment,
  createScopedEnvironment,
  getProjectRepositories,
  type CreateEnvironment,
  type MutationResult
} from './api';

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (result: MutationResult) => void;
  tenantId?: string;
  projectId?: string;
  projectName?: string;
}

export function SpawnDialog({ open, onClose, onCreated, tenantId, projectId, projectName }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const request = useRef<{ fingerprint: string; key: string } | null>(null);
  const [repository, setRepository] = useState('');
  const [ref, setRef] = useState('main');
  const [ttlHours, setTtlHours] = useState(24);

  const reposQuery = useQuery({
    queryKey: ['project-repositories', tenantId, projectId],
    queryFn: () => (tenantId && projectId ? getProjectRepositories(tenantId, projectId) : Promise.resolve([])),
    enabled: Boolean(open && tenantId && projectId),
  });

  const enrolledRepos = reposQuery.data?.filter(r => r.enabled) ?? [];

  const create = useMutation({
    mutationFn: ({ body, key }: { body: CreateEnvironment; key: string }) => {
      if (tenantId && projectId) {
        return createScopedEnvironment(tenantId, projectId, body, key);
      }
      return createEnvironment(body, key);
    },
    retry: false,
    onSuccess: result => {
      request.current = null;
      setRepository('');
      setRef('main');
      setTtlHours(24);
      onCreated(result);
      onClose();
    },
  });

  useEffect(() => {
    if (open && !dialog.current?.open) {
      create.reset();
      dialog.current?.showModal();
    }
    if (!open && dialog.current?.open) {
      dialog.current.close();
    }
  }, [open]);

  // Pre-fill repository from first enrolled repo if empty
  useEffect(() => {
    if (open && enrolledRepos.length > 0 && !repository) {
      setRepository(enrolledRepos[0].name);
      setRef(enrolledRepos[0].branch);
    }
  }, [open, enrolledRepos, repository]);

  function handleRepoChange(selected: string) {
    setRepository(selected);
    const found = enrolledRepos.find(r => r.name === selected);
    if (found) {
      setRef(found.branch);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const body = { repository: repository.trim().toLowerCase(), ref: ref.trim(), ttlHours };
    const fingerprint = JSON.stringify({ ...body, tenantId, projectId });
    if (request.current?.fingerprint !== fingerprint) {
      request.current = { fingerprint, key: crypto.randomUUID() };
    }
    create.mutate({ body, key: request.current.key });
  }

  return (
    <dialog ref={dialog} className="spawn-dialog" aria-labelledby="spawn-title" onCancel={event => {
      if (create.isPending) { event.preventDefault(); } else { onClose(); }
    }}>
      <form onSubmit={submit}>
        <div className="dialog-heading">
          <div>
            <p className="eyebrow">
              {projectName ? `PROJECT: ${projectName.toUpperCase()}` : 'NEW ENVIRONMENT REQUEST'}
            </p>
            <h2 id="spawn-title">Give your branch a workspace.</h2>
          </div>
          <button type="button" className="icon-button" aria-label="Close spawn dialog" disabled={create.isPending} onClick={onClose}>
            <X size={19} />
          </button>
        </div>

        <p className="dialog-description">
          Save a preview request. An enrolled repository's approved branch will be pinned to a commit and built through GitHub before provisioning.
        </p>

        {enrolledRepos.length > 0 ? (
          <>
            <label htmlFor="repo-select">
              <FolderGit2 size={14} style={{ display: 'inline', marginRight: '6px' }} />
              Enrolled Project Repository
            </label>
            <select
              id="repo-select"
              value={repository}
              onChange={e => handleRepoChange(e.target.value)}
              disabled={create.isPending}
            >
              {enrolledRepos.map(r => (
                <option key={r.id} value={r.name}>
                  {r.name} (Policy v{r.policyVersion} &middot; branch: {r.branch})
                </option>
              ))}
            </select>
          </>
        ) : (
          <>
            <label htmlFor="repository">GitHub repository</label>
            <input
              id="repository"
              autoFocus
              required
              maxLength={255}
              pattern="[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-]+"
              placeholder="owner/repository"
              value={repository}
              onChange={event => setRepository(event.target.value)}
              disabled={create.isPending}
            />
          </>
        )}

        <label htmlFor="revision">Branch or revision</label>
        <input
          id="revision"
          required
          maxLength={255}
          pattern="[A-Za-z0-9][A-Za-z0-9._\/\-]*"
          value={ref}
          onChange={event => setRef(event.target.value)}
          disabled={create.isPending}
        />

        <label htmlFor="ttl">Request lifetime</label>
        <select id="ttl" value={ttlHours} onChange={event => setTtlHours(Number(event.target.value))} disabled={create.isPending}>
          {[1, 4, 8, 24].map(hours => (
            <option key={hours} value={hours}>
              {hours} {hours === 1 ? 'hour' : 'hours'}
            </option>
          ))}
        </select>
        <p className="field-help">Lifetime starts when accepted. Repository/ref verification and deployment happen later.</p>

        {create.error && (
          <div role="alert" className="error-banner">
            {create.error.message}
            <small>If the connection was interrupted, retry with the same values to reuse this request.</small>
          </div>
        )}

        <div className="dialog-note">
          <Box size={17} />
          <span>
            Initial status: <strong>Waiting for build</strong>
            <small>This action creates a request, audit event, and pending job. It creates no Kubernetes resources.</small>
          </span>
        </div>

        <div className="dialog-actions">
          <button type="button" className="secondary-button" disabled={create.isPending} onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="primary-button" disabled={create.isPending}>
            {create.isPending ? 'Saving request…' : 'Create request'}
          </button>
        </div>
      </form>
    </dialog>
  );
}
