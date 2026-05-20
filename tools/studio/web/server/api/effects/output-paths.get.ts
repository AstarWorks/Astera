import { repoRoot } from '../../utils/paths'
import { effectsDir } from '../../utils/effects/manifest'
import {
  animationsDir,
  modelsEffectsDir,
  relToRepo,
} from '../../utils/effects/effects-lib'

/**
 * Answers "where do my exports actually land?". Returns repo-relative paths
 * + absolute paths so the UI can show them as hints in the sidebar.
 */
export default defineEventHandler(() => ({
  repo_root: repoRoot,
  paths: {
    recipes: relToRepo(effectsDir),
    textures: relToRepo(animationsDir),
    models: relToRepo(modelsEffectsDir),
  },
  absolute: {
    recipes: effectsDir,
    textures: animationsDir,
    models: modelsEffectsDir,
  },
}))
