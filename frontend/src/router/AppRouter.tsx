import { LoadingOutlined } from '@ant-design/icons'
import { Spin } from 'antd'
import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from '../store/AuthContext'

const AppShell = lazy(() => import('../components/AppShell'))
const AgentPage = lazy(() => import('../pages/Agent'))
const ChatPage = lazy(() => import('../pages/Chat'))
const KnowledgePage = lazy(() => import('../pages/Knowledge'))
const LoginPage = lazy(() => import('../pages/Login'))
const PrinterAssistantPage = lazy(() => import('../pages/PrinterAssistant'))
const MyApplicationsPage = lazy(() => import('../pages/MyApplications'))
const ApplicationDetailPage = lazy(() => import('../pages/ApplicationDetail'))
const AdminApplicationsPage = lazy(() => import('../pages/AdminApplications'))

function ProtectedLayout() {
  const { isAuthenticated } = useAuth()
  return isAuthenticated ? <AppShell /> : <Navigate to="/login" replace />
}

export default function AppRouter() {
  const { isAuthenticated } = useAuth()
  return (
    <Suspense
      fallback={
        <div className="page-loader">
          <Spin indicator={<LoadingOutlined spin />} />
          <span>正在加载工作台</span>
        </div>
      }
    >
      <Routes>
        <Route
          path="/login"
          element={
            isAuthenticated ? (
              <Navigate to="/printer-assistant" replace />
            ) : (
              <LoginPage />
            )
          }
        />
        <Route element={<ProtectedLayout />}>
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/printer-assistant" element={<PrinterAssistantPage />} />
          <Route path="/my-applications" element={<MyApplicationsPage />} />
          <Route path="/applications/:id" element={<ApplicationDetailPage />} />
          <Route path="/admin/applications" element={<AdminApplicationsPage />} />
          <Route path="/knowledge" element={<KnowledgePage />} />
          <Route path="/agent" element={<AgentPage />} />
        </Route>
        <Route path="/" element={<Navigate to="/printer-assistant" replace />} />
        <Route path="*" element={<Navigate to="/printer-assistant" replace />} />
      </Routes>
    </Suspense>
  )
}
