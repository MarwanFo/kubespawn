import { Routes, Route, Navigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Layers3 } from 'lucide-react';
import { getJson, type Profile } from './api';
import { WorkspaceProvider } from './context/WorkspaceContext';
import { WorkspaceLayout } from './layouts/WorkspaceLayout';
import { DeveloperDashboard } from './views/DeveloperDashboard';
import { ReviewInboxView } from './views/ReviewInboxView';
import { AdminView } from './AdminView';
import { ProfileView } from './ProfileView';
import { LoginView } from './views/LoginView';
import { SignupView } from './views/SignupView';
import { ForgotPasswordView } from './views/ForgotPasswordView';
import { ResetPasswordView } from './views/ResetPasswordView';
import { VerifyEmailView } from './views/VerifyEmailView';
import { MfaChallengeView } from './views/MfaChallengeView';
import { OnboardingView } from './views/OnboardingView';
import { NotificationsView } from './views/NotificationsView';

export function App() {
  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => getJson<Profile>('/api/v1/me'),
    retry: false,
  });

  if (me.isPending) {
    return (
      <div className="auth-screen">
        <span className="auth-logo"><Layers3 size={28} className="spin" /></span>
        <h1>KubeSpawn</h1>
        <p>Loading your workspace session…</p>
      </div>
    );
  }

  // If unauthenticated (e.g. 401 or no dev token), allow public auth routes
  if (me.isError) {
    return (
      <Routes>
        <Route path="/login" element={<LoginView />} />
        <Route path="/signup" element={<SignupView />} />
        <Route path="/forgot-password" element={<ForgotPasswordView />} />
        <Route path="/reset-password" element={<ResetPasswordView />} />
        <Route path="/verify-email" element={<VerifyEmailView />} />
        <Route path="/mfa-challenge" element={<MfaChallengeView />} />
        <Route path="/onboarding" element={<OnboardingView />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }

  const profile = me.data;

  // If user is pending onboarding and has 0 active memberships, direct to onboarding
  if (profile.onboardingStatus === 'PENDING_ONBOARDING' && profile.memberships.length === 0) {
    return (
      <Routes>
        <Route path="/onboarding" element={<OnboardingView />} />
        <Route path="*" element={<Navigate to="/onboarding" replace />} />
      </Routes>
    );
  }

  return (
    <WorkspaceProvider profile={profile}>
      <Routes>
        {/* Account access and recovery routes */}
        <Route path="/login" element={<LoginView />} />
        <Route path="/signup" element={<SignupView />} />
        <Route path="/forgot-password" element={<ForgotPasswordView />} />
        <Route path="/reset-password" element={<ResetPasswordView />} />
        <Route path="/verify-email" element={<VerifyEmailView />} />
        <Route path="/mfa-challenge" element={<MfaChallengeView />} />
        <Route path="/onboarding" element={<OnboardingView />} />

        {/* Protected Workspace Layout */}
        <Route path="/" element={<WorkspaceLayout />}>
          <Route index element={<DeveloperDashboard />} />
          <Route path="environments" element={<DeveloperDashboard />} />
          <Route path="reviews" element={<ReviewInboxView />} />
          <Route path="notifications" element={<NotificationsView />} />
          <Route path="admin" element={<AdminView profile={profile} />} />
          <Route path="settings" element={<ProfileView profile={profile} />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </WorkspaceProvider>
  );
}
