import { getAsdsl } from '../../utils/models/asdsl/state'

export default defineEventHandler(() => ({ asdsl: getAsdsl() }))
