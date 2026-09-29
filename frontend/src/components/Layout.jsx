import { NavLink, useLocation } from 'react-router-dom'
import { Search, FolderOpen, Shield, LogOut, Sparkles } from 'lucide-react'
import { getCredentials, clearCredentials } from '../api/client'

export default function Layout({ children, onLogout }) {
  const location = useLocation()
  const creds = getCredentials()
  const username = creds?.username || 'user'

  const handleLogout = () => {
    clearCredentials()
    onLogout()
  }

  const navItems = [
    { to: '/', icon: Search, label: 'Semantic Search' },
    { to: '/documents', icon: FolderOpen, label: 'Documents' },
    { to: '/admin', icon: Shield, label: 'Admin Dashboard' },
  ]

  return (
    <div className="app-layout">
      {/* Sidebar */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <div className="brand-icon">
            <Sparkles size={18} />
          </div>
          <h1>SemSearch</h1>
        </div>

        <nav className="sidebar-nav">
          {navItems.map(({ to, icon: Icon, label }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <Icon size={18} />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="user-badge">
            <div className="avatar">{username.charAt(0)}</div>
            <span>{username}</span>
            <button
              className="btn btn-ghost btn-icon"
              onClick={handleLogout}
              title="Sign out"
              style={{ marginLeft: 'auto' }}
            >
              <LogOut size={16} />
            </button>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="main-content">
        {children}
      </main>
    </div>
  )
}
