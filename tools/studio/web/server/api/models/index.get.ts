import { modelSamplesDir, modelsContentDir } from '../../utils/paths'
import { listFromDir } from '../../utils/models/models-lib'

export default defineEventHandler(() => [
  ...listFromDir(modelSamplesDir, 'samples'),
  ...listFromDir(modelsContentDir, 'content'),
])
