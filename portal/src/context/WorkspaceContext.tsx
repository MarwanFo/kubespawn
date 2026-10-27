import { createContext, useContext, useEffect, useState, useMemo } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient, useQuery } from '@tanstack/react-query';
import { getProjects, getPermissions, type Membership, type Profile, type Project, type EffectivePermissions } from '../api';

interface WorkspaceContextValue {
  profile: Profile;
  activeTenant: Membership | null;
  activeProject: Project | null;
  projects: Project[];
  permissions: EffectivePermissions | null;
  role: 'DEVELOPER' | 'QA_REVIEWER' | 'PORTAL_ADMIN';
  switchTenant: (tenantId: string) => void;
  switchProject: (projectId: string) => void;
  isPlatformAdmin: boolean;
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

export function WorkspaceProvider({ profile, children }: { profile: Profile; children: ReactNode }) {
  const queryClient = useQueryClient();
  const memberships = profile.memberships;

  // Saved active tenant key or first membership
  const [activeTenantId, setActiveTenantId] = useState<string>(() => {
    const saved = localStorage.getItem('kubespawn_tenant_id');
    const match = memberships.find(m => m.tenantId === saved && m.status === 'ACTIVE');
    return match ? match.tenantId : (memberships[0]?.tenantId ?? '');
  });

  const activeTenant = useMemo(() => {
    return memberships.find(m => m.tenantId === activeTenantId) || memberships[0] || null;
  }, [memberships, activeTenantId]);

  // Fetch projects for the active tenant
  const projectsQuery = useQuery({
    queryKey: ['projects', activeTenant?.tenantId],
    queryFn: () => (activeTenant ? getProjects(activeTenant.tenantId) : Promise.resolve([])),
    enabled: Boolean(activeTenant?.tenantId),
    staleTime: 60_000,
  });

  const projects = projectsQuery.data ?? [];

  // Fetch effective permissions for active tenant
  const permissionsQuery = useQuery({
    queryKey: ['permissions', activeTenant?.tenantId],
    queryFn: () => (activeTenant ? getPermissions(activeTenant.tenantId) : Promise.resolve(null)),
    enabled: Boolean(activeTenant?.tenantId),
    staleTime: 60_000,
  });

  const permissions = permissionsQuery.data ?? null;

  const [activeProjectId, setActiveProjectId] = useState<string>('');

  // Default project selection when projects load
  useEffect(() => {
    if (projects.length > 0) {
      if (!projects.some((p: Project) => p.id === activeProjectId)) {
        setActiveProjectId(projects[0].id);
      }
    } else {
      setActiveProjectId('');
    }
  }, [projects, activeProjectId]);

  const activeProject = useMemo(() => {
    return projects.find((p: Project) => p.id === activeProjectId) || projects[0] || null;
  }, [projects, activeProjectId]);

  // Normalize role to target SaaS roles
  const role: 'DEVELOPER' | 'QA_REVIEWER' | 'PORTAL_ADMIN' = useMemo(() => {
    const r = activeTenant?.role;
    if (r === 'PORTAL_ADMIN' || r === 'TENANT_ADMIN') return 'PORTAL_ADMIN';
    if (r === 'QA_REVIEWER' || r === 'VIEWER') return 'QA_REVIEWER';
    return 'DEVELOPER';
  }, [activeTenant]);

  const switchTenant = (tenantId: string) => {
    if (tenantId === activeTenantId) return;

    // Strict cache isolation: clear cached environment queries from previous tenant
    void queryClient.cancelQueries();
    queryClient.removeQueries({ queryKey: ['environments'] });
    queryClient.removeQueries({ queryKey: ['operations'] });
    queryClient.removeQueries({ queryKey: ['github-builds'] });
    queryClient.removeQueries({ queryKey: ['provisioning'] });
    queryClient.removeQueries({ queryKey: ['admin-overview'] });
    queryClient.removeQueries({ queryKey: ['admin-users'] });
    queryClient.removeQueries({ queryKey: ['permissions'] });
    queryClient.removeQueries({ queryKey: ['projects'] });

    localStorage.setItem('kubespawn_tenant_id', tenantId);
    setActiveTenantId(tenantId);
  };

  const switchProject = (projectId: string) => {
    setActiveProjectId(projectId);
  };

  const value = useMemo<WorkspaceContextValue>(() => ({
    profile,
    activeTenant,
    activeProject,
    projects,
    permissions,
    role,
    switchTenant,
    switchProject,
    isPlatformAdmin: profile.platformAdmin,
  }), [profile, activeTenant, activeProject, projects, permissions, role]);

  return (
    <WorkspaceContext.Provider value={value}>
      {children}
    </WorkspaceContext.Provider>
  );
}

export function useWorkspace() {
  const context = useContext(WorkspaceContext);
  if (!context) {
    throw new Error('useWorkspace must be used within a WorkspaceProvider');
  }
  return context;
}
