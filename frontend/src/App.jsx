import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useCallback, useEffect } from 'react'
import { isAuthenticated, clearAuth } from './api/client'
import Layout from './components/Layout'
import LoginForm from './components/LoginForm'
import SearchPage from './pages/SearchPage'
import DocumentsPage from './pages/DocumentsPage'
import AdminPage from './pages/AdminPage'
import WorkspacesPage from './pages/WorkspacesPage'

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated())

  useEffect(() => {
    const handleAuthExpired = () => {
      setAuthed(false)
    }
    window.addEventListener('auth:expired', handleAuthExpired)
    return () => window.removeEventListener('auth:expired', handleAuthExpired)
  }, [])

  const handleLogin = useCallback(() => {
    setAuthed(true)
  }, [])

  const handleLogout = useCallback(() => {
    clearAuth()
    setAuthed(false)
  }, [])

  if (!authed) {
    return <LoginForm onLogin={handleLogin} />
  }

  return (
    <BrowserRouter>
      <Layout onLogout={handleLogout}>
        <Routes>
          <Route path="/" element={<SearchPage />} />
          <Route path="/workspaces" element={<WorkspacesPage />} />
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Layout>
    </BrowserRouter>
  )
}
