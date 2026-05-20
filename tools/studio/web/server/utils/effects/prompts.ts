/**
 * Effects-generation prompt preset. Drives the AI-image node in the fxgraph
 * pipeline. External projects can override via STUDIO_EFFECTS_PROMPT_FILE.
 *
 * Template variables: ${workDir} ${prompt} ${seed}
 */
import { existsSync, readFileSync } from 'node:fs'

const DEFAULT_EFFECTS_PROMPT_TEMPLATE = `Use your image-generation tool to create a single 1024x1024 PNG at \${workDir}/grid.png.

Subject: \${prompt}

Style requirements (Astera Effects):
- Pixel-art aesthetic that downscales cleanly to 32x32
- Hard 1-pixel dark outlines
- Limited palette: <=16 distinct colors
- Subject centered, no anti-aliased edges
- Pure-black background (we will key out to transparency)
- Seed: \${seed}
`

export function effectsPromptTemplate(): string {
  const overridePath = process.env.STUDIO_EFFECTS_PROMPT_FILE
  if (overridePath) {
    if (!existsSync(overridePath)) {
      console.warn(
        `[prompts] STUDIO_EFFECTS_PROMPT_FILE=${overridePath} does not exist, falling back to default`,
      )
    } else {
      return readFileSync(overridePath, 'utf8')
    }
  }
  return DEFAULT_EFFECTS_PROMPT_TEMPLATE
}
