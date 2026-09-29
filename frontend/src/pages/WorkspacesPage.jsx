import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FolderPlus,
  FolderOpen,
  Lock,
  Globe,
  Users,
  Shield,
  Clock,
  Trash2,
  Key,
  Activity,
  Send,
  CheckCircle2,
  XCircle,
  AlertCircle,
  X,
  ExternalLink,
  ChevronRight,
  UserCheck,
  Search,
  RefreshCw,
} from 'lucide-react'
import {
  listWorkspaces,
  createWorkspace,
  getWorkspaceDetail,
  deleteWorkspace,
  requestWorkspaceAccess,
  getPendingAccessRequests,
  reviewAccessRequest,
  revokeWorkspacePermission,
  getAuthUser,
} from '../api/client'

export default function WorkspacesPage() {
  const navigate = useNavigate()
  const currentUser = getAuthUser()
  const currentUsername = currentUser?.username || ''
  const isAdmin = !!currentUser?.isAdmin

  // Workspace lists & state
  const [workspaces, setWorkspaces] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [activeTab, setActiveTab] = useState('all') // 'all', 'mine', 'public', 'private', 'requests'
  const [searchQuery, setSearchQuery] = useState('')

  // Modals & Drawers
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [selectedFolder, setSelectedFolder] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [drawerTab, setDrawerTab] = useState('permissions') // 'permissions', 'activities'
  const [requestModalFolder, setRequestModalFolder] = useState(null)

  // Pending requests state
  const [pendingRequests, setPendingRequests] = useState([])
  const [pendingLoading, setPendingLoading] = useState(false)

  // Form states
  const [createForm, setCreateForm] = useState({
    name: '',
    description: '',
    visibility: 'PUBLIC',
  })
  const [createSubmitting, setCreateSubmitting] = useState(false)
  const [createError, setCreateError] = useState(null)

  const [requestForm, setRequestForm] = useState({
    reason: '',
    requestedRole: 'COLLABORATOR',
  })
  const [requestSubmitting, setRequestSubmitting] = useState(false)
  const [requestError, setRequestError] = useState(null)
  const [requestSuccess, setRequestSuccess] = useState(null)

  const [reviewingId, setReviewingId] = useState(null)
  const [reviewComment, setReviewComment] = useState('')

  // Load workspaces
  const loadWorkspaces = useCallback(async () => {
    try {
      setLoading(true)
      setError(null)
      const data = await listWorkspaces()
      setWorkspaces(data || [])
    } catch (err) {
      setError(err.message || 'Failed to load workspaces')
    } finally {
      setLoading(false)
    }
  }, [])

  // Load pending access requests for folders owned by current user
  const loadPendingRequests = useCallback(async () => {
    try {
      setPendingLoading(true)
      const requests = await getPendingAccessRequests()
      setPendingRequests(requests || [])
    } catch (err) {
      console.error('Failed to load pending requests', err)
    } finally {
      setPendingLoading(false)
    }
  }, [])

  useEffect(() => {
    loadWorkspaces()
    loadPendingRequests()
  }, [loadWorkspaces, loadPendingRequests])

  // Open detail drawer
  const handleOpenDetail = async (folder) => {
    try {
      setDetailLoading(true)
      setSelectedFolder(folder)
      const detail = await getWorkspaceDetail(folder.id)
      setSelectedFolder(detail)
    } catch (err) {
      alert(err.message || 'Failed to load folder details')
    } finally {
      setDetailLoading(false)
    }
  }

  // Handle Create Workspace
  const handleCreateSubmit = async (e) => {
    e.preventDefault()
    if (!createForm.name.trim()) {
      setCreateError('Folder name is required')
      return
    }

    try {
      setCreateSubmitting(true)
      setCreateError(null)
      await createWorkspace(createForm)
      setShowCreateModal(false)
      setCreateForm({ name: '', description: '', visibility: 'PUBLIC' })
      await loadWorkspaces()
    } catch (err) {
      setCreateError(err.message || 'Failed to create workspace folder')
    } finally {
      setCreateSubmitting(false)
    }
  }

  // Handle Delete Workspace
  const handleDeleteFolder = async (folder, e) => {
    e?.stopPropagation()
    if (!window.confirm(`Are you sure you want to delete workspace folder "${folder.name}"? This action moves the folder to the trashcan in Alfresco.`)) {
      return
    }

    try {
      await deleteWorkspace(folder.id)
      if (selectedFolder?.id === folder.id) {
        setSelectedFolder(null)
      }
      await loadWorkspaces()
      await loadPendingRequests()
    } catch (err) {
      alert(err.message || 'Failed to delete workspace')
    }
  }

  // Handle Request Access
  const handleRequestAccessSubmit = async (e) => {
    e.preventDefault()
    if (!requestModalFolder) return

    try {
      setRequestSubmitting(true)
      setRequestError(null)
      await requestWorkspaceAccess(requestModalFolder.id, requestForm)
      setRequestSuccess('Access request submitted successfully! The folder owner will review your request.')
      setTimeout(() => {
        setRequestModalFolder(null)
        setRequestSuccess(null)
        setRequestForm({ reason: '', requestedRole: 'COLLABORATOR' })
      }, 1500)
    } catch (err) {
      setRequestError(err.message || 'Failed to submit request')
    } finally {
      setRequestSubmitting(false)
    }
  }

  // Handle Review Request (Approve/Deny)
  const handleReview = async (requestId, action) => {
    try {
      setReviewingId(requestId)
      await reviewAccessRequest(requestId, {
        action,
        reviewComment: reviewComment.trim(),
      })
      setReviewComment('')
      await loadPendingRequests()
      await loadWorkspaces()
      if (selectedFolder) {
        const detail = await getWorkspaceDetail(selectedFolder.id)
        setSelectedFolder(detail)
      }
    } catch (err) {
      alert(err.message || `Failed to ${action.toLowerCase()} request`)
    } finally {
      setReviewingId(null)
    }
  }

  // Handle Revoke Permission
  const handleRevoke = async (targetUsername) => {
    if (!selectedFolder) return
    if (!window.confirm(`Revoke write permissions for user @${targetUsername}?`)) return

    try {
      await revokeWorkspacePermission(selectedFolder.id, targetUsername)
      const updated = await getWorkspaceDetail(selectedFolder.id)
      setSelectedFolder(updated)
      await loadWorkspaces()
    } catch (err) {
      alert(err.message || 'Failed to revoke permission')
    }
  }

  // Filter workspaces based on active tab and search
  const filteredWorkspaces = workspaces.filter((f) => {
    const matchesSearch =
      f.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (f.description && f.description.toLowerCase().includes(searchQuery.toLowerCase())) ||
      f.ownerUsername.toLowerCase().includes(searchQuery.toLowerCase())

    if (!matchesSearch) return false

    if (activeTab === 'mine') return f.ownerUsername === currentUsername
    if (activeTab === 'public') return f.visibility === 'PUBLIC'
    if (activeTab === 'private') return f.visibility === 'PRIVATE'
    return true
  })

  const myCount = workspaces.filter((f) => f.ownerUsername === currentUsername).length
  const publicCount = workspaces.filter((f) => f.visibility === 'PUBLIC').length
  const privateCount = workspaces.filter((f) => f.visibility === 'PRIVATE').length
  const pendingCount = pendingRequests.length

  return (
    <div className="workspaces-page" style={{ padding: '2rem', maxWidth: '1400px', margin: '0 auto' }}>
      {/* Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: '2rem',
          flexWrap: 'wrap',
          gap: '1rem',
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.25rem' }}>
            <h1 style={{ fontSize: '1.75rem', fontWeight: 700, color: 'var(--text-primary)' }}>
              Workspaces & Folders
            </h1>
            <span
              style={{
                fontSize: '0.75rem',
                padding: '0.2rem 0.6rem',
                borderRadius: '999px',
                background: 'var(--primary-glow)',
                color: 'var(--primary-400)',
                border: '1px solid var(--border-default)',
                fontWeight: 600,
              }}
            >
              Alfresco ACL Sync
            </span>
          </div>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Public and private collaborative workspaces with granular role governance and live audit trails.
          </p>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
          <button
            onClick={() => loadWorkspaces()}
            className="btn btn-ghost"
            title="Refresh workspaces"
            style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}
          >
            <RefreshCw size={16} className={loading ? 'spin' : ''} />
            <span>Refresh</span>
          </button>

          <button
            onClick={() => setShowCreateModal(true)}
            className="btn btn-primary"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.5rem',
              boxShadow: '0 0 20px var(--primary-glow)',
            }}
          >
            <FolderPlus size={18} />
            <span>Create Workspace Folder</span>
          </button>
        </div>
      </div>

      {/* Tabs & Search Bar */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '1.5rem',
          flexWrap: 'wrap',
          gap: '1rem',
          borderBottom: '1px solid var(--border-subtle)',
          paddingBottom: '1rem',
        }}
      >
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button
            className={`btn ${activeTab === 'all' ? 'btn-primary' : 'btn-ghost'}`}
            style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
            onClick={() => setActiveTab('all')}
          >
            All Workspaces ({workspaces.length})
          </button>
          <button
            className={`btn ${activeTab === 'mine' ? 'btn-primary' : 'btn-ghost'}`}
            style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
            onClick={() => setActiveTab('mine')}
          >
            My Folders ({myCount})
          </button>
          <button
            className={`btn ${activeTab === 'public' ? 'btn-primary' : 'btn-ghost'}`}
            style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
            onClick={() => setActiveTab('public')}
          >
            Public ({publicCount})
          </button>
          <button
            className={`btn ${activeTab === 'private' ? 'btn-primary' : 'btn-ghost'}`}
            style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
            onClick={() => setActiveTab('private')}
          >
            Private ({privateCount})
          </button>
          <button
            className={`btn ${activeTab === 'requests' ? 'btn-primary' : 'btn-ghost'}`}
            style={{
              fontSize: '0.85rem',
              padding: '0.4rem 0.8rem',
              display: 'flex',
              alignItems: 'center',
              gap: '0.4rem',
              borderColor: pendingCount > 0 ? 'var(--warning)' : undefined,
            }}
            onClick={() => setActiveTab('requests')}
          >
            <Clock size={15} />
            <span>Pending Requests</span>
            {pendingCount > 0 && (
              <span
                style={{
                  background: 'var(--danger)',
                  color: '#fff',
                  borderRadius: '999px',
                  padding: '0.1rem 0.45rem',
                  fontSize: '0.7rem',
                  fontWeight: 700,
                  marginLeft: '0.2rem',
                }}
              >
                {pendingCount}
              </span>
            )}
          </button>
        </div>

        {activeTab !== 'requests' && (
          <div style={{ position: 'relative', width: '280px' }}>
            <Search
              size={16}
              style={{
                position: 'absolute',
                left: '0.75rem',
                top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-tertiary)',
              }}
            />
            <input
              type="text"
              className="input-field"
              placeholder="Search folders or owner..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              style={{
                paddingLeft: '2.2rem',
                fontSize: '0.85rem',
                background: 'var(--bg-surface)',
                width: '100%',
              }}
            />
          </div>
        )}
      </div>

      {/* Main Content Area */}
      {activeTab === 'requests' ? (
        /* Pending Requests View */
        <div>
          <div style={{ marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1.2rem', fontWeight: 600, color: 'var(--text-primary)' }}>
              Write Access Requests for Your Folders
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
              Users who have read-only access to your public folders can request collaborator permissions to add and edit documents.
            </p>
          </div>

          {pendingLoading ? (
            <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-tertiary)' }}>
              Loading pending access requests...
            </div>
          ) : pendingRequests.length === 0 ? (
            <div
              style={{
                padding: '3.5rem 2rem',
                textAlign: 'center',
                background: 'var(--bg-surface)',
                border: '1px dashed var(--border-default)',
                borderRadius: 'var(--radius-lg)',
              }}
            >
              <CheckCircle2 size={36} style={{ color: 'var(--success)', margin: '0 auto 0.75rem' }} />
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '0.25rem' }}>
                All caught up!
              </h3>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                There are no pending access requests for folders you own.
              </p>
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(400px, 1fr))', gap: '1.25rem' }}>
              {pendingRequests.map((req) => (
                <div
                  key={req.id}
                  style={{
                    background: 'var(--glass-bg)',
                    backdropFilter: 'blur(var(--glass-blur))',
                    border: '1px solid var(--border-default)',
                    borderRadius: 'var(--radius-md)',
                    padding: '1.25rem',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'space-between',
                  }}
                >
                  <div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                        <div className="avatar" style={{ width: 34, height: 34, fontSize: '0.85rem' }}>
                          {req.requesterUsername.charAt(0).toUpperCase()}
                        </div>
                        <div>
                          <div style={{ fontWeight: 600, fontSize: '0.9rem', color: 'var(--text-primary)' }}>
                            @{req.requesterUsername}
                          </div>
                          <div style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)' }}>
                            Target: <span style={{ color: 'var(--primary-400)', fontWeight: 500 }}>{req.folderName}</span>
                          </div>
                        </div>
                      </div>
                      <span
                        style={{
                          fontSize: '0.7rem',
                          padding: '0.2rem 0.5rem',
                          borderRadius: '999px',
                          background: 'var(--warning-bg)',
                          color: 'var(--warning)',
                          fontWeight: 600,
                          border: '1px solid hsla(38, 92%, 50%, 0.3)',
                        }}
                      >
                        PENDING
                      </span>
                    </div>

                    <div
                      style={{
                        background: 'var(--bg-base)',
                        padding: '0.75rem',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: '0.85rem',
                        color: 'var(--text-secondary)',
                        marginBottom: '1rem',
                        border: '1px solid var(--border-subtle)',
                      }}
                    >
                      <div style={{ fontSize: '0.7rem', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '0.25rem' }}>
                        Reason for request:
                      </div>
                      {req.reason || 'No specific reason provided.'}
                    </div>

                    <div style={{ marginBottom: '1rem' }}>
                      <label style={{ display: 'block', fontSize: '0.75rem', color: 'var(--text-tertiary)', marginBottom: '0.3rem' }}>
                        Review Comment (optional):
                      </label>
                      <input
                        type="text"
                        className="input-field"
                        placeholder="e.g. Approved for engineering sprint"
                        value={reviewingId === req.id ? reviewComment : ''}
                        onChange={(e) => {
                          setReviewingId(req.id)
                          setReviewComment(e.target.value)
                        }}
                        style={{ fontSize: '0.8rem', padding: '0.4rem 0.6rem', width: '100%' }}
                      />
                    </div>
                  </div>

                  <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end', borderTop: '1px solid var(--border-subtle)', paddingTop: '0.75rem' }}>
                    <button
                      className="btn btn-ghost"
                      style={{ color: 'var(--danger)', fontSize: '0.8rem', padding: '0.35rem 0.75rem' }}
                      onClick={() => handleReview(req.id, 'DENY')}
                      disabled={reviewingId === req.id}
                    >
                      <XCircle size={15} style={{ marginRight: '0.3rem' }} />
                      Deny
                    </button>
                    <button
                      className="btn btn-primary"
                      style={{
                        background: 'var(--success)',
                        borderColor: 'var(--success)',
                        fontSize: '0.8rem',
                        padding: '0.35rem 0.85rem',
                      }}
                      onClick={() => handleReview(req.id, 'APPROVE')}
                      disabled={reviewingId === req.id}
                    >
                      <CheckCircle2 size={15} style={{ marginRight: '0.3rem' }} />
                      Approve & Grant Collaborator
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : (
        /* Workspaces Grid */
        <div>
          {loading ? (
            <div style={{ padding: '4rem', textAlign: 'center', color: 'var(--text-tertiary)' }}>
              Loading workspace folders...
            </div>
          ) : error ? (
            <div
              style={{
                padding: '1.5rem',
                background: 'var(--danger-bg)',
                border: '1px solid var(--danger)',
                borderRadius: 'var(--radius-md)',
                color: 'var(--danger)',
                display: 'flex',
                alignItems: 'center',
                gap: '0.75rem',
              }}
            >
              <AlertCircle size={20} />
              <span>{error}</span>
            </div>
          ) : filteredWorkspaces.length === 0 ? (
            <div
              style={{
                padding: '4rem 2rem',
                textAlign: 'center',
                background: 'var(--bg-surface)',
                border: '1px dashed var(--border-default)',
                borderRadius: 'var(--radius-lg)',
              }}
            >
              <FolderOpen size={40} style={{ color: 'var(--text-tertiary)', margin: '0 auto 1rem' }} />
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '0.35rem' }}>
                No workspace folders found
              </h3>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: '1.25rem' }}>
                {searchQuery
                  ? 'No workspaces match your search criteria.'
                  : activeTab === 'mine'
                  ? 'You have not created any workspace folders yet.'
                  : 'Get started by creating your first public or private workspace folder.'}
              </p>
              <button onClick={() => setShowCreateModal(true)} className="btn btn-primary" style={{ margin: '0 auto' }}>
                <FolderPlus size={16} style={{ marginRight: '0.4rem' }} />
                Create Workspace Folder
              </button>
            </div>
          ) : (
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))',
                gap: '1.25rem',
              }}
            >
              {filteredWorkspaces.map((folder) => {
                const isOwner = folder.isOwner || folder.ownerUsername === currentUsername
                const canDelete = isOwner || isAdmin
                const isPublic = folder.visibility === 'PUBLIC'
                const canRequestAccess = isPublic && !isOwner && folder.userRole === 'CONSUMER'

                return (
                  <div
                    key={folder.id}
                    onClick={() => handleOpenDetail(folder)}
                    style={{
                      background: 'var(--glass-bg)',
                      backdropFilter: 'blur(var(--glass-blur))',
                      border: '1px solid var(--border-default)',
                      borderRadius: 'var(--radius-md)',
                      padding: '1.25rem',
                      display: 'flex',
                      flexDirection: 'column',
                      justifyContent: 'space-between',
                      cursor: 'pointer',
                      transition: 'transform var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast)',
                    }}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.transform = 'translateY(-2px)'
                      e.currentTarget.style.borderColor = 'var(--primary-400)'
                      e.currentTarget.style.boxShadow = '0 8px 24px var(--primary-glow)'
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.transform = 'translateY(0)'
                      e.currentTarget.style.borderColor = 'var(--border-default)'
                      e.currentTarget.style.boxShadow = 'none'
                    }}
                  >
                    <div>
                      {/* Top Badges */}
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                          marginBottom: '0.75rem',
                        }}
                      >
                        <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center' }}>
                          {isPublic ? (
                            <span
                              style={{
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: '0.3rem',
                                fontSize: '0.7rem',
                                padding: '0.2rem 0.5rem',
                                borderRadius: '999px',
                                background: 'var(--success-bg)',
                                color: 'var(--success)',
                                border: '1px solid hsla(142, 71%, 45%, 0.3)',
                                fontWeight: 600,
                              }}
                            >
                              <Globe size={12} />
                              PUBLIC
                            </span>
                          ) : (
                            <span
                              style={{
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: '0.3rem',
                                fontSize: '0.7rem',
                                padding: '0.2rem 0.5rem',
                                borderRadius: '999px',
                                background: 'var(--warning-bg)',
                                color: 'var(--warning)',
                                border: '1px solid hsla(38, 92%, 50%, 0.3)',
                                fontWeight: 600,
                              }}
                            >
                              <Lock size={12} />
                              PRIVATE
                            </span>
                          )}

                          <span
                            style={{
                              fontSize: '0.7rem',
                              padding: '0.2rem 0.5rem',
                              borderRadius: '999px',
                              background: 'var(--bg-elevated)',
                              color: 'var(--text-secondary)',
                              border: '1px solid var(--border-subtle)',
                              fontWeight: 500,
                            }}
                          >
                            {folder.userRole || 'CONSUMER'}
                          </span>
                        </div>

                        {canDelete && (
                          <button
                            className="btn btn-ghost btn-icon"
                            style={{ color: 'var(--text-tertiary)', padding: '0.3rem' }}
                            title="Delete workspace"
                            onClick={(e) => handleDeleteFolder(folder, e)}
                          >
                            <Trash2 size={15} />
                          </button>
                        )}
                      </div>

                      {/* Folder Name & Desc */}
                      <h3
                        style={{
                          fontSize: '1.05rem',
                          fontWeight: 600,
                          color: 'var(--text-primary)',
                          marginBottom: '0.35rem',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {folder.name}
                      </h3>
                      <p
                        style={{
                          fontSize: '0.85rem',
                          color: 'var(--text-secondary)',
                          marginBottom: '1rem',
                          lineHeight: 1.4,
                          height: '2.8rem',
                          overflow: 'hidden',
                          display: '-webkit-box',
                          WebkitLineClamp: 2,
                          WebkitBoxOrient: 'vertical',
                        }}
                      >
                        {folder.description || 'No description provided.'}
                      </p>
                    </div>

                    {/* Footer Details */}
                    <div>
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                          fontSize: '0.75rem',
                          color: 'var(--text-tertiary)',
                          borderTop: '1px solid var(--border-subtle)',
                          paddingTop: '0.75rem',
                          marginBottom: '0.75rem',
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                          <div className="avatar" style={{ width: 20, height: 20, fontSize: '0.65rem' }}>
                            {folder.ownerUsername.charAt(0).toUpperCase()}
                          </div>
                          <span>
                            Owner: <strong style={{ color: 'var(--text-primary)' }}>@{folder.ownerUsername}</strong>
                            {isOwner && ' (You)'}
                          </span>
                        </div>
                        <div>{new Date(folder.createdAt).toLocaleDateString()}</div>
                      </div>

                      {/* Action buttons */}
                      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                        <button
                          className="btn btn-ghost"
                          style={{
                            flex: 1,
                            fontSize: '0.75rem',
                            padding: '0.35rem 0.5rem',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: '0.3rem',
                          }}
                          onClick={(e) => {
                            e.stopPropagation()
                            navigate(`/documents?folderId=${folder.alfrescoNodeId}`)
                          }}
                        >
                          <FolderOpen size={14} />
                          Browse
                        </button>

                        <button
                          className="btn btn-ghost"
                          style={{
                            flex: 1,
                            fontSize: '0.75rem',
                            padding: '0.35rem 0.5rem',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: '0.3rem',
                          }}
                          onClick={(e) => {
                            e.stopPropagation()
                            handleOpenDetail(folder)
                          }}
                        >
                          <Activity size={14} />
                          Details
                        </button>

                        {canRequestAccess && (
                          <button
                            className="btn btn-primary"
                            style={{
                              flex: '100%',
                              fontSize: '0.75rem',
                              padding: '0.35rem 0.5rem',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              gap: '0.3rem',
                              marginTop: '0.25rem',
                              background: 'var(--accent-500)',
                              borderColor: 'var(--accent-500)',
                            }}
                            onClick={(e) => {
                              e.stopPropagation()
                              setRequestModalFolder(folder)
                            }}
                          >
                            <Key size={14} />
                            Request Write Access
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}

      {/* ── CREATE WORKSPACE MODAL ────────────────────────────────────── */}
      {showCreateModal && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0, 0, 0, 0.75)',
            backdropFilter: 'blur(8px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '1rem',
          }}
          onClick={() => setShowCreateModal(false)}
        >
          <div
            style={{
              background: 'var(--bg-surface)',
              border: '1px solid var(--border-default)',
              borderRadius: 'var(--radius-lg)',
              maxWidth: '560px',
              width: '100%',
              padding: '2rem',
              boxShadow: 'var(--shadow-lg)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.25rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                <div className="brand-icon" style={{ width: 34, height: 34 }}>
                  <FolderPlus size={18} />
                </div>
                <h2 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Create Workspace Folder
                </h2>
              </div>
              <button className="btn btn-ghost btn-icon" onClick={() => setShowCreateModal(false)}>
                <X size={18} />
              </button>
            </div>

            {createError && (
              <div
                style={{
                  padding: '0.75rem',
                  background: 'var(--danger-bg)',
                  border: '1px solid var(--danger)',
                  borderRadius: 'var(--radius-sm)',
                  color: 'var(--danger)',
                  fontSize: '0.85rem',
                  marginBottom: '1rem',
                }}
              >
                {createError}
              </div>
            )}

            <form onSubmit={handleCreateSubmit}>
              <div style={{ marginBottom: '1rem' }}>
                <label style={{ display: 'block', fontSize: '0.85rem', fontWeight: 500, marginBottom: '0.4rem' }}>
                  Folder Name <span style={{ color: 'var(--danger)' }}>*</span>
                </label>
                <input
                  type="text"
                  className="input-field"
                  placeholder="e.g. Engineering Specs, Finance Q3, Team Hub"
                  value={createForm.name}
                  onChange={(e) => setCreateForm({ ...createForm, name: e.target.value })}
                  required
                  style={{ width: '100%' }}
                />
              </div>

              <div style={{ marginBottom: '1.25rem' }}>
                <label style={{ display: 'block', fontSize: '0.85rem', fontWeight: 500, marginBottom: '0.4rem' }}>
                  Description (Optional)
                </label>
                <textarea
                  className="input-field"
                  placeholder="Explain the purpose of this workspace..."
                  rows={2}
                  value={createForm.description}
                  onChange={(e) => setCreateForm({ ...createForm, description: e.target.value })}
                  style={{ width: '100%', resize: 'none' }}
                />
              </div>

              <div style={{ marginBottom: '1.5rem' }}>
                <label style={{ display: 'block', fontSize: '0.85rem', fontWeight: 500, marginBottom: '0.6rem' }}>
                  Visibility & Access Control
                </label>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
                  {/* Public Card */}
                  <div
                    onClick={() => setCreateForm({ ...createForm, visibility: 'PUBLIC' })}
                    style={{
                      padding: '1rem',
                      borderRadius: 'var(--radius-md)',
                      border: createForm.visibility === 'PUBLIC' ? '2px solid var(--success)' : '1px solid var(--border-default)',
                      background: createForm.visibility === 'PUBLIC' ? 'var(--success-bg)' : 'var(--bg-elevated)',
                      cursor: 'pointer',
                      transition: 'border-color var(--transition-fast)',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.4rem' }}>
                      <Globe size={18} style={{ color: 'var(--success)' }} />
                      <strong style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>Public</strong>
                    </div>
                    <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', lineHeight: 1.3 }}>
                      Discoverable by all registered users in <strong>Read-Only</strong> mode. Users can request write access.
                    </p>
                  </div>

                  {/* Private Card */}
                  <div
                    onClick={() => setCreateForm({ ...createForm, visibility: 'PRIVATE' })}
                    style={{
                      padding: '1rem',
                      borderRadius: 'var(--radius-md)',
                      border: createForm.visibility === 'PRIVATE' ? '2px solid var(--warning)' : '1px solid var(--border-default)',
                      background: createForm.visibility === 'PRIVATE' ? 'var(--warning-bg)' : 'var(--bg-elevated)',
                      cursor: 'pointer',
                      transition: 'border-color var(--transition-fast)',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.4rem' }}>
                      <Lock size={18} style={{ color: 'var(--warning)' }} />
                      <strong style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>Private</strong>
                    </div>
                    <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', lineHeight: 1.3 }}>
                      Isolated exclusively to you. No other user can see, search, or access this folder.
                    </p>
                  </div>
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
                <button type="button" className="btn btn-ghost" onClick={() => setShowCreateModal(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary" disabled={createSubmitting}>
                  {createSubmitting ? 'Creating in Alfresco...' : 'Create Folder'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── REQUEST ACCESS MODAL ──────────────────────────────────────── */}
      {requestModalFolder && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0, 0, 0, 0.75)',
            backdropFilter: 'blur(8px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '1rem',
          }}
          onClick={() => setRequestModalFolder(null)}
        >
          <div
            style={{
              background: 'var(--bg-surface)',
              border: '1px solid var(--border-default)',
              borderRadius: 'var(--radius-lg)',
              maxWidth: '480px',
              width: '100%',
              padding: '1.75rem',
              boxShadow: 'var(--shadow-lg)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.25rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <Key size={20} style={{ color: 'var(--accent-400)' }} />
                <h3 style={{ fontSize: '1.15rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Request Write Access
                </h3>
              </div>
              <button className="btn btn-ghost btn-icon" onClick={() => setRequestModalFolder(null)}>
                <X size={18} />
              </button>
            </div>

            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '1rem' }}>
              Request write and edit permissions on workspace <strong>"{requestModalFolder.name}"</strong> owned by{' '}
              <strong style={{ color: 'var(--primary-400)' }}>@{requestModalFolder.ownerUsername}</strong>.
            </p>

            {requestError && (
              <div
                style={{
                  padding: '0.75rem',
                  background: 'var(--danger-bg)',
                  border: '1px solid var(--danger)',
                  borderRadius: 'var(--radius-sm)',
                  color: 'var(--danger)',
                  fontSize: '0.85rem',
                  marginBottom: '1rem',
                }}
              >
                {requestError}
              </div>
            )}

            {requestSuccess && (
              <div
                style={{
                  padding: '0.75rem',
                  background: 'var(--success-bg)',
                  border: '1px solid var(--success)',
                  borderRadius: 'var(--radius-sm)',
                  color: 'var(--success)',
                  fontSize: '0.85rem',
                  marginBottom: '1rem',
                }}
              >
                {requestSuccess}
              </div>
            )}

            <form onSubmit={handleRequestAccessSubmit}>
              <div style={{ marginBottom: '1.25rem' }}>
                <label style={{ display: 'block', fontSize: '0.85rem', fontWeight: 500, marginBottom: '0.4rem' }}>
                  Reason for Write Access
                </label>
                <textarea
                  className="input-field"
                  placeholder="Explain why you need write/upload permissions for this folder..."
                  rows={3}
                  value={requestForm.reason}
                  onChange={(e) => setRequestForm({ ...requestForm, reason: e.target.value })}
                  style={{ width: '100%', resize: 'none' }}
                  required
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
                <button type="button" className="btn btn-ghost" onClick={() => setRequestModalFolder(null)}>
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary" disabled={requestSubmitting}>
                  {requestSubmitting ? 'Submitting...' : 'Submit Request'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── FOLDER DETAIL DRAWER ──────────────────────────────────────── */}
      {selectedFolder && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0, 0, 0, 0.6)',
            backdropFilter: 'blur(4px)',
            display: 'flex',
            justifyContent: 'flex-end',
            zIndex: 900,
          }}
          onClick={() => setSelectedFolder(null)}
        >
          <div
            style={{
              width: '540px',
              maxWidth: '90vw',
              height: '100%',
              background: 'var(--bg-surface)',
              borderLeft: '1px solid var(--border-default)',
              boxShadow: 'var(--shadow-lg)',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {/* Drawer Header */}
            <div
              style={{
                padding: '1.5rem',
                borderBottom: '1px solid var(--border-default)',
                background: 'var(--bg-elevated)',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  {selectedFolder.visibility === 'PUBLIC' ? (
                    <Globe size={18} style={{ color: 'var(--success)' }} />
                  ) : (
                    <Lock size={18} style={{ color: 'var(--warning)' }} />
                  )}
                  <h2 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                    {selectedFolder.name}
                  </h2>
                </div>
                <button className="btn btn-ghost btn-icon" onClick={() => setSelectedFolder(null)}>
                  <X size={18} />
                </button>
              </div>

              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>
                {selectedFolder.description || 'No description provided.'}
              </p>

              <div
                style={{
                  display: 'flex',
                  gap: '1rem',
                  fontSize: '0.75rem',
                  color: 'var(--text-tertiary)',
                  flexWrap: 'wrap',
                }}
              >
                <div>
                  Owner: <strong style={{ color: 'var(--text-primary)' }}>@{selectedFolder.ownerUsername}</strong>
                </div>
                <div>
                  Your Role: <strong style={{ color: 'var(--primary-400)' }}>{selectedFolder.userRole || 'CONSUMER'}</strong>
                </div>
                <div>
                  Node: <span style={{ fontFamily: 'var(--font-mono)' }}>{selectedFolder.alfrescoNodeId?.slice(0, 8)}...</span>
                </div>
              </div>
            </div>

            {/* Drawer Subtabs */}
            <div
              style={{
                display: 'flex',
                borderBottom: '1px solid var(--border-subtle)',
                background: 'var(--bg-base)',
              }}
            >
              <button
                className={`btn btn-ghost`}
                style={{
                  flex: 1,
                  borderRadius: 0,
                  borderBottom: drawerTab === 'permissions' ? '2px solid var(--primary-400)' : 'none',
                  color: drawerTab === 'permissions' ? 'var(--primary-400)' : 'var(--text-secondary)',
                  fontWeight: drawerTab === 'permissions' ? 600 : 400,
                  fontSize: '0.85rem',
                  padding: '0.75rem',
                }}
                onClick={() => setDrawerTab('permissions')}
              >
                <Users size={16} style={{ marginRight: '0.4rem' }} />
                Members & Permissions ({selectedFolder.permissions?.length || 0})
              </button>
              <button
                className={`btn btn-ghost`}
                style={{
                  flex: 1,
                  borderRadius: 0,
                  borderBottom: drawerTab === 'activities' ? '2px solid var(--primary-400)' : 'none',
                  color: drawerTab === 'activities' ? 'var(--primary-400)' : 'var(--text-secondary)',
                  fontWeight: drawerTab === 'activities' ? 600 : 400,
                  fontSize: '0.85rem',
                  padding: '0.75rem',
                }}
                onClick={() => setDrawerTab('activities')}
              >
                <Activity size={16} style={{ marginRight: '0.4rem' }} />
                Activity Audit Log ({selectedFolder.recentActivities?.length || 0})
              </button>
            </div>

            {/* Drawer Body */}
            <div style={{ flex: 1, overflowY: 'auto', padding: '1.25rem' }}>
              {detailLoading ? (
                <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--text-tertiary)' }}>
                  Loading details...
                </div>
              ) : drawerTab === 'permissions' ? (
                <div>
                  <div style={{ marginBottom: '1rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                    Permissions synchronized directly with Alfresco ACS repository ACLs:
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                    {selectedFolder.permissions?.map((perm) => {
                      const isPermOwner = perm.username === selectedFolder.ownerUsername
                      const canRevoke =
                        (selectedFolder.ownerUsername === currentUsername || isAdmin) &&
                        !isPermOwner

                      return (
                        <div
                          key={perm.id || perm.username}
                          style={{
                            background: 'var(--bg-elevated)',
                            padding: '0.75rem 1rem',
                            borderRadius: 'var(--radius-sm)',
                            border: '1px solid var(--border-subtle)',
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                            <div className="avatar" style={{ width: 28, height: 28, fontSize: '0.75rem' }}>
                              {perm.username.charAt(0).toUpperCase()}
                            </div>
                            <div>
                              <div style={{ fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-primary)' }}>
                                @{perm.username}
                                {isPermOwner && (
                                  <span style={{ fontSize: '0.7rem', color: 'var(--primary-400)', marginLeft: '0.4rem' }}>
                                    (Owner)
                                  </span>
                                )}
                              </div>
                              <div style={{ fontSize: '0.7rem', color: 'var(--text-tertiary)' }}>
                                Role: <span style={{ color: 'var(--text-secondary)', fontWeight: 500 }}>{perm.role}</span> | Source: {perm.source}
                              </div>
                            </div>
                          </div>

                          {canRevoke && (
                            <button
                              className="btn btn-ghost"
                              style={{ color: 'var(--danger)', fontSize: '0.75rem', padding: '0.2rem 0.6rem' }}
                              onClick={() => handleRevoke(perm.username)}
                            >
                              Revoke
                            </button>
                          )}
                        </div>
                      )
                    })}

                    {selectedFolder.visibility === 'PUBLIC' && (
                      <div
                        style={{
                          padding: '0.75rem 1rem',
                          background: 'var(--bg-base)',
                          borderRadius: 'var(--radius-sm)',
                          border: '1px dashed var(--border-default)',
                          fontSize: '0.8rem',
                          color: 'var(--text-secondary)',
                        }}
                      >
                        🌐 <strong>All Registered Users (GROUP_EVERYONE)</strong>: Read-Only (Consumer)
                      </div>
                    )}
                  </div>
                </div>
              ) : (
                /* Activities tab */
                <div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                    {selectedFolder.recentActivities?.map((act) => (
                      <div
                        key={act.id}
                        style={{
                          background: 'var(--bg-elevated)',
                          padding: '0.75rem 1rem',
                          borderRadius: 'var(--radius-sm)',
                          border: '1px solid var(--border-subtle)',
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.25rem' }}>
                          <span
                            style={{
                              fontSize: '0.75rem',
                              fontWeight: 600,
                              color:
                                act.activityType.includes('APPROVE')
                                  ? 'var(--success)'
                                  : act.activityType.includes('REVOKE') || act.activityType.includes('DENIED')
                                  ? 'var(--danger)'
                                  : 'var(--primary-400)',
                            }}
                          >
                            {act.activityType}
                          </span>
                          <span style={{ fontSize: '0.7rem', color: 'var(--text-tertiary)' }}>
                            {new Date(act.occurredAt).toLocaleString()}
                          </span>
                        </div>
                        <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                          Actor: <strong>@{act.username}</strong>
                        </div>
                        {act.details && (
                          <div
                            style={{
                              marginTop: '0.4rem',
                              padding: '0.4rem',
                              background: 'var(--bg-base)',
                              borderRadius: '4px',
                              fontSize: '0.75rem',
                              fontFamily: 'var(--font-mono)',
                              color: 'var(--text-tertiary)',
                              overflowX: 'auto',
                            }}
                          >
                            {act.details}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* Drawer Footer */}
            <div
              style={{
                padding: '1rem 1.5rem',
                borderTop: '1px solid var(--border-default)',
                background: 'var(--bg-elevated)',
                display: 'flex',
                justifyContent: 'space-between',
              }}
            >
              <button
                className="btn btn-primary"
                style={{ width: '100%', display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '0.4rem' }}
                onClick={() => {
                  navigate(`/documents?folderId=${selectedFolder.alfrescoNodeId}`)
                }}
              >
                <FolderOpen size={16} />
                Browse Documents in this Workspace
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
