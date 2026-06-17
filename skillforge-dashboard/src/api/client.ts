import axios from 'axios';

const api = axios.create({ baseURL: '/api' });

// Navigation function - set by React Router context to enable client-side navigation
// Falls back to window.location.href if not set
let navigateFn: ((path: string) => void) | null = null;

export function setApiNavigate(fn: (path: string) => void) {
  navigateFn = fn;
}

// Request interceptor: inject Bearer token from localStorage
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('sf_token');
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: redirect to /login on 401
let isRedirecting = false;

api.interceptors.response.use(
  (res) => res,
  (error: unknown) => {
    const status =
      error &&
      typeof error === 'object' &&
      'response' in error &&
      (error as { response?: { status?: number } }).response?.status;
    const requestUrl =
      (error &&
      typeof error === 'object' &&
      'config' in error &&
      (error as { config?: { url?: string } }).config?.url) ?? '';
    // Skip auto-redirect for auth endpoints — let the caller handle the error directly
    const isAuthEndpoint = typeof requestUrl === 'string' && requestUrl.startsWith('/auth/');
    if (status === 401 && !isAuthEndpoint && !isRedirecting) {
      if (!window.location.pathname.includes('/login')) {
        isRedirecting = true;
        localStorage.removeItem('sf_token');
        // Use React Router navigation if available (no full page reload)
        // Otherwise fall back to window.location.href
        setTimeout(() => {
          if (navigateFn) {
            navigateFn('/login');
          } else {
            window.location.href = '/login';
          }
          isRedirecting = false;
        }, 100);
      }
    }
    return Promise.reject(error);
  },
);

/** Unwrap a paginated-or-direct array response from the backend. */
export function extractList<T>(res: { data: T[] | { data: T[] } | unknown }): T[] {
  const d = (res as { data: unknown }).data;
  if (Array.isArray(d)) return d as T[];
  if (d && typeof d === 'object' && Array.isArray((d as { data?: unknown }).data)) {
    return (d as { data: T[] }).data;
  }
  return [];
}

export default api;
