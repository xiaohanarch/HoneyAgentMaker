import type { ReactNode } from 'react'
import { Navigate, createBrowserRouter, RouterProvider } from 'react-router'
import { AssistantPage } from './pages/AssistantPage'
import { ConnectionCenterPage } from './pages/ConnectionCenterPage'
import { DraftPage } from './pages/DraftPage'
import { HubPage } from './pages/HubPage'
import { ImChannelPage } from './pages/ImChannelPage'
import { NewAgentPage } from './pages/NewAgentPage'
import { StudioPage } from './pages/StudioPage'
import { AppShell } from './shell/AppShell'
import { ExtensionPage } from './studio/extend/ExtensionPage'
import { VendorAgentsPage } from './studio/extend/VendorAgentsPage'
import { SkillEditorPage } from './studio/skills/SkillEditorPage'
import { ToolCenterPage } from './studio/tools/ToolCenterPage'

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/assistant" replace /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'channels/im', element: <ImChannelPage /> },
      { path: 'studio', element: <StudioPage /> },
      { path: 'studio/connections', element: <ConnectionCenterPage /> },
      { path: 'studio/agents/new', element: <NewAgentPage /> },
      { path: 'studio/drafts/:id', element: <DraftPage /> },
      { path: 'studio/drafts/:id/:step', element: <DraftPage /> },
      { path: 'studio/vendor-agents', element: <VendorAgentsPage /> },
      { path: 'studio/extensions/:id', element: <ExtensionPage /> },
      { path: 'studio/tools', element: <ToolCenterPage /> },
      { path: 'studio/skills/:id', element: <SkillEditorPage /> },
      { path: 'hub', element: <HubPage /> },
      { path: '*', element: <Navigate to="/assistant" replace /> },
    ],
  },
])

export function App(): ReactNode {
  return <RouterProvider router={router} />
}
