/**
 * Texture-generation prompt preset. Astera house style: 32×32 pixel-art with
 * LL→UR diagonal weapons orientation. External projects can override the
 * whole template by pointing STUDIO_TEXTURE_PROMPT_FILE at a plain text file.
 *
 * Template variables: ${workDir} ${rows} ${cols} ${count} ${subject}
 */
import { existsSync, readFileSync } from 'node:fs'

const DEFAULT_TEXTURE_PROMPT_TEMPLATE = `Use your image-generation tool to create a single PNG at \${workDir}/grid.png.

Subject: A \${rows}×\${cols} grid of \${count} distinct \${subject} in pixel-art style.
- List each cell with its variant name (top-left, top-right, ...) so the
  model knows what goes where.

Style requirements:
- Classic 32×32 pixel-art aesthetic look (we will downscale)
- Hard 1-pixel dark outlines around every shape
- Limited palette: <=16 distinct colors total across all cells
- Consistent palette and outline width across all variants
- No anti-aliasing on edges, no semi-transparent pixels
- Bold silhouettes readable at thumbnail size
- DENSE SUBJECT — each item fills 70%+ of its cell, chunky vanilla
  Minecraft item style (think iron_sword.png: roughly 12x14 px subject
  filling a 16x16 canvas). NOT a thin diagonal line with empty space
  around it. The subject should look "solid", not "sketched".
- WEAPONS / TOOLS (swords, guns, wands, staves, bows, pickaxes,
  hammers, etc.): drawn diagonally from LOWER-LEFT to UPPER-RIGHT,
  matching the vanilla Minecraft iron_sword inventory icon
  orientation. The "business end" (blade tip, gun barrel, wand
  crystal, axe head) is at the UPPER-RIGHT corner of the cell. The
  "grip end" (hilt, pistol grip, wand handle, axe haft) is at the
  LOWER-LEFT corner. This is the Astera house diagonal — consistent
  across every weapon.
- NON-WEAPON items (potions, food, blocks, materials, scrolls, etc.):
  drawn upright/orthogonal, NOT diagonal. Fill the cell densely.

Layout requirements (CRITICAL — read carefully):
- One square output image divided into a \${rows}x\${cols} grid (\${count} equal cells).
- DRAW A VISIBLE 2-PIXEL GRAY INSET BORDER around EACH cell. The border
  is drawn as actual visible pixels in mid-gray (around RGB 110-130).
  This border is part of the output image — it is NOT optional and it
  is NOT a metaphor. Picture each cell as a small framed picture-box.
- Every subject sits INSIDE its cell's gray border with at least 2 pixels
  of pure black between the subject's silhouette and the border on
  every side. The border PHYSICALLY constrains where the subject can go.
- The gray borders form a continuous grid pattern across the whole image
  (think of a window with mullions or a contact sheet of framed photos).
- Background inside each cell (the space around the subject) is pure
  black. We will key out the black to transparency in post-processing
  and strip the gray border at slice time.
- No text labels, no numbers, no captions inside the cells.
- Each subject is centered within its cell's interior AND fills it
  densely (see Style requirements above).
- All subjects roughly the same physical size relative to their cell.

After generation, save the file to: \${workDir}/grid.png
`

export function texturePromptTemplate(): string {
  const overridePath = process.env.STUDIO_TEXTURE_PROMPT_FILE
  if (overridePath) {
    if (!existsSync(overridePath)) {
      console.warn(
        `[prompts] STUDIO_TEXTURE_PROMPT_FILE=${overridePath} does not exist, falling back to default`,
      )
    } else {
      return readFileSync(overridePath, 'utf8')
    }
  }
  return DEFAULT_TEXTURE_PROMPT_TEMPLATE
}

export function substitute(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\$\{(\w+)\}/g, (_, k) => String(vars[k] ?? ''))
}
