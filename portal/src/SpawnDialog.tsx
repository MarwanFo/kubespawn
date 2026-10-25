import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Box, X } from 'lucide-react';
import { createEnvironment } from './api';
import type { CreateEnvironment, MutationResult } from './api';

interface Props { open: boolean; onClose: () => void; onCreated: (result: MutationResult) => void }

export function SpawnDialog({ open, onClose, onCreated }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const request = useRef<{ fingerprint: string; key: string } | null>(null);
  const [repository, setRepository] = useState('');
  const [ref, setRef] = useState('main');
  const [ttlHours, setTtlHours] = useState(24);
  const create = useMutation({
    mutationFn: ({ body, key }: { body: CreateEnvironment; key: string }) => createEnvironment(body, key),
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
    if (open && !dialog.current?.open) { create.reset(); dialog.current?.showModal(); }
    if (!open && dialog.current?.open) { dialog.current.close(); }
  }, [open]); // Mutation resets only when opening a new dialog, not during a request.

  function submit(event: FormEvent) {
    event.preventDefault();
    const body = { repository: repository.trim().toLowerCase(), ref: ref.trim(), ttlHours };
    const fingerprint = JSON.stringify(body);
    if (request.current?.fingerprint !== fingerprint) {
      request.current = { fingerprint, key: crypto.randomUUID() };
    }
    create.mutate({ body, key: request.current.key });
  }

  return <dialog ref={dialog} className="spawn-dialog" aria-labelledby="spawn-title" onCancel={event => {
    if (create.isPending) { event.preventDefault(); } else { onClose(); }
  }}>
    <form onSubmit={submit}>
      <div className="dialog-heading"><div><p className="eyebrow">NEW ENVIRONMENT REQUEST</p><h2 id="spawn-title">Give your branch a workspace.</h2></div><button type="button" className="icon-button" aria-label="Close spawn dialog" disabled={create.isPending} onClick={onClose}><X size={19} /></button></div>
      <p className="dialog-description">Save a preview request. An enrolled repository's approved branch will be pinned to a commit and built through GitHub before provisioning.</p>
      <label htmlFor="repository">GitHub repository</label>
      <input id="repository" autoFocus required maxLength={255} pattern="[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-]+" placeholder="owner/repository" value={repository} onChange={event => setRepository(event.target.value)} disabled={create.isPending} />
      <label htmlFor="revision">Branch or revision</label>
      <input id="revision" required maxLength={255} pattern="[A-Za-z0-9][A-Za-z0-9._\/\-]*" value={ref} onChange={event => setRef(event.target.value)} disabled={create.isPending} />
      <label htmlFor="ttl">Request lifetime</label>
      <select id="ttl" value={ttlHours} onChange={event => setTtlHours(Number(event.target.value))} disabled={create.isPending}>
        {[1, 4, 8, 24].map(hours => <option key={hours} value={hours}>{hours} {hours === 1 ? 'hour' : 'hours'}</option>)}
      </select>
      <p className="field-help">Lifetime starts when accepted. Repository/ref verification and deployment happen later.</p>
      {create.error && <div role="alert" className="error-banner">{create.error.message}<small>If the connection was interrupted, retry with the same values to reuse this request.</small></div>}
      <div className="dialog-note"><Box size={17} /><span>Initial status: <strong>Waiting for build</strong><small>This action creates a request, audit event, and pending job. It creates no Kubernetes resources.</small></span></div>
      <div className="dialog-actions"><button type="button" className="secondary-button" disabled={create.isPending} onClick={onClose}>Cancel</button><button type="submit" className="primary-button" disabled={create.isPending}>{create.isPending ? 'Saving request…' : 'Create request'}</button></div>
    </form>
  </dialog>;
}
