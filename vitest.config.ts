import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: [
      'apps/*/src/**/*.test.{ts,tsx}',
      'packages/*/src/**/*.test.{ts,tsx}',
      'external-agents/*/src/**/*.test.ts',
    ],
    environment: 'node',
    passWithNoTests: true,
  },
})
