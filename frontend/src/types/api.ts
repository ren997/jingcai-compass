/** 服务端统一响应包装。 */
export type ApiResponse<T> = {
  code: string;
  message: string;
  data: T;
  traceId: string;
};

export type AdminRole = 'ADMIN';

/** 管理员短期登录会话。 */
export type AdminSession = {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  adminId: number;
  username: string;
  role: AdminRole;
};
