/**
 * Common envelope for ASDSL endpoints. All mutations return either a truth
 * envelope (from utils/models/asdsl/state.ts) on success, or this MISSING_ARGS
 * envelope on missing required body fields.
 */
export function missingArgs(hintText?: string) {
  return {
    ok: false as const,
    status: 'ERROR' as const,
    error_code: 'MISSING_ARGS' as const,
    hints: hintText ? [{ text: hintText }] : [],
    changes: {},
  }
}
