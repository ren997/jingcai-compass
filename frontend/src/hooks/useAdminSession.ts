import { useSyncExternalStore } from 'react';
import {
  getAdminSession,
  subscribeAdminSession,
} from '../services/adminSession';

/** 订阅管理员登录状态，供权限路由和布局使用。 */
export function useAdminSession() {
  return useSyncExternalStore(subscribeAdminSession, getAdminSession, () => null);
}
