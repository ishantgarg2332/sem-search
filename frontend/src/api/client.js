/**
 * API client for the Semantic Search backend.
 * Handles authentication (HTTP Basic) and all REST API calls.
 * The credentials are stored in sessionStorage.
 */

const API_BASE = '/api';

/**
 * Gets the stored auth credentials from sessionStorage.
 * @returns {{ username: string, password: string } | null}
 */
export function getCredentials() {
  const stored = sessionStorage.getItem('semsearch_auth');
  if (!stored) return null;
  try {
    return JSON.parse(stored);
  } catch {
    return null;
  }
}

/**
 * Saves auth credentials to sessionStorage.
 * @param {string} username
 * @param {string} password
 */
export function saveCredentials(username, password) {
  sessionStorage.setItem('semsearch_auth', JSON.stringify({ username, password }));
}

/**
 * Clears stored auth credentials.
 */
export function clearCredentials() {
  sessionStorage.removeItem('semsearch_auth');
}

/**
 * Checks if the user is currently authenticated (has stored creds).
 * @returns {boolean}
 */
export function isAuthenticated() {
  return getCredentials() !== null;
}

/**
 * Builds the Basic Auth header value.
 * @returns {string}
 */
function getAuthHeader() {
  const creds = getCredentials();
  if (!creds) throw new Error('Not authenticated');
  return 'Basic ' + btoa(`${creds.username}:${creds.password}`);
}

/**
 * Makes an authenticated fetch request to the backend API.
 * @param {string} path - API path (e.g., '/search?q=hello')
 * @param {RequestInit} options - fetch options
 * @returns {Promise<Response>}
 */
async function apiFetch(path, options = {}) {
  const headers = {
    ...options.headers,
    'Authorization': getAuthHeader(),
  };

  // Don't set Content-Type for FormData (browser sets it with boundary)
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  if (response.status === 401) {
    clearCredentials();
    window.location.reload();
    throw new Error('Authentication failed');
  }

  return response;
}

// ── Search API ──────────────────────────────────────────────────────────────

/**
 * Performs a semantic search query.
 * @param {string} query - the search query
 * @param {number} limit - max results (default: 10)
 * @returns {Promise<Array>}
 */
export async function searchDocuments(query, limit = 10) {
  const res = await apiFetch(`/search?q=${encodeURIComponent(query)}&limit=${limit}`);
  if (!res.ok) throw new Error(`Search failed: ${res.status}`);
  return res.json();
}

// ── Document Management API ─────────────────────────────────────────────────

/**
 * Lists children of an Alfresco folder.
 * @param {string} folderId - folder node UUID (use '-root-' for root)
 * @param {number} skipCount - pagination offset
 * @param {number} maxItems - items per page
 * @returns {Promise<{entries: Array, pagination: Object}>}
 */
export async function listDocuments(folderId = '-root-', skipCount = 0, maxItems = 25) {
  const res = await apiFetch(
    `/documents?folderId=${encodeURIComponent(folderId)}&skipCount=${skipCount}&maxItems=${maxItems}`
  );
  if (!res.ok) throw new Error(`List failed: ${res.status}`);
  return res.json();
}

/**
 * Gets metadata for a single document/folder.
 * @param {string} nodeId
 * @returns {Promise<Object>}
 */
export async function getDocument(nodeId) {
  const res = await apiFetch(`/documents/${nodeId}`);
  if (!res.ok) throw new Error(`Get document failed: ${res.status}`);
  return res.json();
}

/**
 * Downloads the content of a document.
 * @param {string} nodeId
 */
export async function downloadDocument(nodeId) {
  const res = await apiFetch(`/documents/${nodeId}/content?disposition=attachment`);
  if (!res.ok) throw new Error(`Download failed: ${res.status}`);

  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition');
  let filename = 'download';
  if (disposition) {
    const matchUtf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i);
    const matchSimple = disposition.match(/filename="?([^";]+)"?/i);
    if (matchUtf8) {
      filename = decodeURIComponent(matchUtf8[1]);
    } else if (matchSimple) {
      filename = matchSimple[1];
    }
  }

  // Trigger browser download
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/**
 * Opens the document content in a new browser tab using an authenticated blob URL.
 * Prevents 401 Unauthorized errors from direct Alfresco links.
 * @param {string} nodeId
 */
