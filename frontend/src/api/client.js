/**
 * API client for the Semantic Search backend.
 * Handles JWT authentication and all REST API calls.
 * The JWT token and current user profile are stored in sessionStorage.
 */

const API_BASE = '/api';

/**
 * Gets the stored JWT token from sessionStorage.
 * @returns {string | null}
 */
export function getToken() {
  return sessionStorage.getItem('semsearch_jwt');
}

/**
 * Gets the stored user profile from sessionStorage.
 * @returns {{ username: string, firstName?: string, lastName?: string, email?: string, roles: string[], isAdmin: boolean } | null}
 */
export function getAuthUser() {
  const stored = sessionStorage.getItem('semsearch_user');
  if (!stored) return null;
  try {
    return JSON.parse(stored);
  } catch {
    return null;
  }
}

/**
 * Stores JWT token and user profile in sessionStorage.
 * @param {string} token
 * @param {object} user
 */
export function setAuth(token, user) {
  if (token) sessionStorage.setItem('semsearch_jwt', token);
  if (user) sessionStorage.setItem('semsearch_user', JSON.stringify(user));
}

/**
 * Clears stored JWT token and user profile.
 */
export function clearAuth() {
  sessionStorage.removeItem('semsearch_jwt');
  sessionStorage.removeItem('semsearch_user');
  sessionStorage.removeItem('semsearch_auth');
}

/**
 * Backward compatibility alias for clearAuth.
 */
export function clearCredentials() {
  clearAuth();
}

/**
 * Backward compatibility helper returning username and admin status.
 * @returns {{ username: string, isAdmin: boolean } | null}
 */
export function getCredentials() {
  const user = getAuthUser();
  if (!user) return null;
  return { username: user.username, isAdmin: !!user.isAdmin };
}

/**
 * Backward compatibility helper.
 */
export function saveCredentials(username) {
  // Kept for backward compatibility
}

/**
 * Checks if the user is currently authenticated (has stored JWT token).
 * @returns {boolean}
 */
export function isAuthenticated() {
  return !!getToken();
}

/**
 * Safely parses JSON response, providing clear error messages when the backend is unreachable or returns non-JSON.
 */
async function parseJsonResponse(res, fallbackMessage) {
  let data = null;
  const text = await res.text();
  if (text && text.trim().length > 0) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!res.ok) {
    if (data && data.error) {
      throw new Error(data.error);
    }
    if (res.status === 504 || res.status === 502 || res.status === 503) {
      throw new Error('Backend server unreachable. Please ensure the Spring Boot backend is running on port 8085.');
    }
    throw new Error(fallbackMessage || `Request failed with status ${res.status}`);
  }

  if (!data) {
    throw new Error('Server returned an empty response.');
  }

  return data;
}

/**
 * Logs in with username and password against Alfresco via backend.
 * @param {string} username
 * @param {string} password
 * @returns {Promise<object>} user object
 */
export async function login(username, password) {
  const res = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const data = await parseJsonResponse(res, 'Invalid credentials');
  setAuth(data.token, data.user);
  return data.user;
}

/**
 * Registers a new user account in Alfresco via backend.
 * @param {{ username: string, password: string, firstName?: string, lastName?: string, email: string }} userData
 * @returns {Promise<object>} user object
 */
export async function signup(userData) {
  const res = await fetch(`${API_BASE}/auth/signup`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(userData),
  });
  const data = await parseJsonResponse(res, 'Registration failed');
  setAuth(data.token, data.user);
  return data.user;
}

/**
 * Fetches the currently authenticated user profile from backend.
 * @returns {Promise<object>}
 */
export async function fetchMe() {
  const res = await apiFetch('/auth/me');
  const user = await parseJsonResponse(res, 'Failed to fetch user profile');
  sessionStorage.setItem('semsearch_user', JSON.stringify(user));
  return user;
}

/**
 * Builds the Authorization header value with JWT.
 * @returns {string}
 */
function getAuthHeader() {
  const token = getToken();
  if (!token) throw new Error('Not authenticated');
  return `Bearer ${token}`;
}

