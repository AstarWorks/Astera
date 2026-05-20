import { existsSync } from 'node:fs'
import path from 'node:path'
import { latestVanillaVersion, repoRoot, texturesDir } from '../../utils/paths'

export default defineEventHandler(() => {
  const v = latestVanillaVersion()
  return {
    astera: existsSync(texturesDir),
    vanilla: v !== null,
    vanilla_version: v,
    content: existsSync(path.join(repoRoot, 'content', 'models')),
  }
})