export async function openDocument(nodeId) {
  const res = await apiFetch(`/documents/${nodeId}/content?disposition=inline`);
  if (!res.ok) throw new Error(`Open failed: ${res.status}`);

  const blob = await res.blob();
  const contentType = res.headers.get('Content-Type') || 'application/pdf';
  const fileBlob = new Blob([blob], { type: contentType });
  const url = URL.createObjectURL(fileBlob);
  window.open(url, '_blank');
}

/**
 * Uploads a file to an Alfresco folder.
 * @param {File} file - the file to upload
 * @param {string} parentId - target folder UUID
 * @returns {Promise<Object>}
 */
export async function uploadDocument(file, parentId = '-root-') {
  const formData = new FormData();
  formData.append('file', file);

  const res = await apiFetch(`/documents/upload?parentId=${encodeURIComponent(parentId)}`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Upload failed: ${res.status} - ${text}`);
  }
  return res.json();
}

/**
 * Updates the content of an existing document with a new file.
 * @param {string} nodeId - the document node UUID
 * @param {File} file - the new file content
 * @returns {Promise<Object>}
 */
export async function updateDocumentContent(nodeId, file) {
  const formData = new FormData();
  formData.append('file', file);

  const res = await apiFetch(`/documents/${nodeId}/content`, {
    method: 'PUT',
    body: formData,
  });
  if (!res.ok) throw new Error(`Update failed: ${res.status}`);
  return res.json();
}

/**
 * Deletes a document from Alfresco.
 * @param {string} nodeId
 * @returns {Promise<Object>}
 */
export async function deleteDocument(nodeId) {
  const res = await apiFetch(`/documents/${nodeId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Delete failed: ${res.status}`);
  return res.json();
}

// ── Admin API ───────────────────────────────────────────────────────────────

/**
 * Gets aggregate job stats (pending, running, done, failed counts).
 * @returns {Promise<Object>}
 */
export async function getJobStats() {
  const res = await apiFetch('/admin/jobs/stats');
  if (!res.ok) throw new Error(`Stats failed: ${res.status}`);
  return res.json();
}

/**
 * Gets recent jobs for the activity feed.
 * @param {number} limit
 * @returns {Promise<Array>}
 */
export async function getRecentJobs(limit = 20) {
  const res = await apiFetch(`/admin/jobs/recent?limit=${limit}`);
  if (!res.ok) throw new Error(`Recent jobs failed: ${res.status}`);
  return res.json();
}

/**
 * Gets failed jobs.
 * @param {number} limit
 * @returns {Promise<Array>}
 */
export async function getFailedJobs(limit = 50) {
  const res = await apiFetch(`/admin/jobs/failed?limit=${limit}`);
  if (!res.ok) throw new Error(`Failed jobs failed: ${res.status}`);
  return res.json();
}

/**
 * Requeues all failed jobs.
 * @returns {Promise<Object>}
 */
export async function requeueFailedJobs() {
  const res = await apiFetch('/admin/jobs/requeue', { method: 'POST' });
  if (!res.ok) throw new Error(`Requeue failed: ${res.status}`);
  return res.json();
}

/**
 * Triggers content reconciliation.
 * @returns {Promise<Object>}
 */
export async function triggerContentReconciliation() {
  const res = await apiFetch('/admin/reconcile/content', { method: 'POST' });
  if (!res.ok) throw new Error(`Reconciliation failed: ${res.status}`);
  return res.json();
}

/**
 * Triggers orphan reconciliation.
 * @returns {Promise<Object>}
 */
export async function triggerOrphanReconciliation() {
  const res = await apiFetch('/admin/reconcile/orphans', { method: 'POST' });
  if (!res.ok) throw new Error(`Reconciliation failed: ${res.status}`);
  return res.json();
}

/**
 * Triggers permission drift reconciliation.
 * @returns {Promise<Object>}
 */
export async function triggerPermissionReconciliation() {
  const res = await apiFetch('/admin/reconcile/permissions', { method: 'POST' });
  if (!res.ok) throw new Error(`Reconciliation failed: ${res.status}`);
  return res.json();
}

/**
 * Validates credentials by making a test search request.
 * @param {string} username
 * @param {string} password
 * @returns {Promise<boolean>}
 */
export async function validateCredentials(username, password) {
  try {
    const headers = {
      'Authorization': 'Basic ' + btoa(`${username}:${password}`),
      'Content-Type': 'application/json',
    };
    const res = await fetch(`${API_BASE}/search?q=test&limit=1`, { headers });
    return res.ok || res.status !== 401;
  } catch {
    return false;
  }
}
