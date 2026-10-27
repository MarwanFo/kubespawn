import { useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Bell, GitPullRequest, Box, AlertTriangle, ShieldAlert,
  Mail, CheckCheck, Check, ExternalLink, Settings, Filter,
  CheckCircle2, RefreshCw
} from 'lucide-react';
import { useWorkspace } from '../context/WorkspaceContext';
import {
  fetchNotifications,
  fetchNotificationStats,
  markNotificationRead,
  markAllNotificationsRead,
  fetchNotificationPreferences,
  updateNotificationPreferences,
  type NotificationItem
} from '../api';

export function NotificationsView() {
  const { activeTenant } = useWorkspace();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();

  const activeTab = searchParams.get('tab') === 'preferences' ? 'preferences' : 'inbox';
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [page, setPage] = useState(0);

  const tenantId = activeTenant?.tenantId;

  // Query notifications list
  const notificationsQuery = useQuery({
    queryKey: ['notifications-inbox', tenantId, unreadOnly, typeFilter, page],
    queryFn: () => {
      if (!tenantId) return Promise.resolve({ items: [], total: 0, page: 0, size: 20, unreadCount: 0 });
      return fetchNotifications(tenantId, {
        unreadOnly: unreadOnly ? true : undefined,
        type: typeFilter !== 'ALL' ? typeFilter : undefined,
        page,
        size: 20,
      });
    },
    enabled: !!tenantId,
  });

  // Query stats
  const statsQuery = useQuery({
    queryKey: ['notification-stats', tenantId],
    queryFn: () => (tenantId ? fetchNotificationStats(tenantId) : Promise.resolve({ totalCount: 0, unreadCount: 0 })),
    enabled: !!tenantId,
  });

  // Query preferences
  const preferencesQuery = useQuery({
    queryKey: ['notification-preferences', tenantId],
    queryFn: () => (tenantId ? fetchNotificationPreferences(tenantId) : null),
    enabled: !!tenantId && activeTab === 'preferences',
  });

  // Mark single as read mutation
  const markReadMutation = useMutation({
    mutationFn: (notifId: string) => {
      if (!tenantId) throw new Error('No active tenant');
      return markNotificationRead(tenantId, notifId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notification-stats', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-inbox', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-recent', tenantId] });
    },
  });

  // Mark all as read mutation
  const markAllReadMutation = useMutation({
    mutationFn: () => {
      if (!tenantId) throw new Error('No active tenant');
      return markAllNotificationsRead(tenantId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notification-stats', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-inbox', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-recent', tenantId] });
    },
  });

  // Update preferences state & mutation
  const [prefsForm, setPrefsForm] = useState<{
    emailEnabled: boolean;
    inAppEnabled: boolean;
    reviewsEnabled: boolean;
    lifecycleEnabled: boolean;
    quotaAlertsEnabled: boolean;
  } | null>(null);

  // Sync preferences from query
  const prefs = prefsForm ?? preferencesQuery.data ?? {
    emailEnabled: true,
    inAppEnabled: true,
    reviewsEnabled: true,
    lifecycleEnabled: true,
    quotaAlertsEnabled: true,
  };

  const [savedSuccess, setSavedSuccess] = useState(false);

  const updatePrefsMutation = useMutation({
    mutationFn: (newPrefs: typeof prefs) => {
      if (!tenantId) throw new Error('No active tenant');
      return updateNotificationPreferences(tenantId, newPrefs);
    },
    onSuccess: () => {
      setSavedSuccess(true);
      setTimeout(() => setSavedSuccess(false), 3000);
      queryClient.invalidateQueries({ queryKey: ['notification-preferences', tenantId] });
    },
  });

  const handleNotificationClick = (item: NotificationItem) => {
    if (!item.read) {
      markReadMutation.mutate(item.id);
    }
    if (item.targetUrl) {
      navigate(item.targetUrl);
    }
  };

  const formatRelativeTime = (isoString: string) => {
    try {
      const now = Date.now();
      const past = new Date(isoString).getTime();
      const diffSec = Math.floor((now - past) / 1000);
      if (diffSec < 60) return 'just now';
      const diffMin = Math.floor(diffSec / 60);
      if (diffMin < 60) return `${diffMin}m ago`;
      const diffHours = Math.floor(diffMin / 60);
      if (diffHours < 24) return `${diffHours}h ago`;
      const diffDays = Math.floor(diffHours / 24);
      return `${diffDays}d ago`;
    } catch {
      return '';
    }
  };

  const getCategoryIcon = (type: string) => {
    switch (type) {
      case 'REVIEW_REQUESTED':
      case 'REVIEW_DECISION':
        return <GitPullRequest size={16} className="notif-badge-icon review" />;
      case 'ENVIRONMENT_EXPIRING':
        return <Box size={16} className="notif-badge-icon lifecycle" />;
      case 'QUOTA_WARNING':
        return <AlertTriangle size={16} className="notif-badge-icon quota" />;
      case 'SYSTEM_ALERT':
        return <ShieldAlert size={16} className="notif-badge-icon system" />;
      case 'INVITATION':
        return <Mail size={16} className="notif-badge-icon invite" />;
      default:
        return <Bell size={16} className="notif-badge-icon default" />;
    }
  };

  const getCategoryBadgeClass = (type: string) => {
    switch (type) {
      case 'REVIEW_REQUESTED':
      case 'REVIEW_DECISION':
        return 'badge-review';
      case 'ENVIRONMENT_EXPIRING':
        return 'badge-lifecycle';
      case 'QUOTA_WARNING':
        return 'badge-quota';
      case 'SYSTEM_ALERT':
        return 'badge-system';
      case 'INVITATION':
        return 'badge-invite';
      default:
        return 'badge-default';
    }
  };

  const unreadCount = statsQuery.data?.unreadCount || 0;

  return (
    <div className="notifications-view-container">
      {/* Top Banner & Header */}
      <div className="view-header">
        <div>
          <h1>Workspace Notifications</h1>
          <p className="text-muted">
            Track preview review requests, approvals, environment lifecycle events, and quota warning thresholds.
          </p>
        </div>

        {/* Tab Controls */}
        <div className="tab-pill-group">
          <button
            className={`tab-pill-btn ${activeTab === 'inbox' ? 'active' : ''}`}
            onClick={() => setSearchParams({ tab: 'inbox' })}
          >
            <Bell size={15} />
            <span>Inbox</span>
            {unreadCount > 0 && <span className="tab-unread-badge">{unreadCount}</span>}
          </button>
          <button
            className={`tab-pill-btn ${activeTab === 'preferences' ? 'active' : ''}`}
            onClick={() => setSearchParams({ tab: 'preferences' })}
          >
            <Settings size={15} />
            <span>Preferences</span>
          </button>
        </div>
      </div>

      {activeTab === 'inbox' && (
        <div className="notifications-inbox-section">
          {/* Controls Bar */}
          <div className="inbox-filter-bar">
            <div className="filter-group">
              <button
                className={`filter-chip ${!unreadOnly ? 'selected' : ''}`}
                onClick={() => { setUnreadOnly(false); setPage(0); }}
              >
                All Notifications
              </button>
              <button
                className={`filter-chip ${unreadOnly ? 'selected' : ''}`}
                onClick={() => { setUnreadOnly(true); setPage(0); }}
              >
                Unread Only ({unreadCount})
              </button>
            </div>

            <div className="filter-dropdown-wrap">
              <Filter size={14} className="text-muted" />
              <select
                className="filter-select"
                value={typeFilter}
                onChange={(e) => { setTypeFilter(e.target.value); setPage(0); }}
                aria-label="Filter by notification type"
              >
                <option value="ALL">All Categories</option>
                <option value="REVIEW_REQUESTED">QA Review Requests</option>
                <option value="REVIEW_DECISION">QA Decisions</option>
                <option value="QUOTA_WARNING">Quota Warnings</option>
                <option value="ENVIRONMENT_EXPIRING">Lifecycle &amp; TTL</option>
                <option value="SYSTEM_ALERT">System &amp; Recovery</option>
                <option value="INVITATION">Invitations</option>
              </select>
            </div>

            {unreadCount > 0 && (
              <button
                className="btn-secondary mark-all-read-btn"
                onClick={() => markAllReadMutation.mutate()}
                disabled={markAllReadMutation.isPending}
              >
                <CheckCheck size={15} />
                <span>Mark All as Read</span>
              </button>
            )}
          </div>

          {/* Notifications Feed */}
          {notificationsQuery.isPending && (
            <div className="notifications-card loading-card">
              <RefreshCw size={24} className="spin text-muted" />
              <p>Loading notification feed…</p>
            </div>
          )}

          {!notificationsQuery.isPending && (notificationsQuery.data?.items.length ?? 0) === 0 && (
            <div className="notifications-card empty-card">
              <div className="empty-icon-circle">
                <Bell size={28} />
              </div>
              <h3>No Notifications Found</h3>
              <p className="text-muted">
                {unreadOnly
                  ? "You have marked all notifications as read in this organization."
                  : "No events recorded for this category filter."}
              </p>
            </div>
          )}

          {!notificationsQuery.isPending && (notificationsQuery.data?.items.length ?? 0) > 0 && (
            <div className="notification-cards-feed">
              {notificationsQuery.data?.items.map((item) => (
                <div
                  key={item.id}
                  className={`notif-feed-card ${item.read ? 'read' : 'unread'}`}
                  onClick={() => handleNotificationClick(item)}
                >
                  <div className="notif-feed-icon-col">
                    {getCategoryIcon(item.type)}
                  </div>

                  <div className="notif-feed-content">
                    <div className="notif-feed-header">
                      <div className="title-and-badge">
                        <span className={`category-tag ${getCategoryBadgeClass(item.type)}`}>
                          {item.type.replace(/_/g, ' ')}
                        </span>
                        <strong className="notif-feed-title">{item.title}</strong>
                      </div>
                      <span className="notif-feed-timestamp">{formatRelativeTime(item.createdAt)}</span>
                    </div>

                    <p className="notif-feed-message">{item.message}</p>

                    <div className="notif-feed-footer">
                      {item.targetUrl && (
                        <button
                          className="action-deep-btn"
                          onClick={(e) => {
                            e.stopPropagation();
                            handleNotificationClick(item);
                          }}
                        >
                          <ExternalLink size={13} />
                          <span>View Details</span>
                        </button>
                      )}
                    </div>
                  </div>

                  <div className="notif-feed-actions">
                    {!item.read ? (
                      <button
                        className="btn-icon-check"
                        title="Mark as read"
                        onClick={(e) => {
                          e.stopPropagation();
                          markReadMutation.mutate(item.id);
                        }}
                      >
                        <Check size={15} />
                      </button>
                    ) : (
                      <span className="read-indicator" title="Read">
                        <CheckCircle2 size={15} className="text-muted" />
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {activeTab === 'preferences' && (
        <div className="notifications-preferences-section">
          <div className="preferences-card">
            <h2>Notification Channels</h2>
            <p className="text-muted">Control where and how you receive workspace alerts and product updates.</p>

            <div className="toggle-setting-row">
              <div className="toggle-info">
                <strong>Email Notifications</strong>
                <p className="text-muted">
                  Send transactional product notices, invitations, and review alerts to your email via Mailpit.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={prefs.emailEnabled}
                  onChange={(e) => setPrefsForm({ ...prefs, emailEnabled: e.target.checked })}
                />
                <span className="slider round" />
              </label>
            </div>

            <div className="toggle-setting-row">
              <div className="toggle-info">
                <strong>In-App Notifications</strong>
                <p className="text-muted">
                  Display real-time notification badge counts and dropdown alerts in the workspace navigation bar.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={prefs.inAppEnabled}
                  onChange={(e) => setPrefsForm({ ...prefs, inAppEnabled: e.target.checked })}
                />
                <span className="slider round" />
              </label>
            </div>
          </div>

          <div className="preferences-card">
            <h2>Category Alerts</h2>
            <p className="text-muted">Fine-tune the specific event types you wish to be notified about.</p>

            <div className="toggle-setting-row">
              <div className="toggle-info">
                <strong>QA Reviews &amp; Decisions</strong>
                <p className="text-muted">
                  Alerts when assigned to preview reviews, test feedback is left, or review decisions are submitted.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={prefs.reviewsEnabled}
                  onChange={(e) => setPrefsForm({ ...prefs, reviewsEnabled: e.target.checked })}
                />
                <span className="slider round" />
              </label>
            </div>

            <div className="toggle-setting-row">
              <div className="toggle-info">
                <strong>Environment Lifecycle &amp; Expiry</strong>
                <p className="text-muted">
                  Alerts when environments transition to READY, provisioning fails, or preview lifetimes approach expiration.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={prefs.lifecycleEnabled}
                  onChange={(e) => setPrefsForm({ ...prefs, lifecycleEnabled: e.target.checked })}
                />
                <span className="slider round" />
              </label>
            </div>

            <div className="toggle-setting-row">
              <div className="toggle-info">
                <strong>Quota &amp; Capacity Warnings</strong>
                <p className="text-muted">
                  Warnings when active environments or CPU/memory reach 80% of organization capacity limits.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={prefs.quotaAlertsEnabled}
                  onChange={(e) => setPrefsForm({ ...prefs, quotaAlertsEnabled: e.target.checked })}
                />
                <span className="slider round" />
              </label>
            </div>
          </div>

          <div className="preferences-action-bar">
            <button
              className="btn-primary save-prefs-btn"
              onClick={() => updatePrefsMutation.mutate(prefs)}
              disabled={updatePrefsMutation.isPending}
            >
              {updatePrefsMutation.isPending ? 'Saving Preferences…' : 'Save Notification Preferences'}
            </button>
            {savedSuccess && (
              <span className="save-success-msg">
                <CheckCircle2 size={16} /> Preferences successfully updated!
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
