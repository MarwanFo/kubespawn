import { useState, useRef, useEffect } from 'react';
import { Outlet, NavLink, Link, useNavigate, useLocation } from 'react-router-dom';
import {
  Layers3, Box, GitPullRequest, Users, UserRound, ShieldCheck,
  ChevronDown, Building2, FolderGit2, LogOut, Plus, Bell
} from 'lucide-react';
import { useWorkspace } from '../context/WorkspaceContext';
import { logout } from '../api';
import { NotificationBell } from '../components/NotificationBell';

export function WorkspaceLayout() {
  const { profile, activeTenant, activeProject, projects, role, switchTenant, switchProject, isPlatformAdmin } = useWorkspace();
  const navigate = useNavigate();
  const location = useLocation();

  const [tenantDropdownOpen, setTenantDropdownOpen] = useState(false);
  const [projectDropdownOpen, setProjectDropdownOpen] = useState(false);
  const [userDropdownOpen, setUserDropdownOpen] = useState(false);

  const tenantMenuRef = useRef<HTMLDivElement>(null);
  const projectMenuRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLDivElement>(null);

  // Close dropdowns on outside click
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (tenantMenuRef.current && !tenantMenuRef.current.contains(event.target as Node)) {
        setTenantDropdownOpen(false);
      }
      if (projectMenuRef.current && !projectMenuRef.current.contains(event.target as Node)) {
        setProjectDropdownOpen(false);
      }
      if (userMenuRef.current && !userMenuRef.current.contains(event.target as Node)) {
        setUserDropdownOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const getPageTitle = () => {
    if (location.pathname === '/' || location.pathname.startsWith('/environments')) return 'Developer Environments';
    if (location.pathname.startsWith('/reviews')) return 'QA Review Inbox';
    if (location.pathname.startsWith('/notifications')) return 'Workspace Notifications';
    if (location.pathname.startsWith('/admin')) return 'Portal Administration';
    if (location.pathname.startsWith('/settings')) return 'Profile & Security';
    return 'Workspace';
  };

  return (
    <div className="app-shell">
      {/* Role-Aware Sidebar */}
      <aside className="sidebar">
        <Link to="/" className="brand brand-button" aria-label="KubeSpawn home">
          <span className="brand-mark"><Layers3 size={23} /></span>
          KubeSpawn<span className="brand-dot">.</span>
        </Link>

        {/* Tenant selector pill in sidebar */}
        <div className="sidebar-tenant-badge">
          <span className="workspace-avatar">{activeTenant?.tenantName.slice(0, 2).toUpperCase() || 'KS'}</span>
          <div className="tenant-info">
            <span className="tenant-name">{activeTenant?.tenantName || 'No Workspace'}</span>
            <span className={`tenant-role-pill ${role.toLowerCase()}`}>{role}</span>
          </div>
        </div>

        <div className="nav-label">WORKSPACE</div>
        <nav className="sidebar-nav">
          <NavLink
            to="/"
            end
            className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}
          >
            <Box size={18} />
            <span>Environments</span>
          </NavLink>

          <NavLink
            to="/reviews"
            className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}
          >
            <GitPullRequest size={18} />
            <span>QA Reviews</span>
            <span className="nav-count qa-badge">Inbox</span>
          </NavLink>

          <NavLink
            to="/notifications"
            className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}
          >
            <Bell size={18} />
            <span>Notifications</span>
          </NavLink>

          {(role === 'PORTAL_ADMIN' || isPlatformAdmin) && (
            <NavLink
              to="/admin"
              className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}
            >
              <Users size={18} />
              <span>Admin Center</span>
            </NavLink>
          )}

          <NavLink
            to="/settings"
            className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}
          >
            <UserRound size={18} />
            <span>Profile &amp; Settings</span>
          </NavLink>
        </nav>

        <div className="sidebar-note">
          <ShieldCheck size={17} />
          <span>
            Tenant Isolated
            <small>{profile.authMode === 'local' ? 'Local bootstrap admin' : 'OIDC BFF session'}</small>
          </span>
        </div>

        <div className="sidebar-footer">
          <span className="status-dot online" />
          API Connected
          <small>SaaS Foundation &middot; Phase 02</small>
        </div>
      </aside>

      {/* Main Area */}
      <div className="main-area">
        <header className="topbar">
          <div className="topbar-left">
            {/* Organization Dropdown */}
            <div className="dropdown-container" ref={tenantMenuRef}>
              <button
                className="context-switcher-btn"
                onClick={() => setTenantDropdownOpen(!tenantDropdownOpen)}
                aria-label="Switch Organization"
              >
                <Building2 size={16} className="text-muted" />
                <span className="context-name">{activeTenant?.tenantName || 'Select Org'}</span>
                <span className={`role-badge-mini ${role.toLowerCase()}`}>{role}</span>
                <ChevronDown size={14} />
              </button>

              {tenantDropdownOpen && (
                <div className="dropdown-menu">
                  <div className="dropdown-header">ORGANIZATIONS (TENANTS)</div>
                  {profile.memberships.map(m => (
                    <button
                      key={m.tenantId}
                      className={`dropdown-item ${m.tenantId === activeTenant?.tenantId ? 'active' : ''}`}
                      onClick={() => {
                        switchTenant(m.tenantId);
                        setTenantDropdownOpen(false);
                      }}
                    >
                      <span className="item-avatar">{m.tenantName.slice(0, 2).toUpperCase()}</span>
                      <div className="item-copy">
                        <strong>{m.tenantName}</strong>
                        <small>{m.role.replace('_', ' ')}</small>
                      </div>
                    </button>
                  ))}
                  <div className="dropdown-divider" />
                  <button
                    className="dropdown-item add-action"
                    onClick={() => {
                      setTenantDropdownOpen(false);
                      navigate('/onboarding');
                    }}
                  >
                    <Plus size={15} /> Add Organization / Accept Invite
                  </button>
                </div>
              )}
            </div>

            <span className="topbar-separator">/</span>

            {/* Project Dropdown */}
            <div className="dropdown-container" ref={projectMenuRef}>
              <button
                className="context-switcher-btn"
                onClick={() => setProjectDropdownOpen(!projectDropdownOpen)}
                aria-label="Switch Project"
              >
                <FolderGit2 size={16} className="text-muted" />
                <span className="context-name">{activeProject?.name || 'Default Project'}</span>
                <ChevronDown size={14} />
              </button>

              {projectDropdownOpen && (
                <div className="dropdown-menu">
                  <div className="dropdown-header">PROJECTS IN {activeTenant?.tenantName.toUpperCase()}</div>
                  {projects.map(p => (
                    <button
                      key={p.id}
                      className={`dropdown-item ${p.id === activeProject?.id ? 'active' : ''}`}
                      onClick={() => {
                        switchProject(p.id);
                        setProjectDropdownOpen(false);
                      }}
                    >
                      <div className="item-copy">
                        <strong>{p.name}</strong>
                        <small>{p.projectKey} &middot; {p.description || 'No description'}</small>
                      </div>
                    </button>
                  ))}
                  {projects.length === 0 && (
                    <div className="dropdown-empty">No projects found.</div>
                  )}
                </div>
              )}
            </div>
          </div>

          <div className="topbar-right">
            <span className="breadcrumb-title">{getPageTitle()}</span>

            {/* Notifications Bell */}
            <NotificationBell />

            {/* User Dropdown */}
            <div className="dropdown-container" ref={userMenuRef}>
              <button
                className="user-profile-btn"
                onClick={() => setUserDropdownOpen(!userDropdownOpen)}
              >
                <span className="mini-avatar">{profile.displayName.slice(0, 2).toUpperCase()}</span>
                <div className="user-text">
                  <span className="user-name">{profile.displayName}</span>
                  <small className="user-sub">{profile.platformAdmin ? 'Platform Admin' : role}</small>
                </div>
                <ChevronDown size={13} />
              </button>

              {userDropdownOpen && (
                <div className="dropdown-menu user-dropdown">
                  <div className="dropdown-header">{profile.email || 'Local Administrator'}</div>
                  <Link
                    to="/settings"
                    className="dropdown-item"
                    onClick={() => setUserDropdownOpen(false)}
                  >
                    <UserRound size={15} /> Profile &amp; Security
                  </Link>
                  <div className="dropdown-divider" />
                  <button
                    className="dropdown-item danger-text"
                    onClick={() => void logout()}
                  >
                    <LogOut size={15} /> Sign Out
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        <main>
          <Outlet />
          <footer>
            <span>KubeSpawn Developer Platform &middot; Designed for short-lived preview environments.</span>
            <span>Current Tenant: <strong>{activeTenant?.tenantName}</strong></span>
          </footer>
        </main>
      </div>
    </div>
  );
}
