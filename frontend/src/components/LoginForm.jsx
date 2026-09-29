import { useState } from 'react'
import { Lock, User, Sparkles, Mail, UserPlus, LogIn, ArrowRight } from 'lucide-react'
import { motion, AnimatePresence } from 'framer-motion'
import { login, signup } from '../api/client'

export default function LoginForm({ onLogin }) {
  const [mode, setMode] = useState('login') // 'login' | 'signup'

  // Login form state
  const [loginUsername, setLoginUsername] = useState('')
  const [loginPassword, setLoginPassword] = useState('')

  // Signup form state
  const [signupUsername, setSignupUsername] = useState('')
  const [signupFirstName, setSignupFirstName] = useState('')
  const [signupLastName, setSignupLastName] = useState('')
  const [signupEmail, setSignupEmail] = useState('')
  const [signupPassword, setSignupPassword] = useState('')
  const [signupConfirmPassword, setSignupConfirmPassword] = useState('')

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleLoginSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      await login(loginUsername, loginPassword)
      onLogin()
    } catch (err) {
      setError(err.message || 'Invalid username or password')
    } finally {
      setLoading(false)
    }
  }

  const handleSignupSubmit = async (e) => {
    e.preventDefault()
    setError('')

    if (signupPassword !== signupConfirmPassword) {
      setError('Passwords do not match')
      return
    }

    if (signupPassword.length < 6) {
      setError('Password must be at least 6 characters')
      return
    }

    setLoading(true)

    try {
      await signup({
        username: signupUsername.trim(),
        password: signupPassword,
        firstName: signupFirstName.trim(),
        lastName: signupLastName.trim(),
        email: signupEmail.trim(),
      })
      onLogin()
    } catch (err) {
      setError(err.message || 'Registration failed')
    } finally {
      setLoading(false)
    }
  }

  const switchMode = (newMode) => {
    setError('')
    setMode(newMode)
  }

  return (
    <div className="login-overlay">
      {/* Background decorative gradients */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(ellipse 80% 60% at 50% 30%, hsla(217, 91%, 55%, 0.12), transparent), radial-gradient(ellipse 60% 80% at 30% 80%, hsla(280, 67%, 55%, 0.08), transparent)',
          pointerEvents: 'none',
        }}
      />

      <motion.div
        className="login-card"
        style={{ maxWidth: mode === 'signup' ? 460 : 400 }}
        initial={{ opacity: 0, y: 20, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.35, ease: 'easeOut' }}
      >
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: '1rem' }}>
          <div
            className="brand-icon"
            style={{
              width: 52,
              height: 52,
              background: 'linear-gradient(135deg, hsl(217, 91%, 55%), hsl(280, 67%, 55%))',
              borderRadius: 16,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 8px 24px hsla(217, 91%, 55%, 0.3)',
            }}
          >
            <Sparkles size={26} color="white" />
          </div>
        </div>

        <h2>Alfresco SemSearch</h2>
        <p className="login-subtitle">Enterprise Semantic Search & Collaboration</p>

        {/* Mode Toggle Buttons */}
        <div
          style={{
            display: 'flex',
            background: 'var(--bg-surface)',
            borderRadius: 'var(--radius-md)',
            padding: 4,
            marginBottom: '1.25rem',
            border: '1px solid var(--border-color)',
          }}
        >
          <button
            type="button"
            className="btn btn-ghost"
            style={{
              flex: 1,
              borderRadius: 'var(--radius-sm)',
              padding: '0.45rem',
              fontSize: '0.85rem',
              fontWeight: mode === 'login' ? 600 : 400,
              background: mode === 'login' ? 'var(--primary-600)' : 'transparent',
              color: mode === 'login' ? '#ffffff' : 'var(--text-secondary)',
              transition: 'all 0.2s ease',
            }}
            onClick={() => switchMode('login')}
          >
            <LogIn size={15} style={{ marginRight: 6, verticalAlign: -2 }} />
            Sign In
          </button>
          <button
            type="button"
            className="btn btn-ghost"
            style={{
              flex: 1,
              borderRadius: 'var(--radius-sm)',
              padding: '0.45rem',
              fontSize: '0.85rem',
              fontWeight: mode === 'signup' ? 600 : 400,
              background: mode === 'signup' ? 'var(--primary-600)' : 'transparent',
              color: mode === 'signup' ? '#ffffff' : 'var(--text-secondary)',
              transition: 'all 0.2s ease',
            }}
            onClick={() => switchMode('signup')}
          >
            <UserPlus size={15} style={{ marginRight: 6, verticalAlign: -2 }} />
            Sign Up
          </button>
        </div>

        {error && (
          <motion.div
            className="login-error"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            style={{ marginBottom: '1rem' }}
          >
            {error}
          </motion.div>
        )}

        <AnimatePresence mode="wait">
          {mode === 'login' ? (
            <motion.form
              key="login-form"
              className="login-form"
              onSubmit={handleLoginSubmit}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              <div className="form-group">
                <label htmlFor="login-username">
                  <User size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 6 }} />
                  Username
                </label>
                <input
                  id="login-username"
                  type="text"
                  className="input"
                  placeholder="Enter your Alfresco username"
                  value={loginUsername}
                  onChange={(e) => setLoginUsername(e.target.value)}
                  autoFocus
                  required
                />
              </div>

              <div className="form-group">
                <label htmlFor="login-password">
                  <Lock size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 6 }} />
                  Password
                </label>
                <input
                  id="login-password"
                  type="password"
                  className="input"
                  placeholder="••••••••"
                  value={loginPassword}
                  onChange={(e) => setLoginPassword(e.target.value)}
                  required
                />
              </div>

              <button
                type="submit"
                className="btn btn-primary"
                disabled={loading || !loginUsername || !loginPassword}
                style={{ width: '100%', marginTop: '0.5rem', padding: '0.75rem', fontWeight: 600 }}
              >
                {loading ? (
                  <>
                    <span className="spinner" style={{ width: 16, height: 16 }} />
                    Authenticating…
                  </>
                ) : (
                  <>
                    Sign In
                    <ArrowRight size={16} style={{ marginLeft: 6 }} />
                  </>
                )}
              </button>

              <p
                style={{
                  textAlign: 'center',
                  fontSize: '0.8rem',
                  color: 'var(--text-secondary)',
                  marginTop: '0.5rem',
                }}
              >
                Don't have an account?{' '}
                <button
                  type="button"
                  onClick={() => switchMode('signup')}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: 'var(--primary-400)',
                    cursor: 'pointer',
                    textDecoration: 'underline',
                    padding: 0,
                    font: 'inherit',
                  }}
                >
                  Create one here
                </button>
              </p>
            </motion.form>
          ) : (
            <motion.form
              key="signup-form"
              className="login-form"
              onSubmit={handleSignupSubmit}
              initial={{ opacity: 0, x: 10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -10 }}
              transition={{ duration: 0.2 }}
            >
              <div className="form-group">
                <label htmlFor="signup-username">
                  <User size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 6 }} />
                  Username
                </label>
                <input
                  id="signup-username"
                  type="text"
                  className="input"
                  placeholder="e.g. abc or john_doe"
                  value={signupUsername}
                  onChange={(e) => setSignupUsername(e.target.value)}
                  autoFocus
                  required
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
                <div className="form-group">
                  <label htmlFor="signup-first">First Name</label>
                  <input
                    id="signup-first"
                    type="text"
                    className="input"
                    placeholder="First"
                    value={signupFirstName}
                    onChange={(e) => setSignupFirstName(e.target.value)}
                    required
                  />
                </div>
                <div className="form-group">
                  <label htmlFor="signup-last">Last Name</label>
                  <input
                    id="signup-last"
                    type="text"
                    className="input"
                    placeholder="Last"
                    value={signupLastName}
                    onChange={(e) => setSignupLastName(e.target.value)}
                  />
                </div>
              </div>

              <div className="form-group">
                <label htmlFor="signup-email">
                  <Mail size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 6 }} />
                  Email
                </label>
                <input
                  id="signup-email"
                  type="email"
                  className="input"
                  placeholder="name@example.com"
                  value={signupEmail}
                  onChange={(e) => setSignupEmail(e.target.value)}
                  required
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
                <div className="form-group">
                  <label htmlFor="signup-password">
                    <Lock size={14} style={{ display: 'inline', verticalAlign: '-2px', marginRight: 6 }} />
                    Password
                  </label>
                  <input
                    id="signup-password"
                    type="password"
                    className="input"
                    placeholder="Min 6 chars"
                    value={signupPassword}
                    onChange={(e) => setSignupPassword(e.target.value)}
                    required
                  />
                </div>
                <div className="form-group">
                  <label htmlFor="signup-confirm">Confirm</label>
                  <input
                    id="signup-confirm"
                    type="password"
                    className="input"
                    placeholder="Re-enter"
                    value={signupConfirmPassword}
                    onChange={(e) => setSignupConfirmPassword(e.target.value)}
                    required
                  />
                </div>
              </div>

              <button
                type="submit"
                className="btn btn-primary"
                disabled={
                  loading ||
                  !signupUsername ||
                  !signupEmail ||
                  !signupPassword ||
                  signupPassword !== signupConfirmPassword
                }
                style={{ width: '100%', marginTop: '0.5rem', padding: '0.75rem', fontWeight: 600 }}
              >
                {loading ? (
                  <>
                    <span className="spinner" style={{ width: 16, height: 16 }} />
                    Creating Account…
                  </>
                ) : (
                  <>
                    Create Account
                    <ArrowRight size={16} style={{ marginLeft: 6 }} />
                  </>
                )}
              </button>

              <p
                style={{
                  textAlign: 'center',
                  fontSize: '0.8rem',
                  color: 'var(--text-secondary)',
                  marginTop: '0.5rem',
                }}
              >
                Already have an account?{' '}
                <button
                  type="button"
                  onClick={() => switchMode('login')}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: 'var(--primary-400)',
                    cursor: 'pointer',
                    textDecoration: 'underline',
                    padding: 0,
                    font: 'inherit',
                  }}
                >
                  Sign in here
                </button>
              </p>
            </motion.form>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
  )
}
