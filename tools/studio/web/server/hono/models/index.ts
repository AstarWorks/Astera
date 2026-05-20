/**
 * Models feature sub-app: 3D model editor, ASDSL state machine, paint /
 * cuboid CRUD, composite shape primitives, screenshot bridge.
 */
import { Hono } from 'hono'
import { modelsListRoute } from './models'
import { stateRoute } from './state-route'
import { paintRoute } from './paint'
import { cuboidsRoute } from './cuboids'
import { compositeRoute } from './composite-route'
import { asdslRoute } from './asdsl-route'
import { screenshotRoute } from './screenshot'

export const modelsRoute = new Hono()

modelsRoute.route('/api/models', modelsListRoute)
modelsRoute.route('/api/state', stateRoute)
modelsRoute.route('/api/paint', paintRoute)
modelsRoute.route('/api/cuboids', cuboidsRoute)
modelsRoute.route('/api/composite', compositeRoute)
modelsRoute.route('/api/asdsl', asdslRoute)
modelsRoute.route('/api/screenshot', screenshotRoute)
