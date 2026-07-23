import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import type { PropsWithChildren } from 'react';
import { BrowserRouter } from 'react-router-dom';

export function createAppQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: 1,
        refetchOnWindowFocus: false,
        staleTime: 30_000,
      },
    },
  });
}

type AppProvidersProps = PropsWithChildren<{
  queryClient: QueryClient;
}>;

export default function AppProviders({ children, queryClient }: AppProvidersProps) {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#176b42',
          borderRadius: 10,
          fontFamily: '"Segoe UI", "PingFang SC", sans-serif',
        },
      }}
    >
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>{children}</BrowserRouter>
      </QueryClientProvider>
    </ConfigProvider>
  );
}
