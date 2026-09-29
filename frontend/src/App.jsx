import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useState, useCallback } from 'react'
import { isAuthenticated } from './api/client'
import Layout from './components/Layout'
import LoginForm from './components/LoginForm'
import SearchPage from './pages/SearchPage'
import DocumentsPage from './pages/DocumentsPage'
import AdminPage from './pages/AdminPage'

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated())

  const handleLogin = useCallback(() => {
    setAuthed(true)
  }, [])

  const handleLogout = useCallback(() => {
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
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Layout>
    </BrowserRouter>
  )
}
