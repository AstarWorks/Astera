/**
 * Nitro catch-all for /api/**. Hands off to the Hono app, which owns all
 * concrete routes. This single dispatcher replaces the Bun.serve() entrypoint
 * that the pre-Nuxt Studio used to run on a sibling port.
 */
import { app } from '../hono/app'

export default defineEventHandler(async (event) => {
  const request = toWebRequest(event)
  return app.fetch(request)
})
