/**
 * Generic `${VAR}` template substitution. Used by per-feature prompt loaders
 * (utils/textures/prompts.ts, utils/effects/prompts.ts).
 */
export function substitute(
  template: string,
  vars: Record<string, string | number>,
): string {
  return template.replace(/\$\{(\w+)\}/g, (_, k) => String(vars[k] ?? ''))
}
