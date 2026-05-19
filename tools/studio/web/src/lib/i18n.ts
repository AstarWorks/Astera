/**
 * Composable for i18n resolution in /weapons (ADR-0073 Tier I).
 *
 * Loads all `content/languages/<locale>.yaml` files via /api/languages,
 * caches them on first access, and exposes a `resolveKey(key)` lookup
 * plus a reactive `currentLocale` switch. Components that want to render
 * a resolved key use the `<I18nText>` SFC; programmatic callers use
 * `useI18n().resolve(key)`.
 *
 * Backend mirror: `server/routes/languages.ts`. Flatten rules match
 * the Kotlin LanguageLoader: nested maps join with `.`, lists become
 * `string[]`.
 *
 * MiniMessage handling: by default tags like `<gold>`, `<bold>`,
 * `{player}` are stripped before display. Components that want raw
 * MiniMessage can read `resolveRaw(key)` instead.
 */
import { ref, computed, type Ref } from 'vue'
import { api } from '../api'

export type LangValue = string | string[]
export type LocaleEntries = Record<string, LangValue>

const _locales = ref<string[]>([])
const _entries = ref<Record<string, LocaleEntries>>({})
const _loaded = ref<Record<string, boolean>>({})
const _loading = ref<Record<string, boolean>>({})
const _currentLocale = ref<string>('en')
let _initialized = false

async function _bootstrap(): Promise<void> {
  if (_initialized) return
  _initialized = true
  try {
    const list = await api.languages()
    _locales.value = list
    if (list.length > 0 && !list.includes(_currentLocale.value)) {
      _currentLocale.value = list[0]
    }
    // Eagerly load the current locale so first render shows resolved text.
    await ensureLocale(_currentLocale.value)
  } catch (e) {
    console.warn('[i18n] failed to load language list:', e)
  }
}

async function ensureLocale(locale: string): Promise<void> {
  if (_loaded.value[locale] || _loading.value[locale]) return
  _loading.value[locale] = true
  try {
    const payload = await api.loadLocale(locale)
    _entries.value[locale] = payload.entries
    _loaded.value[locale] = true
  } catch (e) {
    console.warn(`[i18n] failed to load locale ${locale}:`, e)
  } finally {
    _loading.value[locale] = false
  }
}

/**
 * Strip MiniMessage-ish tags (`<gold>foo</gold>` → `foo`) and Adventure
 * placeholders (`{player}` → `{player}` — kept literal for now since
 * Studio rarely has the player context to substitute).
 */
export function stripMiniMessage(s: string): string {
  return s.replace(/<\/?[A-Za-z][A-Za-z0-9_-]*(?:[^>]*)?>/g, '')
}

function _lookup(locale: string, key: string): LangValue | null {
  const map = _entries.value[locale]
  if (!map) return null
  const v = map[key]
  return v === undefined ? null : v
}

export function useI18n() {
  void _bootstrap()
  const setLocale = async (locale: string) => {
    await ensureLocale(locale)
    _currentLocale.value = locale
  }
  const resolveRaw = (key: string, locale?: string): LangValue | null =>
    _lookup(locale ?? _currentLocale.value, key)
  const resolve = (key: string, locale?: string): string | string[] | null => {
    const raw = resolveRaw(key, locale)
    if (raw === null) return null
    if (Array.isArray(raw)) return raw.map(stripMiniMessage)
    return stripMiniMessage(raw)
  }
  return {
    locales: computed(() => _locales.value),
    currentLocale: _currentLocale as Ref<string>,
    setLocale,
    resolve,
    resolveRaw,
    isLoaded: computed(() => _loaded.value[_currentLocale.value] === true),
  }
}

// For tests: reset module state to bypass the bootstrap guard.
export function __resetForTests(): void {
  _initialized = false
  _locales.value = []
  _entries.value = {}
  _loaded.value = {}
  _loading.value = {}
  _currentLocale.value = 'en'
}

// For tests: seed entries without hitting the network.
export function __seedLocaleForTests(locale: string, entries: LocaleEntries): void {
  _entries.value[locale] = entries
  _loaded.value[locale] = true
  if (!_locales.value.includes(locale)) _locales.value.push(locale)
}
