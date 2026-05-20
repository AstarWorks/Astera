import { SCHEMA } from '#shared/effects/schema'

/**
 * Serve the node schema as JSON. Single source of truth for the chat AI's
 * system prompt and for any external tool inspecting available node types.
 */
export default defineEventHandler(() => ({ schema: SCHEMA }))