/**
 * Makes an authenticated fetch request to the backend API.
 * @param {string} path - API path (e.g., '/search?q=hello')
 * @param {RequestInit} options - fetch options
 * @returns {Promise<Response>}
 */
async function apiFetch(path, options = {}) {
  const token = getToken();
  const headers = {
    ...options.headers,
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  // Don't set Content-Type for FormData (browser sets it with boundary)
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  if (response.status === 401) {
    clearAuth();
    window.dispatchEvent(new CustomEvent('auth:expired'));
    throw new Error('Authentication expired or unauthorized');
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
 * Validates credentials by attempting to authenticate.
 * @param {string} username
 * @param {string} password
 * @returns {Promise<boolean>}
 */
export async function validateCredentials(username, password) {
  try {
    await login(username, password);
    return true;
  } catch {
    return false;
  }
}

// ── Workspaces & Permissions API ──────────────────────────────────────────

/**
 * Lists workspace folders accessible to current user.
 * @returns {Promise<Array>}
 */
export async function listWorkspaces() {
  const res = await apiFetch('/workspaces');
  return parseJsonResponse(res, 'Failed to fetch workspaces');
}

/**
 * Creates a new workspace folder (PUBLIC or PRIVATE).
 * @param {{ name: string, description?: string, visibility: 'PUBLIC' | 'PRIVATE' }} data
 * @returns {Promise<Object>}
 */
export async function createWorkspace(data) {
  const res = await apiFetch('/workspaces', {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return parseJsonResponse(res, 'Failed to create workspace');
}

/**
 * Gets details, permissions and activities of a workspace folder.
 * @param {string} id
 * @returns {Promise<Object>}
 */
export async function getWorkspaceDetail(id) {
  const res = await apiFetch(`/workspaces/${id}`);
  return parseJsonResponse(res, 'Failed to fetch workspace details');
}

/**
 * Deletes a workspace folder (owner/admin only).
 * @param {string} id
 * @returns {Promise<Object>}
 */
export async function deleteWorkspace(id) {
  const res = await apiFetch(`/workspaces/${id}`, {
    method: 'DELETE',
  });
  return parseJsonResponse(res, 'Failed to delete workspace');
}

/**
 * Requests write/collaborator access on a workspace folder.
 * @param {string} folderId
 * @param {{ reason?: string, requestedRole?: string }} data
 * @returns {Promise<Object>}
 */
export async function requestWorkspaceAccess(folderId, data) {
  const res = await apiFetch(`/workspaces/${folderId}/requests`, {
    method: 'POST',
    body: JSON.stringify(data || {}),
  });
  return parseJsonResponse(res, 'Failed to submit access request');
}

/**
 * Fetches pending access requests for folders owned by the logged-in user.
 * @returns {Promise<Array>}
 */
export async function getPendingAccessRequests() {
  const res = await apiFetch('/workspaces/requests/pending');
  return parseJsonResponse(res, 'Failed to fetch pending access requests');
}

/**
 * Fetches access requests raised by the logged-in user.
 * @returns {Promise<Array>}
 */
export async function getMyAccessRequests() {
  const res = await apiFetch('/workspaces/requests/mine');
  return parseJsonResponse(res, 'Failed to fetch my access requests');
}

/**
 * Reviews an access request (APPROVE or DENY).
 * @param {string} requestId
 * @param {{ action: 'APPROVE' | 'DENY', reviewComment?: string }} data
 * @returns {Promise<Object>}
 */
export async function reviewAccessRequest(requestId, data) {
  const res = await apiFetch(`/workspaces/requests/${requestId}/review`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
  return parseJsonResponse(res, 'Failed to review access request');
}

/**
 * Revokes a user's permissions on a workspace folder.
 * @param {string} folderId
 * @param {string} targetUser
 * @returns {Promise<Object>}
 */
export async function revokeWorkspacePermission(folderId, targetUser) {
  const res = await apiFetch(`/workspaces/${folderId}/permissions/${targetUser}`, {
    method: 'DELETE',
  });
  return parseJsonResponse(res, 'Failed to revoke permission');
}

