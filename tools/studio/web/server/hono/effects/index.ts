/**
 * Effects feature sub-app: fxgraph CRUD, Claude chat for graph editing.
 */
import { Hono } from 'hono'
import { effectsCrudRoute } from './effects'
import { chatRoute } from './chat'

export const effectsRoute = new Hono()

effectsRoute.route('/api/effects', effectsCrudRoute)
effectsRoute.route('/api/effects/chat', chatRoute)
