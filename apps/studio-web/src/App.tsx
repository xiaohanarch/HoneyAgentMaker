import type { ReactNode } from 'react'
import { Navigate, createBrowserRouter, RouterProvider } from 'react-router'
import { AssistantPage } from './pages/AssistantPage'
import { ConnectionCenterPage } from './pages/ConnectionCenterPage'
import { HubPage } from './pages/HubPage'
import { ImChannelPage } from './pages/ImChannelPage'
import { StudioPage } from './pages/StudioPage'
import { AppShell } from './shell/AppShell'

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/assistant" replace /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'channels/im', element: <ImChannelPage /> },
      { path: 'studio', element: <StudioPage /> },
      { path: 'studio/connections', element: <ConnectionCenterPage /> },
      { path: 'hub', element: <HubPage /> },
      { path: '*', element: <Navigate to="/assistant" replace /> },
    ],
  },
])

export function App(): ReactNode {
  return <RouterProvider router={router} />
}
