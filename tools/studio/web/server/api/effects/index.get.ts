import { effectsSamplesDir } from '../../utils/paths'
import { effectsDir } from '../../utils/effects/manifest'
import {
  listFxGraphs,
  type EffectListEntry,
} from '../../utils/effects/effects-lib'

export default defineEventHandler((): EffectListEntry[] => [
  ...listFxGraphs(effectsSamplesDir, 'samples'),
  ...listFxGraphs(effectsDir, 'content'),
])
