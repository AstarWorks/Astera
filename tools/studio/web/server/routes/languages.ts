/**
 * Language file API — TS mirror of `plugin/core/.../i18n/LanguageLoader.kt`.
 *
 *   GET /api/languages              list available locales (from filenames)
 *   GET /api/languages/:locale      flat key→value map for that locale
 *
 * Flatten rules (must match Kotlin):
 *   Map         → recurse, join keys with `.`
 *   Scalar      → leaf string
 *   List/Array  → leaf `string[]` (kept as-is, Kotlin behaviour)
 *
 * MiniMessage tags (`<gold>...</gold>`, `<bold>`, etc.) are NOT stripped at
 * the API layer — frontend decides whether to render them, escape them, or
 * convert. Same for `{placeholder}` substitutions: caller's job.
 *
 * Path: env STUDIO_LANGUAGES_DIR (default `<repo>/content/languages`).
 */
import { Hono } from 'hono'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import yaml from 'js-yaml'
import { languagesDir } from '../paths'

export const languages = new Hono()

export type LangValue = string | string[]
export type FlatMap = Record<string, LangValue>

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

export function flatten(prefix: string, node: unknown): FlatMap {
  if (isPlainObject(node)) {
    const out: FlatMap = {}
    for (const [k, v] of Object.entries(node)) {
      const nextPrefix = prefix ? `${prefix}.${k}` : k
      Object.assign(out, flatten(nextPrefix, v))
    }
    return out
  }
  if (Array.isArray(node)) {
    return { [prefix]: node.map((item) => (typeof item === 'string' ? item : String(item))) }
  }
  if (node === null || node === undefined) {
    return {}
  }
  return { [prefix]: typeof node === 'string' ? node : String(node) }
}

function listLocaleFiles(): { locale: string; file: string }[] {
  if (!existsSync(languagesDir)) return []
  const result: { locale: string; file: string }[] = []
  for (const name of readdirSync(languagesDir)) {
    if (!name.endsWith('.yaml') && !name.endsWith('.yml')) continue
    const full = path.join(languagesDir, name)
    try {
      if (!statSync(full).isFile()) continue
    } catch {
      continue
    }
    const locale = name.replace(/\.(yaml|yml)$/, '')
    if (!/^[A-Za-z0-9_-]{1,32}$/.test(locale)) continue
    result.push({ locale, file: full })
  }
  return result.sort((a, b) => a.locale.localeCompare(b.locale))
}

languages.get('/', (c) => {
  return c.json(listLocaleFiles().map((f) => f.locale))
})

languages.get('/:locale', (c) => {
  const locale = c.req.param('locale')
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(locale)) {
    return c.json({ error: 'invalid locale' }, 400)
  }
  const found = listLocaleFiles().find((f) => f.locale === locale)
  if (!found) return c.json({ error: 'locale not found' }, 404)
  let parsed: unknown
  try {
    parsed = yaml.load(readFileSync(found.file, 'utf8'))
  } catch (e) {
    return c.json({ error: `parse failed: ${(e as Error).message}` }, 500)
  }
  const flat = flatten('', parsed)
  return c.json({ locale, entries: flat })
})
