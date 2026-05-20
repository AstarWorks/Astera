/**
 * Textures feature sub-app: texture-gen jobs, tile review, catalog browsing.
 */
import { Hono } from 'hono'
import { jobsRoute } from './jobs'
import { tilesRoute } from './tiles'
import { generateRoute } from './generate'
import { catalogRoute, catalogMetaRoute } from './catalog'

export const texturesRoute = new Hono()

// Order matters: tiles (deeper /:jobId/tiles/* paths) must register before
// jobs in the same prefix so /accept etc. don't get shadowed by GET /:id.
texturesRoute.route('/api/jobs', tilesRoute)
texturesRoute.route('/api/jobs', jobsRoute)
texturesRoute.route('/api/generate', generateRoute)
texturesRoute.route('/api/textures', catalogRoute)
texturesRoute.route('/api', catalogMetaRoute)  // /api/manifest, /api/categories
