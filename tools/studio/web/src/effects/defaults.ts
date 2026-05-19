/**
 * Default-params lookup for newly-added nodes. Wraps schema.ts so call sites
 * don't import the schema directly when all they need is the per-type defaults.
 */
export { defaultParamsFor } from './schema'
