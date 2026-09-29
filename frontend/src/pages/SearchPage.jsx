import { useState, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Search, X, FileText, Download, ExternalLink, Sparkles, Zap } from 'lucide-react'
import { searchDocuments, downloadDocument, openDocument } from '../api/client'

export default function SearchPage() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState(null)

  const handleSearch = useCallback(async (e) => {
    e?.preventDefault()
    if (!query.trim()) return

    setLoading(true)
    setError(null)
    setSearched(true)

    try {
      const data = await searchDocuments(query.trim(), 10)
      setResults(data)
    } catch (err) {
      setError(err.message)
      setResults([])
    } finally {
      setLoading(false)
    }
  }, [query])

  const handleClear = () => {
    setQuery('')
    setResults([])
    setSearched(false)
    setError(null)
  }

  const handleDownload = async (e, nodeId) => {
    e.stopPropagation()
    try {
      await downloadDocument(nodeId)
    } catch (err) {
      console.error('Download failed:', err)
    }
  }

  const handleOpen = async (e, nodeId) => {
    e.stopPropagation()
    try {
      await openDocument(nodeId)
    } catch (err) {
      console.error('Open failed:', err)
    }
  }

  return (
    <div>
      {/* Page Header */}
      <div className="page-header" style={{ textAlign: 'center', paddingTop: searched ? '0' : '8vh' }}>
        <motion.div
          layout
          transition={{ duration: 0.4, ease: 'easeInOut' }}
        >
          {!searched && (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.1 }}
              style={{ marginBottom: '2rem' }}
            >
              <div style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '0.5rem',
                padding: '0.4rem 1rem',
                background: 'var(--primary-glow)',
                border: '1px solid hsla(217, 91%, 60%, 0.2)',
                borderRadius: 'var(--radius-full)',
                fontSize: '0.78rem',
                fontWeight: 600,
                color: 'var(--primary-400)',
                marginBottom: '1.5rem',
              }}>
                <Zap size={14} />
                Powered by Elasticsearch + Nomic Embeddings
              </div>
              <h2 style={{ fontSize: '2.5rem', fontWeight: 800, marginBottom: '0.5rem' }}>
                <span style={{
                  background: 'linear-gradient(135deg, var(--primary-400), var(--accent-400))',
                  WebkitBackgroundClip: 'text',
                  WebkitTextFillColor: 'transparent',
                  backgroundClip: 'text',
                }}>
                  Semantic Search
                </span>
              </h2>
              <p style={{ color: 'var(--text-secondary)', maxWidth: 500, margin: '0 auto' }}>
                Search through Alfresco documents using natural language.
                BM25 keyword matching + kNN vector search, fused with Reciprocal Rank Fusion.
              </p>
            </motion.div>
          )}

          {searched && (
            <div className="page-header" style={{ textAlign: 'left' }}>
              <h2>Search Results</h2>
              <p>{results.length} document{results.length !== 1 ? 's' : ''} found for "{query}"</p>
            </div>
          )}
        </motion.div>
      </div>

      {/* Search Bar */}
      <motion.div layout className="search-container">
        <form onSubmit={handleSearch}>
          <div className="search-input-wrapper">
            <Search size={20} className="search-icon" />
            <input
              type="text"
              className="input input-lg"
              placeholder="Search documents... e.g., 'quarterly revenue report' or 'employee onboarding policy'"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              style={{ paddingLeft: '3.25rem', paddingRight: query ? '3.25rem' : '1.25rem' }}
              autoFocus
            />
            {query && (
              <button
                type="button"
                className="btn btn-ghost btn-icon search-clear"
                onClick={handleClear}
              >
                <X size={18} />
              </button>
            )}
          </div>
        </form>
      </motion.div>

      {/* Loading */}
      {loading && (
        <div className="loading-center" style={{ marginTop: '3rem' }}>
          <div style={{ textAlign: 'center' }}>
            <div className="spinner" style={{ margin: '0 auto 1rem', width: 32, height: 32 }} />
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
              Embedding query & searching…
            </p>
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          style={{
            maxWidth: 720,
            margin: '2rem auto 0',
            padding: '1rem',
            background: 'var(--danger-bg)',
            border: '1px solid hsla(0, 72%, 51%, 0.3)',
            borderRadius: 'var(--radius-md)',
            color: 'var(--danger)',
            fontSize: '0.875rem',
          }}
        >
          Search failed: {error}
        </motion.div>
      )}

      {/* Results */}
      {!loading && !error && (
        <div className="search-results" style={{ maxWidth: 720, margin: '0 auto' }}>
          <AnimatePresence>
            {results.map((result, index) => (
              <motion.div
                key={result.id}
                className="result-card"
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                transition={{ delay: index * 0.05, duration: 0.3 }}
              >
                <div className="result-header">
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <FileText size={18} style={{ color: 'var(--primary-400)', flexShrink: 0 }} />
                    <span className="result-name">{result.name}</span>
                  </div>
                  <span className="score-badge">
                    <Sparkles size={12} />
                    {result.score.toFixed(4)}
                  </span>
                </div>

                <div className="result-path">{result.path}</div>

                <div className="result-snippet">
                  {result.snippet}
                </div>

                <div className="result-footer">
                  <span style={{ fontSize: '0.75rem', color: 'var(--text-tertiary)' }}>
                    Node: {result.id.substring(0, 8)}…
                  </span>
                  <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={(e) => handleDownload(e, result.id)}
                      title="Download"
                    >
                      <Download size={14} />
                      Download
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={(e) => handleOpen(e, result.id)}
                      title="Open document"
                    >
                      <ExternalLink size={14} />
                      Open
                    </button>
                  </div>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>

          {/* Empty state */}
          {searched && !loading && results.length === 0 && !error && (
            <motion.div
              className="empty-state"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
            >
              <Search size={48} className="empty-icon" />
              <h3>No documents found</h3>
              <p>Try a different query or upload some documents first.</p>
            </motion.div>
          )}
        </div>
      )}
    </div>
  )
}
