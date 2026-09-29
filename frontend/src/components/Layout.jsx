import { NavLink } from 'react-router-dom'
import { Search, FolderOpen, Shield, LogOut, Sparkles } from 'lucide-react'
import { getAuthUser, getCredentials, clearAuth } from '../api/client'

export default function Layout({ children, onLogout }) {
  const user = getAuthUser() || getCredentials()
  const username = user?.username || 'user'
  const displayName = user?.firstName ? `${user.firstName} ${user.lastName || ''}`.trim() : username
  const isAdmin = !!user?.isAdmin

  const handleLogout = () => {
    clearAuth()
    onLogout()
  }

  const navItems = [
    { to: '/', icon: Search, label: 'Semantic Search' },
    { to: '/documents', icon: FolderOpen, label: 'Documents' },
    ...(isAdmin ? [{ to: '/admin', icon: Shield, label: 'Admin Dashboard' }] : []),
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
          <div className="user-badge" style={{ gap: '0.6rem' }}>
            <div className="avatar">{username.charAt(0).toUpperCase()}</div>
            <div style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
              <span
                style={{
                  fontWeight: 600,
                  fontSize: '0.85rem',
                  whiteSpace: 'nowrap',
                  textOverflow: 'ellipsis',
                  overflow: 'hidden',
                }}
              >
                {displayName}
              </span>
              <span
                style={{
                  fontSize: '0.7rem',
                  color: isAdmin ? 'var(--primary-400)' : 'var(--text-tertiary)',
                  fontWeight: isAdmin ? 600 : 400,
                  letterSpacing: isAdmin ? '0.04em' : 'normal',
                }}
              >
                {isAdmin ? 'ADMINISTRATOR' : `@${username}`}
              </span>
            </div>
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
      <main className="main-content">{children}</main>
    </div>
  )
}
