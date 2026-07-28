import { requestApi } from './http';
import type { AdminSession } from '../types/api';

export type AdminLoginDto = {
  username: string;
  password: string;
};

/** 使用管理员账号换取短期 Bearer Token。 */
export function loginAdmin(request: AdminLoginDto) {
  return requestApi<AdminSession>('/api/admin/auth/login', {
    method: 'POST',
    body: request,
  });
}

/** 使当前管理员账号的既有 JWT 立即失效。 */
export function logoutAdmin() {
  return requestApi<void>('/api/admin/auth/logout', {
    method: 'POST',
    authenticated: true,
  });
}
