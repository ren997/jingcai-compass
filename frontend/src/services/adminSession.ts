import type { AdminSession } from '../types/api';

const STORAGE_KEY = 'jingcai-compass.admin-session';
let currentSession = readSession();
const listeners = new Set<() => void>();

function isBrowser() {
  return typeof window !== 'undefined';
}

function readSession(): AdminSession | null {
  if (!isBrowser()) {
    return null;
  }
  const rawValue = window.sessionStorage.getItem(STORAGE_KEY);
  if (!rawValue) {
    return null;
  }

  try {
    const session = JSON.parse(rawValue) as AdminSession;
    if (!session.accessToken || !session.expiresAt || Date.parse(session.expiresAt) <= Date.now()) {
      window.sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    window.sessionStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

function notifyListeners() {
  listeners.forEach((listener) => listener());
}

/** 返回当前有效管理员会话；过期会话会被自动移除。 */
export function getAdminSession(): AdminSession | null {
  if (currentSession && Date.parse(currentSession.expiresAt) <= Date.now()) {
    clearAdminSession();
  }
  return currentSession;
}

/** 保存本次浏览器会话有效的管理员 JWT。 */
export function setAdminSession(session: AdminSession) {
  currentSession = session;
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  notifyListeners();
}

/** 清除本地管理员 JWT，并通知路由守卫刷新。 */
export function clearAdminSession() {
  const hadSession = currentSession !== null;
  currentSession = null;
  if (isBrowser()) {
    window.sessionStorage.removeItem(STORAGE_KEY);
  }
  if (hadSession) {
    notifyListeners();
  }
}

/** 供 React 路由守卫订阅管理员会话变更。 */
export function subscribeAdminSession(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
