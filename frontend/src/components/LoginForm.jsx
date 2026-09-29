import { useState } from 'react'
import { Lock, User, Sparkles } from 'lucide-react'
import { motion } from 'framer-motion'
import { saveCredentials, validateCredentials } from '../api/client'

export default function LoginForm({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const valid = await validateCredentials(username, password)
      if (valid) {
        saveCredentials(username, password)
        onLogin()
      } else {
        setError('Invalid credentials. Try admin/admin, alice/alice, or bob/bob.')
      }
    } catch (err) {
      setError('Connection failed. Is the backend running?')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-overlay">
      {/* Background decorative gradients */}
      <div style={{
        position: 'absolute',
        inset: 0,
        background: 'radial-gradient(ellipse 80% 60% at 50% 30%, hsla(217, 91%, 55%, 0.08), transparent), radial-gradient(ellipse 60% 80% at 30% 80%, hsla(280, 67%, 55%, 0.06), transparent)',
        pointerEvents: 'none',
      }} />

      <motion.div
        className="login-card"
        initial={{ opacity: 0, y: 20, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.4, ease: 'easeOut' }}
      >
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: '1rem' }}>
          <div className="brand-icon" style={{
            width: 48,
            height: 48,
            background: 'linear-gradient(135deg, hsl(217, 91%, 55%), hsl(280, 67%, 55%))',
            borderRadius: 14,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}>
            <Sparkles size={24} color="white" />
          </div>
        </div>

        <h2>Alfresco SemSearch</h2>
        <p className="login-subtitle">
          Semantic Document Search Engine
        </p>

        <form className="login-form" onSubmit={handleSubmit}>
          {error && (
            <motion.div
              className="login-error"
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
            >
              {error}
            </motion.div>
          )}

          <div className="form-group">
            <label htmlFor="username">
              <User size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 4 }} />
              Username
            </label>
            <input
              id="username"
              type="text"
              className="input"
              placeholder="admin"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoFocus
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">
              <Lock size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 4 }} />
              Password
            </label>
            <input
              id="password"
              type="password"
              className="input"
              placeholder="••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={loading || !username || !password}
            style={{ width: '100%', marginTop: '0.5rem', padding: '0.7rem' }}
          >
            {loading ? (
              <>
                <span className="spinner" style={{ width: 16, height: 16 }} />
                Signing in…
              </>
            ) : (
              'Sign In'
            )}
          </button>

          <p style={{
            textAlign: 'center',
            fontSize: '0.75rem',
            color: 'var(--text-tertiary)',
            marginTop: '0.5rem',
          }}>
            Default credentials: admin / admin
          </p>
        </form>
      </motion.div>
    </div>
  )
}
