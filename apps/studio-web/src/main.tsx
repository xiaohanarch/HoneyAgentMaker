import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'
import { IdentityProvider } from './identity/identity-context'
import './index.css'

const root = document.getElementById('root')
if (root === null) throw new Error('页面缺少 #root 节点')

const queryClient = new QueryClient()

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <IdentityProvider>
        <App />
      </IdentityProvider>
    </QueryClientProvider>
  </StrictMode>,
)
