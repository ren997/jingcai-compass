import { Component, type ErrorInfo, type PropsWithChildren, type ReactNode } from 'react';

type AppErrorBoundaryState = {
  hasError: boolean;
};

export default class AppErrorBoundary extends Component<
  PropsWithChildren,
  AppErrorBoundaryState
> {
  state: AppErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('页面渲染异常', error, errorInfo);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <main className="page">
          <section className="state-card error" role="alert">
            页面加载失败，请刷新后重试。
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}
