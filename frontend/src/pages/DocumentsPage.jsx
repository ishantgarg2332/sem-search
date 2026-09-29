import { useState, useEffect, useCallback, useRef } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  FolderOpen, FileText, Upload, Trash2, RefreshCw, ChevronRight,
  Home, X, Download, UploadCloud, Info, Eye
} from 'lucide-react'
import {
  listDocuments, getDocument, uploadDocument, updateDocumentContent,
  deleteDocument, downloadDocument
} from '../api/client'

export default function DocumentsPage() {
  const [entries, setEntries] = useState([])
  const [pagination, setPagination] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  // Navigation state
  const [folderStack, setFolderStack] = useState([{ id: '-root-', name: 'Repository' }])
  const currentFolder = folderStack[folderStack.length - 1]

  // Detail panel
  const [selectedNode, setSelectedNode] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailData, setDetailData] = useState(null)

  // Upload
  const [showUpload, setShowUpload] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [dragover, setDragover] = useState(false)
  const fileInputRef = useRef(null)

  // Delete confirmation
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)

  // Toast
  const [toast, setToast] = useState(null)

  const showToast = (message, type = 'info') => {
    setToast({ message, type })
    setTimeout(() => setToast(null), 4000)
  }

  // ── Load folder contents ────────────────────────────────────────────────

  const loadFolder = useCallback(async (folderId, skipCount = 0) => {
    setLoading(true)
    setError(null)
    try {
      const data = await listDocuments(folderId, skipCount, 50)
      setEntries(data.entries || [])
      setPagination(data.pagination || null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadFolder(currentFolder.id)
  }, [currentFolder.id, loadFolder])

  // ── Navigation ──────────────────────────────────────────────────────────

  const navigateToFolder = (folder) => {
    setSelectedNode(null)
    setDetailData(null)
    setFolderStack(prev => [...prev, { id: folder.id, name: folder.name }])
  }

  const navigateToBreadcrumb = (index) => {
    setSelectedNode(null)
    setDetailData(null)
    setFolderStack(prev => prev.slice(0, index + 1))
  }

  // ── Detail panel ────────────────────────────────────────────────────────

  const showDetail = async (node) => {
    setSelectedNode(node)
    setDetailLoading(true)
    try {
      const full = await getDocument(node.id)
      setDetailData(full)
    } catch (err) {
      setDetailData(node) // fallback to list data
    } finally {
      setDetailLoading(false)
    }
  }

  // ── Upload ──────────────────────────────────────────────────────────────

  const handleFileUpload = async (files) => {
    if (!files || files.length === 0) return
    setUploading(true)
    try {
      for (const file of files) {
        await uploadDocument(file, currentFolder.id)
        showToast(`Uploaded "${file.name}" successfully`, 'success')
      }
      setShowUpload(false)
      loadFolder(currentFolder.id) // refresh
    } catch (err) {
      showToast(`Upload failed: ${err.message}`, 'error')
    } finally {
      setUploading(false)
    }
  }

  const handleDrop = (e) => {
    e.preventDefault()
    setDragover(false)
    handleFileUpload(e.dataTransfer.files)
  }

  // ── Update content ─────────────────────────────────────────────────────

  const updateInputRef = useRef(null)
  const [updateTarget, setUpdateTarget] = useState(null)

  const handleUpdateContent = async (file) => {
    if (!file || !updateTarget) return
    try {
      await updateDocumentContent(updateTarget.id, file)
      showToast(`Updated "${updateTarget.name}" with new content`, 'success')
      setUpdateTarget(null)
      loadFolder(currentFolder.id)
      if (selectedNode?.id === updateTarget.id) {
        showDetail(updateTarget)
      }
    } catch (err) {
      showToast(`Update failed: ${err.message}`, 'error')
    }
  }

  // ── Delete ─────────────────────────────────────────────────────────────

  const handleDelete = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await deleteDocument(deleteTarget.id)
      showToast(`Deleted "${deleteTarget.name}"`, 'success')
      setDeleteTarget(null)
      if (selectedNode?.id === deleteTarget.id) {
        setSelectedNode(null)
        setDetailData(null)
      }
      loadFolder(currentFolder.id)
    } catch (err) {
      showToast(`Delete failed: ${err.message}`, 'error')
    } finally {
      setDeleting(false)
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  const formatBytes = (bytes) => {
    if (!bytes || bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
  }

  const formatDate = (dateStr) => {
    if (!dateStr) return '—'
    return new Date(dateStr).toLocaleString()
  }

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <h2>Documents</h2>
        <p>Browse, upload, and manage Alfresco repository content</p>
      </div>

      {/* Breadcrumb */}
      <div className="breadcrumb">
        {folderStack.map((folder, idx) => (
          <span key={folder.id + idx} style={{ display: 'flex', alignItems: 'center', gap: '0.25rem' }}>
            {idx > 0 && <ChevronRight size={14} className="breadcrumb-separator" />}
            <span
              className={`breadcrumb-item ${idx === folderStack.length - 1 ? 'active' : ''}`}
              onClick={() => idx < folderStack.length - 1 && navigateToBreadcrumb(idx)}
              style={{ display: 'flex', alignItems: 'center', gap: '0.25rem' }}
            >
              {idx === 0 && <Home size={14} />}
              {folder.name}
            </span>
          </span>
        ))}
      </div>

      {/* Action bar */}
      <div className="action-bar">
        <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          {entries.length} item{entries.length !== 1 ? 's' : ''}
          {pagination?.totalItems > 0 && ` of ${pagination.totalItems}`}
        </span>
        <div className="actions">
          <button className="btn btn-primary" onClick={() => setShowUpload(true)}>
            <Upload size={16} /> Upload
          </button>
          <button className="btn btn-secondary" onClick={() => loadFolder(currentFolder.id)}>
            <RefreshCw size={16} /> Refresh
          </button>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div style={{
          padding: '1rem',
          background: 'var(--danger-bg)',
          border: '1px solid hsla(0, 72%, 51%, 0.3)',
          borderRadius: 'var(--radius-md)',
          color: 'var(--danger)',
          fontSize: '0.875rem',
          marginBottom: '1rem',
        }}>
          Failed to load: {error}
        </div>
      )}

      {/* Loading */}
      {loading && (
        <div className="loading-center">
          <div className="spinner" style={{ width: 32, height: 32 }} />
        </div>
      )}

      {/* Document grid */}
      {!loading && (
        <div className="doc-grid">
          <AnimatePresence>
            {entries.map((item, idx) => (
              <motion.div
                key={item.id}
                className={`doc-item ${item.isFolder ? 'is-folder' : 'is-file'}`}
                initial={{ opacity: 0, y: 15 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0 }}
                transition={{ delay: idx * 0.03 }}
                onClick={() => item.isFolder ? navigateToFolder(item) : showDetail(item)}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                  <div className="doc-icon">
                    {item.isFolder ? <FolderOpen size={20} /> : <FileText size={20} />}
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div className="doc-name">{item.name}</div>
                    <div className="doc-meta">
                      {item.isFolder ? 'Folder' : formatBytes(item.sizeInBytes)}
                      {item.modifiedAt && ` · ${formatDate(item.modifiedAt)}`}
                    </div>
                  </div>
                </div>

                {item.isFile && (
                  <div className="doc-actions" onClick={(e) => e.stopPropagation()}>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => showDetail(item)}
                      title="View details"
                    >
                      <Eye size={14} />
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => downloadDocument(item.id)}
                      title="Download"
                    >
                      <Download size={14} />
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => setUpdateTarget(item)}
                      title="Upload new version"
                    >
                      <UploadCloud size={14} />
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => setDeleteTarget(item)}
                      title="Delete"
                      style={{ color: 'var(--danger)' }}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                )}
              </motion.div>
            ))}
          </AnimatePresence>

          {!loading && entries.length === 0 && (
            <div className="empty-state" style={{ gridColumn: '1 / -1' }}>
              <FolderOpen size={48} className="empty-icon" />
              <h3>This folder is empty</h3>
              <p>Upload documents to get started.</p>
            </div>
          )}
        </div>
      )}

      {/* Upload Modal */}
      <AnimatePresence>
        {showUpload && (
          <motion.div
            className="modal-overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => !uploading && setShowUpload(false)}
          >
            <motion.div
              className="modal-content"
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              onClick={(e) => e.stopPropagation()}
            >
              <h3>Upload Document</h3>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: '1rem' }}>
                Upload to: <strong>{currentFolder.name}</strong>
              </p>

              <div
                className={`upload-zone ${dragover ? 'dragover' : ''}`}
                onDragOver={(e) => { e.preventDefault(); setDragover(true) }}
                onDragLeave={() => setDragover(false)}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
              >
                <div className="upload-icon">
                  <Upload size={36} />
                </div>
                {uploading ? (
                  <>
                    <div className="spinner" style={{ margin: '0 auto 0.5rem' }} />
                    <p>Uploading…</p>
                  </>
                ) : (
                  <>
                    <p>Drag & drop files here, or click to browse</p>
                    <p className="upload-hint">PDF, Word, text files up to 50MB</p>
                  </>
                )}
              </div>

              <input
                ref={fileInputRef}
                type="file"
                multiple
                style={{ display: 'none' }}
                onChange={(e) => handleFileUpload(e.target.files)}
              />

              <div className="modal-actions">
                <button
                  className="btn btn-secondary"
                  onClick={() => setShowUpload(false)}
                  disabled={uploading}
                >
                  Cancel
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Update Content (hidden file input triggered by updateTarget) */}
      {updateTarget && (
        <input
          ref={updateInputRef}
          type="file"
          style={{ display: 'none' }}
          onChange={(e) => {
            if (e.target.files?.[0]) handleUpdateContent(e.target.files[0])
            e.target.value = '' // reset
          }}
        />
      )}
      {updateTarget && (() => {
        // Auto-trigger file picker
        setTimeout(() => updateInputRef.current?.click(), 50)
        return null
      })()}

      {/* Delete Confirmation Modal */}
      <AnimatePresence>
        {deleteTarget && (
          <motion.div
            className="modal-overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => !deleting && setDeleteTarget(null)}
          >
            <motion.div
              className="modal-content"
              initial={{ scale: 0.95 }}
              animate={{ scale: 1 }}
              exit={{ scale: 0.95 }}
              onClick={(e) => e.stopPropagation()}
            >
              <h3>Delete Document</h3>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
                Are you sure you want to delete <strong>"{deleteTarget.name}"</strong>?
                This will move it to the Alfresco trashcan and remove all search index chunks.
              </p>
              <div className="modal-actions">
                <button className="btn btn-secondary" onClick={() => setDeleteTarget(null)} disabled={deleting}>
                  Cancel
                </button>
                <button className="btn btn-danger" onClick={handleDelete} disabled={deleting}>
                  {deleting ? 'Deleting…' : 'Delete'}
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Detail Panel */}
      <AnimatePresence>
        {selectedNode && (
          <motion.div
            className="detail-panel"
            initial={{ x: 380 }}
            animate={{ x: 0 }}
            exit={{ x: 380 }}
            transition={{ type: 'spring', damping: 25, stiffness: 200 }}
          >
            <div className="detail-header">
              <h3>{selectedNode.name}</h3>
              <button
                className="btn btn-ghost btn-icon"
                onClick={() => { setSelectedNode(null); setDetailData(null) }}
              >
                <X size={18} />
              </button>
            </div>

            {detailLoading && (
              <div className="loading-center">
                <div className="spinner" />
              </div>
            )}

            {detailData && !detailLoading && (
              <>
                <div className="detail-section">
                  <h4>General</h4>
                  <div className="detail-row">
                    <span className="label">Type</span>
                    <span className="value">{detailData.nodeType}</span>
                  </div>
                  <div className="detail-row">
                    <span className="label">MIME Type</span>
                    <span className="value">{detailData.mimeType || '—'}</span>
                  </div>
                  <div className="detail-row">
                    <span className="label">Size</span>
                    <span className="value">{formatBytes(detailData.sizeInBytes)}</span>
                  </div>
                  <div className="detail-row">
                    <span className="label">Version</span>
                    <span className="value">{detailData.versionLabel || '—'}</span>
                  </div>
                </div>

                <div className="detail-section">
                  <h4>Path</h4>
                  <div style={{
                    fontFamily: 'var(--font-mono)',
                    fontSize: '0.78rem',
                    color: 'var(--text-secondary)',
                    wordBreak: 'break-all',
                    padding: '0.5rem',
                    background: 'var(--bg-elevated)',
                    borderRadius: 'var(--radius-sm)',
                  }}>
                    {detailData.path}
                  </div>
                </div>

                <div className="detail-section">
                  <h4>Dates</h4>
                  <div className="detail-row">
                    <span className="label">Created</span>
                    <span className="value">{formatDate(detailData.createdAt)}</span>
                  </div>
                  <div className="detail-row">
                    <span className="label">Modified</span>
                    <span className="value">{formatDate(detailData.modifiedAt)}</span>
                  </div>
                  <div className="detail-row">
                    <span className="label">Created By</span>
                    <span className="value">{detailData.createdBy || '—'}</span>
                  </div>
                  <div className="detail-row">
                    <span className="label">Modified By</span>
                    <span className="value">{detailData.modifiedBy || '—'}</span>
                  </div>
                </div>

                {detailData.permissions && detailData.permissions.length > 0 && (
                  <div className="detail-section">
                    <h4>Permissions ({detailData.inheritPermissions ? 'inherited' : 'local only'})</h4>
                    {detailData.permissions.slice(0, 10).map((perm, i) => (
                      <div key={i} className="detail-row">
                        <span className="label" style={{ fontSize: '0.78rem' }}>
                          {perm.authorityId}
                        </span>
                        <span className="value" style={{ fontSize: '0.78rem' }}>
                          {perm.role}
                          <span style={{
                            marginLeft: '0.3rem',
                            color: perm.accessStatus === 'ALLOWED' ? 'var(--success)' : 'var(--danger)',
                            fontSize: '0.7rem',
                          }}>
                            {perm.accessStatus}
                          </span>
                        </span>
                      </div>
                    ))}
                  </div>
                )}

                <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
                  <button
                    className="btn btn-primary btn-sm"
                    onClick={() => downloadDocument(detailData.id)}
                    style={{ flex: 1 }}
                  >
                    <Download size={14} /> Download
                  </button>
                  <button
                    className="btn btn-danger btn-sm"
                    onClick={() => setDeleteTarget(detailData)}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Toast */}
      <AnimatePresence>
        {toast && (
          <motion.div
            className={`toast ${toast.type}`}
            initial={{ opacity: 0, y: 20, x: 20 }}
            animate={{ opacity: 1, y: 0, x: 0 }}
            exit={{ opacity: 0, y: 20 }}
          >
            <Info size={16} style={{
              color: toast.type === 'success' ? 'var(--success)'
                : toast.type === 'error' ? 'var(--danger)' : 'var(--info)',
              flexShrink: 0,
            }} />
            {toast.message}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
