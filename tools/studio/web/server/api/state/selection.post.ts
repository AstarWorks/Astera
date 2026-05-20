import { setSelectedFace } from '../../utils/models/model-state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    selectedFace?: { elementIndex: number; face: string } | null
  }
  setSelectedFace(
    body.selectedFace
      ? {
          elementIndex: body.selectedFace.elementIndex,
          face: body.selectedFace.face as
            | 'east' | 'west' | 'up' | 'down' | 'south' | 'north',
        }
      : null,
  )
  return { ok: true }
})
