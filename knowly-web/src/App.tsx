import { useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate, Link, useLocation } from 'react-router-dom'
import { isLoggedIn, logout } from './services/api'
import LoginPage from './pages/LoginPage'
import WorkbenchPage from './pages/WorkbenchPage'
import GraphPage from './pages/GraphPage'
import SettingsPage from './pages/SettingsPage'

/** 路由守卫：未登录跳登录页 */
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  if (!isLoggedIn()) {
    return <Navigate to="/login" replace />
  }
  return <>{children}</>
}

/** 带导航的主布局 */
function MainLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  const navItems = [
    { path: '/', label: '🧹 清洗工作台' },
    { path: '/graph', label: '🔗 知识图谱' },
    { path: '/settings', label: '⚙️ 设置' },
  ]

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* 顶部导航栏 */}
      <nav style={{
        display: 'flex', alignItems: 'center', gap: 24,
        padding: '0 24px', height: 56,
        background: '#001529', color: '#fff',
        boxShadow: '0 1px 4px rgba(0,0,0,0.1)'
      }}>
        <div style={{ fontSize: 18, fontWeight: 600, marginRight: 32 }}>知了 knowly</div>
        {navItems.map(item => (
          <Link key={item.path} to={item.path}
            style={{
              color: location.pathname === item.path ? '#1890ff' : '#ffffffbf',
              textDecoration: 'none', fontSize: 14, padding: '4px 8px',
              borderBottom: location.pathname === item.path ? '2px solid #1890ff' : 'none',
            }}>
            {item.label}
          </Link>
        ))}
        <div style={{ flex: 1 }} />
        <span style={{ color: '#ffffff80', fontSize: 13 }}>
          {localStorage.getItem('knowly_username') || 'admin'}
        </span>
        <button onClick={() => { logout(); window.location.href = '/login' }}
          style={{
            color: '#ffffff80', background: 'transparent', border: 'none',
            cursor: 'pointer', fontSize: 13,
          }}>
          退出
        </button>
      </nav>

      {/* 内容区 */}
      <div style={{ flex: 1, background: '#f0f2f5' }}>
        {children}
      </div>
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={
          <ProtectedRoute>
            <MainLayout>
              <WorkbenchPage />
            </MainLayout>
          </ProtectedRoute>
        } />
        <Route path="/graph" element={
          <ProtectedRoute>
            <MainLayout>
              <GraphPage />
            </MainLayout>
          </ProtectedRoute>
        } />
        <Route path="/settings" element={
          <ProtectedRoute>
            <MainLayout>
              <SettingsPage />
            </MainLayout>
          </ProtectedRoute>
        } />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
