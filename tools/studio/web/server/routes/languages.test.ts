import { test, expect } from 'bun:test'
import { flatten } from './languages'

test('flatten: simple flat map', () => {
  const input = { hello: 'world', foo: 'bar' }
  expect(flatten('', input)).toEqual({ hello: 'world', foo: 'bar' })
})

test('flatten: nested map joins with dots', () => {
  const input = {
    astera: {
      system: {
        welcome: 'Welcome to <gold>Astera</gold>, {player}!',
      },
    },
  }
  expect(flatten('', input)).toEqual({
    'astera.system.welcome': 'Welcome to <gold>Astera</gold>, {player}!',
  })
})

test('flatten: arrays become string[] leaves (matches Kotlin LanguageLoader)', () => {
  const input = {
    astera: {
      weapon: {
        sword: {
          lore: ['First line', 'Second line'],
        },
      },
    },
  }
  const out = flatten('', input)
  expect(out['astera.weapon.sword.lore']).toEqual(['First line', 'Second line'])
})

test('flatten: deeply nested + sibling keys', () => {
  const input = {
    astera: {
      command: {
        give: {
          success: 'Gave {weapon} to {player}',
          not_found: 'Weapon {weapon} not found',
        },
        'player-only': 'Player-only command',
      },
    },
  }
  expect(flatten('', input)).toEqual({
    'astera.command.give.success': 'Gave {weapon} to {player}',
    'astera.command.give.not_found': 'Weapon {weapon} not found',
    'astera.command.player-only': 'Player-only command',
  })
})

test('flatten: null/undefined values are dropped', () => {
  const input = {
    a: 'one',
    b: null,
    c: undefined,
    d: { e: 'four' },
  }
  expect(flatten('', input)).toEqual({ a: 'one', 'd.e': 'four' })
})

test('flatten: number/boolean values are stringified', () => {
  const input = { count: 42, active: true }
  expect(flatten('', input)).toEqual({ count: '42', active: 'true' })
})

test('flatten: numeric/boolean array items are stringified', () => {
  const input = { mixed: [1, 'two', true] }
  expect(flatten('', input)).toEqual({ mixed: ['1', 'two', 'true'] })
})

test('flatten: arrays at top level work', () => {
  const input = { lore: ['a', 'b'] }
  expect(flatten('', input)).toEqual({ lore: ['a', 'b'] })
})

test('flatten: empty object yields empty map', () => {
  expect(flatten('', {})).toEqual({})
})

test('flatten: prefix is honored when seeded externally', () => {
  expect(flatten('prefix', { foo: 'bar' })).toEqual({ 'prefix.foo': 'bar' })
})
