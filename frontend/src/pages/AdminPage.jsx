import { useState, useEffect, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  RotateCcw, Play, Shield, FileSearch, Trash2,
  Clock, Loader, CheckCircle, XCircle, AlertTriangle, RefreshCw
} from 'lucide-react'
import {
  getJobStats, getRecentJobs, getFailedJobs, requeueFailedJobs,
  triggerContentReconciliation, triggerOrphanReconciliation,
  triggerPermissionReconciliation
} from '../api/client'

export default function AdminPage() {
  const [stats, setStats] = useState({ PENDING: 0, RUNNING: 0, DONE: 0, FAILED: 0 })
  const [recentJobs, setRecentJobs] = useState([])
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState(null)
  const [toast, setToast] = useState(null)

  const showToast = (message, type = 'info') => {
    setToast({ message, type })
    setTimeout(() => setToast(null), 4000)
  }

  const loadData = useCallback(async () => {
    try {
      const [statsData, jobsData] = await Promise.all([
        getJobStats(),
        getRecentJobs(30),
      ])
      setStats(statsData)
      setRecentJobs(jobsData)
    } catch (err) {
      console.error('Failed to load admin data:', err)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadData()
    // Auto-refresh every 10 seconds
    const interval = setInterval(loadData, 10000)
    return () => clearInterval(interval)
  }, [loadData])

  const handleAction = async (actionName, actionFn) => {
    setActionLoading(actionName)
    try {
      const result = await actionFn()
      showToast(
        `${actionName} completed. ${result.requeuedCount != null
          ? `${result.requeuedCount} requeued`
          : result.enqueuedCount != null
            ? `${result.enqueuedCount} enqueued`
            : 'Success'
        }`,
        'success'
      )
      await loadData() // refresh stats
    } catch (err) {
      showToast(`${actionName} failed: ${err.message}`, 'error')
    } finally {
      setActionLoading(null)
    }
  }

  const formatDate = (dateStr) => {
    if (!dateStr) return '—'
    const d = new Date(dateStr)
    return d.toLocaleString()
  }

  const statusIcon = (status) => {
    switch (status) {
      case 'PENDING': return <Clock size={14} />
      case 'RUNNING': return <Loader size={14} style={{ animation: 'spin 1s linear infinite' }} />
      case 'DONE': return <CheckCircle size={14} />
      case 'FAILED': return <XCircle size={14} />
      default: return null
    }
  }

  const statCards = [
    { key: 'PENDING', label: 'Pending', className: 'pending', icon: Clock },
    { key: 'RUNNING', label: 'Running', className: 'running', icon: Loader },
    { key: 'DONE', label: 'Completed', className: 'completed', icon: CheckCircle },
    { key: 'FAILED', label: 'Failed', className: 'failed', icon: XCircle },
  ]

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <h2>Admin Dashboard</h2>
        <p>Monitor ingestion pipeline, manage jobs, and trigger reconciliation</p>
      </div>

      {loading ? (
        <div className="loading-center">
          <div className="spinner" style={{ width: 32, height: 32 }} />
        </div>
      ) : (
        <>
          {/* Stats Grid */}
          <div className="stats-grid">
            {statCards.map(({ key, label, className, icon: Icon }, idx) => (
              <motion.div
                key={key}
                className={`stat-card ${className}`}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: idx * 0.08 }}
              >
                <div style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: '0.5rem',
                  marginBottom: '0.5rem',
                }}>
                  <Icon size={20} style={{ opacity: 0.7 }} />
                </div>
                <div className="stat-value">
                  {stats[key] ?? 0}
                </div>
                <div className="stat-label">{label}</div>
              </motion.div>
            ))}
          </div>

          {/* Action Buttons */}
          <div className="card" style={{ marginBottom: 'var(--space-xl)' }}>
            <h3 style={{ fontSize: '0.95rem', fontWeight: 700, marginBottom: 'var(--space-md)' }}>
              Actions
            </h3>
            <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
              <button
                className="btn btn-secondary"
                onClick={() => handleAction('Requeue Failed', requeueFailedJobs)}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'Requeue Failed' ? <span className="spinner" style={{ width: 14, height: 14 }} /> : <RotateCcw size={14} />}
                Requeue Failed Jobs
              </button>
              <button
                className="btn btn-secondary"
                onClick={() => handleAction('Content Reconciliation', triggerContentReconciliation)}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'Content Reconciliation' ? <span className="spinner" style={{ width: 14, height: 14 }} /> : <FileSearch size={14} />}
                Content Reconciliation
              </button>
              <button
                className="btn btn-secondary"
                onClick={() => handleAction('Orphan Reconciliation', triggerOrphanReconciliation)}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'Orphan Reconciliation' ? <span className="spinner" style={{ width: 14, height: 14 }} /> : <Trash2 size={14} />}
                Orphan Cleanup
              </button>
              <button
                className="btn btn-secondary"
                onClick={() => handleAction('Permission Drift', triggerPermissionReconciliation)}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'Permission Drift' ? <span className="spinner" style={{ width: 14, height: 14 }} /> : <Shield size={14} />}
                Permission Drift
              </button>
              <button
                className="btn btn-ghost"
                onClick={loadData}
                title="Refresh"
              >
                <RefreshCw size={14} /> Refresh
              </button>
            </div>
          </div>

          {/* Recent Jobs Table */}
          <div className="card">
            <div style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 'var(--space-md)',
            }}>
              <h3 style={{ fontSize: '0.95rem', fontWeight: 700 }}>
                Recent Activity
              </h3>
              <span style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)' }}>
                Auto-refreshing every 10s
              </span>
            </div>

            {recentJobs.length === 0 ? (
              <div className="empty-state">
                <Clock size={36} className="empty-icon" />
                <h3>No jobs yet</h3>
                <p>Upload a document to trigger the ingestion pipeline.</p>
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table className="jobs-table">
                  <thead>
                    <tr>
                      <th>ID</th>
                      <th>Node ID</th>
                      <th>Action</th>
                      <th>Status</th>
                      <th>Attempts</th>
                      <th>Last Error</th>
                      <th>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    <AnimatePresence>
                      {recentJobs.map((job, idx) => (
                        <motion.tr
                          key={job.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          transition={{ delay: idx * 0.02 }}
                        >
                          <td style={{ fontFamily: 'var(--font-mono)', fontSize: '0.78rem' }}>
                            {job.id}
                          </td>
                          <td style={{ fontFamily: 'var(--font-mono)', fontSize: '0.78rem' }}>
                            {job.nodeId?.substring(0, 8)}…
                          </td>
                          <td>
                            <span style={{
                              padding: '0.1rem 0.4rem',
                              borderRadius: 'var(--radius-sm)',
                              fontSize: '0.72rem',
                              fontWeight: 600,
                              background: job.action === 'DELETE' ? 'var(--danger-bg)' : 'var(--info-bg)',
                              color: job.action === 'DELETE' ? 'var(--danger)' : 'var(--info)',
                            }}>
                              {job.action}
                            </span>
                          </td>
                          <td>
                            <span className={`status-badge ${job.status}`}>
                              {statusIcon(job.status)}
                              <span style={{ marginLeft: '0.25rem' }}>{job.status}</span>
                            </span>
                          </td>
                          <td>{job.attempts}</td>
                          <td style={{
                            maxWidth: 200,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            fontSize: '0.78rem',
                            color: job.lastError ? 'var(--danger)' : 'var(--text-tertiary)',
                          }}>
                            {job.lastError || '—'}
                          </td>
                          <td style={{ fontSize: '0.78rem', whiteSpace: 'nowrap' }}>
                            {formatDate(job.updatedAt)}
                          </td>
                        </motion.tr>
                      ))}
                    </AnimatePresence>
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {/* Toast */}
      <AnimatePresence>
        {toast && (
          <motion.div
            className={`toast ${toast.type}`}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
          >
            {toast.type === 'success' ? <CheckCircle size={16} style={{ color: 'var(--success)', flexShrink: 0 }} /> :
             toast.type === 'error' ? <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} /> :
             null}
            {toast.message}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
