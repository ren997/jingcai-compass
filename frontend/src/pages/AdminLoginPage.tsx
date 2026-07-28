import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Form, Input } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAdminSession } from '../hooks/useAdminSession';
import { loginAdmin, type AdminLoginDto } from '../services/admin';
import { setAdminSession } from '../services/adminSession';

type LoginLocationState = {
  from?: string;
};

function resolveReturnPath(state: unknown) {
  const candidate = (state as LoginLocationState | null)?.from;
  return candidate?.startsWith('/admin') ? candidate : '/admin';
}

/** 管理员登录页。 */
export default function AdminLoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const session = useAdminSession();
  const returnPath = resolveReturnPath(location.state);
  const loginMutation = useMutation({
    mutationFn: loginAdmin,
    onSuccess: (newSession) => {
      setAdminSession(newSession);
      navigate(returnPath, { replace: true });
    },
  });

  if (session) {
    return <Navigate replace to={returnPath} />;
  }

  return (
    <main className="login-page">
      <section className="login-card">
        <p className="eyebrow">Administrator access</p>
        <h1>管理员登录</h1>
        <p className="login-summary">登录后可访问受保护的运营与复核功能。</p>
        {loginMutation.isError && (
          <Alert
            className="login-error"
            message={loginMutation.error.message}
            showIcon
            type="error"
          />
        )}
        <Form<AdminLoginDto>
          layout="vertical"
          onFinish={(values) => loginMutation.mutate(values)}
          requiredMark={false}
        >
          <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input autoComplete="username" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Button block htmlType="submit" loading={loginMutation.isPending} type="primary">
            登录后台
          </Button>
        </Form>
      </section>
    </main>
  );
}
