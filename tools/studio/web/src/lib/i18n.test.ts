import { test, expect, beforeEach } from 'bun:test'
import {
  __resetForTests,
  __seedLocaleForTests,
  stripMiniMessage,
  useI18n,
} from './i18n'

beforeEach(() => {
  __resetForTests()
})

test('stripMiniMessage: removes simple tags', () => {
  expect(stripMiniMessage('<gold>Astera</gold>')).toBe('Astera')
})

test('stripMiniMessage: removes nested tags', () => {
  expect(stripMiniMessage('<gold><bold>Astera</bold></gold>')).toBe('Astera')
})

test('stripMiniMessage: removes tags with attributes', () => {
  expect(stripMiniMessage('<color:red>hi</color>')).toBe('hi')
})

test('stripMiniMessage: keeps plain text untouched', () => {
  expect(stripMiniMessage('plain text {player}')).toBe('plain text {player}')
})

test('stripMiniMessage: idempotent', () => {
  const s = '<green>Astera plugin enabled</green>'
  expect(stripMiniMessage(stripMiniMessage(s))).toBe('Astera plugin enabled')
})

test('useI18n.resolve: hits seeded locale, strips MiniMessage', () => {
  __seedLocaleForTests('en', {
    'astera.system.welcome': '<gold>Welcome</gold> to Astera!',
  })
  const { resolve, setLocale } = useI18n()
  // The composable defaults to 'en' on reset; seeding 'en' makes it findable.
  void setLocale('en')
  expect(resolve('astera.system.welcome')).toBe('Welcome to Astera!')
})

test('useI18n.resolve: returns null for unknown key', () => {
  __seedLocaleForTests('en', { 'a.b.c': 'present' })
  const { resolve } = useI18n()
  expect(resolve('a.b.missing')).toBeNull()
})

test('useI18n.resolve: array value strips MiniMessage per line', () => {
  __seedLocaleForTests('en', {
    'lore.demo': ['<gray>line one</gray>', '<gray>line two</gray>'],
  })
  const { resolve } = useI18n()
  const out = resolve('lore.demo')
  expect(out).toEqual(['line one', 'line two'])
})

test('useI18n.resolveRaw: returns raw value without stripping', () => {
  __seedLocaleForTests('en', { 'k': '<gold>raw</gold>' })
  const { resolveRaw } = useI18n()
  expect(resolveRaw('k')).toBe('<gold>raw</gold>')
})

test('useI18n.resolve: explicit locale overrides current', () => {
  __seedLocaleForTests('en', { greet: 'Hello' })
  __seedLocaleForTests('ja', { greet: 'こんにちは' })
  const { resolve, setLocale } = useI18n()
  void setLocale('en')
  expect(resolve('greet')).toBe('Hello')
  expect(resolve('greet', 'ja')).toBe('こんにちは')
})

test('useI18n: locales list reflects seeded data', () => {
  __seedLocaleForTests('en', { a: '1' })
  __seedLocaleForTests('ja', { a: '一' })
  const { locales } = useI18n()
  expect(locales.value.sort()).toEqual(['en', 'ja'])
})
