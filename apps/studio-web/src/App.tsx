import type { ReactNode } from 'react'
import { Navigate, createBrowserRouter, RouterProvider } from 'react-router'
import { AssistantPage } from './pages/AssistantPage'
import { HubPage } from './pages/HubPage'
import { StudioPage } from './pages/StudioPage'
import { AppShell } from './shell/AppShell'

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/assistant" replace /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'studio', element: <StudioPage /> },
      { path: 'hub', element: <HubPage /> },
      { path: '*', element: <Navigate to="/assistant" replace /> },
    ],
  },
])

export function App(): ReactNode {
  return <RouterProvider router={router} />
}
