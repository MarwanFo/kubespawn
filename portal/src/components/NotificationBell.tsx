import { useState, useRef, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Bell, GitPullRequest, Box, AlertTriangle, ShieldAlert,
  Mail, CheckCheck, Settings, Check, ExternalLink
} from 'lucide-react';
import { useWorkspace } from '../context/WorkspaceContext';
import {
  fetchNotifications,
  fetchNotificationStats,
  markNotificationRead,
  markAllNotificationsRead,
  type NotificationItem
} from '../api';

export function NotificationBell() {
  const { activeTenant } = useWorkspace();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  const tenantId = activeTenant?.tenantId;

  // Close on click outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Query unread count stats
  const statsQuery = useQuery({
    queryKey: ['notification-stats', tenantId],
    queryFn: () => (tenantId ? fetchNotificationStats(tenantId) : Promise.resolve({ totalCount: 0, unreadCount: 0 })),
    enabled: !!tenantId,
    refetchInterval: 30000,
  });

  // Query recent notifications preview when menu is open
  const recentQuery = useQuery({
    queryKey: ['notifications-recent', tenantId],
    queryFn: () => (tenantId ? fetchNotifications(tenantId, { size: 5 }) : Promise.resolve({ items: [], total: 0, page: 0, size: 5, unreadCount: 0 })),
    enabled: !!tenantId && open,
  });

  // Mark single as read mutation
  const markReadMutation = useMutation({
    mutationFn: (notifId: string) => {
      if (!tenantId) throw new Error('No active tenant');
      return markNotificationRead(tenantId, notifId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notification-stats', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-recent', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-inbox', tenantId] });
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
      queryClient.invalidateQueries({ queryKey: ['notifications-recent', tenantId] });
      queryClient.invalidateQueries({ queryKey: ['notifications-inbox', tenantId] });
    },
  });

  const unreadCount = statsQuery.data?.unreadCount || 0;

  const handleNotificationClick = (item: NotificationItem) => {
    if (!item.read) {
      markReadMutation.mutate(item.id);
    }
    setOpen(false);
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
        return <GitPullRequest size={15} className="notif-icon review" />;
      case 'ENVIRONMENT_EXPIRING':
        return <Box size={15} className="notif-icon lifecycle" />;
      case 'QUOTA_WARNING':
        return <AlertTriangle size={15} className="notif-icon quota" />;
      case 'SYSTEM_ALERT':
        return <ShieldAlert size={15} className="notif-icon system" />;
      case 'INVITATION':
        return <Mail size={15} className="notif-icon invite" />;
      default:
        return <Bell size={15} className="notif-icon default" />;
    }
  };

  return (
    <div className="dropdown-container notif-bell-container" ref={menuRef}>
      <button
        id="notif-bell-btn"
        className={`notif-bell-btn ${unreadCount > 0 ? 'has-unread' : ''}`}
        onClick={() => setOpen(!open)}
        aria-label="Workspace Notifications"
        title="Workspace Notifications"
      >
        <Bell size={18} />
        {unreadCount > 0 && (
          <span className="notif-badge">
            {unreadCount > 9 ? '9+' : unreadCount}
            <span className="notif-ping" />
          </span>
        )}
      </button>

      {open && (
        <div className="dropdown-menu notif-dropdown-menu">
          <div className="notif-dropdown-header">
            <div className="notif-header-title">
              <strong>Notifications</strong>
              {unreadCount > 0 && (
                <span className="notif-header-count">{unreadCount} new</span>
              )}
            </div>
            {unreadCount > 0 && (
              <button
                className="notif-mark-all-btn"
                onClick={() => markAllReadMutation.mutate()}
                disabled={markAllReadMutation.isPending}
                title="Mark all notifications as read"
              >
                <CheckCheck size={14} />
                <span>Mark all read</span>
              </button>
            )}
          </div>

          <div className="notif-list-container">
            {recentQuery.isPending && (
              <div className="notif-loading">Loading notifications…</div>
            )}

            {!recentQuery.isPending && (recentQuery.data?.items.length ?? 0) === 0 && (
              <div className="notif-empty">
                <Bell size={24} className="text-muted" />
                <p>No notifications yet</p>
                <small>You're all caught up with your workspace activity.</small>
              </div>
            )}

            {!recentQuery.isPending &&
              recentQuery.data?.items.map((item) => (
                <div
                  key={item.id}
                  className={`notif-item ${item.read ? 'read' : 'unread'}`}
                  onClick={() => handleNotificationClick(item)}
                >
                  <div className="notif-item-icon">{getCategoryIcon(item.type)}</div>
                  <div className="notif-item-body">
                    <div className="notif-item-top">
                      <span className="notif-item-title">{item.title}</span>
                      <span className="notif-item-time">{formatRelativeTime(item.createdAt)}</span>
                    </div>
                    <p className="notif-item-msg">{item.message}</p>
                    {item.targetUrl && (
                      <span className="notif-deep-link">
                        <ExternalLink size={11} /> Open
                      </span>
                    )}
                  </div>
                  {!item.read && (
                    <button
                      className="notif-item-check"
                      title="Mark as read"
                      onClick={(e) => {
                        e.stopPropagation();
                        markReadMutation.mutate(item.id);
                      }}
                    >
                      <Check size={13} />
                    </button>
                  )}
                </div>
              ))}
          </div>

          <div className="notif-dropdown-footer">
            <Link
              to="/notifications"
              className="notif-footer-link"
              onClick={() => setOpen(false)}
            >
              View all notifications
            </Link>
            <Link
              to="/notifications?tab=preferences"
              className="notif-footer-btn"
              title="Notification Preferences"
              onClick={() => setOpen(false)}
            >
              <Settings size={14} />
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
