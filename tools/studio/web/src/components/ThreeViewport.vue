<script setup lang="ts">
import { markRaw, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { mcModelToThree, disposeGroup } from '../three/mcmodel-to-three'
import { setRenderTrigger } from '../three/texture-resolver'
import { pickFace } from '../three/face-picker'
import type { McModel, FaceName, SelectedFace } from '../types'

const props = defineProps<{
  model: McModel | null
  vanillaVersion: string | null
  selectedFace: SelectedFace | null
}>()

const emit = defineEmits<{
  'update:selectedFace': [value: SelectedFace | null]
}>()

const canvasContainer = ref<HTMLDivElement | null>(null)

type CameraPreset = 'iso' | 'front' | 'top' | 'side' | 'item_gui'

const CAMERA_PRESETS: Record<
  CameraPreset,
  { position: [number, number, number]; target: [number, number, number]; fov?: number }
> = {
  iso: { position: [24, 20, 32], target: [8, 8, 8] },
  front: { position: [8, 8, 36], target: [8, 8, 8] },
  side: { position: [36, 8, 8], target: [8, 8, 8] },
  top: { position: [8, 36, 8.01], target: [8, 8, 8] },
  item_gui: { position: [8, 8, 32], target: [8, 8, 8], fov: 25 },
}

const FACE_NORMAL: Record<FaceName, [number, number, number]> = {
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
  south: [0, 0, 1],
  north: [0, 0, -1],
}

// --- Three.js objects: markRaw to keep Vue's reactive proxy out ---
// (proxying a WebGLRenderer or Scene breaks .traverse() and explodes the
// dependency graph at every property access.)
const scene = markRaw(new THREE.Scene())
scene.background = new THREE.Color(0x0e0e10)

const camera = markRaw(new THREE.PerspectiveCamera(45, 1, 0.1, 1000))
camera.position.set(24, 20, 32)
camera.lookAt(8, 8, 8)

// MC textures already encode the look we want; lighting just adds noise on
// top of pixel-art. We use MeshBasicMaterial in merge-geometry.ts and have
// no lights in the scene. (Adding ambient/directional lights is a Wave-2-era
// remnant that the audit told us to drop.)

const grid = markRaw(new THREE.GridHelper(32, 32, 0x4b5563, 0x2a2a2f))
grid.position.set(8, 0, 8)
scene.add(grid)

const axes = markRaw(new THREE.AxesHelper(20))
axes.position.set(0, 0.01, 0)
scene.add(axes)

// Component-local plain refs (no shallowRef — we never read them in templates,
// just need to retain references across handlers).
let renderer: THREE.WebGLRenderer | null = null
let controls: OrbitControls | null = null
let modelGroup: THREE.Group | null = null
let highlightLines: THREE.LineSegments | null = null
let highlightFill: THREE.Mesh | null = null
let resizeObserver: ResizeObserver | null = null
let renderRequested = false
let resizePending = false

// --- Render-on-demand --------------------------------------------------------
// The previous always-on RAF loop was the dominant viewport-cost driver
// (~60 fps × full-screen rasterization × DPR² even on a static scene). We now
// re-render only when the user moves the camera, the model changes, a texture
// finishes loading, or the canvas resizes. OrbitControls fires "change"
// continuously while damping inertia settles, so drag-then-release tail also
// gets covered automatically.

function requestRender() {
  if (renderRequested) return
  renderRequested = true
  requestAnimationFrame(() => {
    renderRequested = false
    if (!renderer) return
    resizeIfNeeded()
    controls?.update()
    renderer.render(scene, camera)
  })
}

function resizeIfNeeded(): boolean {
  const el = canvasContainer.value
  if (!el || !renderer) return false
  const w = el.clientWidth
  const h = el.clientHeight
  if (w === 0 || h === 0) return false
  const canvas = renderer.domElement
  const needs = canvas.width !== Math.floor(w * renderer.getPixelRatio())
    || canvas.height !== Math.floor(h * renderer.getPixelRatio())
  if (needs) {
    renderer.setSize(w, h, false)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    return true
  }
  return false
}

function onResize() {
  if (resizePending) return
  resizePending = true
  requestAnimationFrame(() => {
    resizePending = false
    if (resizeIfNeeded()) requestRender()
  })
}

// --- Screenshot --------------------------------------------------------------
// Synchronous render → toDataURL → camera restore, all in one tick. This is
// why we don't need preserveDrawingBuffer: true (the spec preserves the
// drawing buffer inside the same task on most browsers, and the rest don't
// matter for us).
//
// Returns the camera snapshot alongside the PNG so the server can map
// part bboxes to pixel coords for `crop_model_view` (B9 / ADR-0062 §5)
// without re-rendering.

export type CameraSnapshot = {
  view_id: string
  origin: [number, number, number]
  target: [number, number, number]
  up: [number, number, number]
  fov_deg: number
  width: number
  height: number
}

export type ScreenshotPayload = {
  png_base64: string
  camera: CameraSnapshot
}

function takeScreenshot(preset: CameraPreset = 'iso'): ScreenshotPayload {
  const empty: ScreenshotPayload = {
    png_base64: '',
    camera: {
      view_id: preset,
      origin: [0, 0, 0],
      target: [0, 0, 0],
      up: [0, 1, 0],
      fov_deg: 45,
      width: 0,
      height: 0,
    },
  }
  if (!renderer || !controls) return empty
  const view = CAMERA_PRESETS[preset]
  const savedPos = camera.position.clone()
  const savedTarget = controls.target.clone()
  const savedFov = camera.fov
  camera.position.set(...view.position)
  controls.target.set(...view.target)
  if (view.fov) camera.fov = view.fov
  camera.updateProjectionMatrix()
  controls.update()
  renderer.render(scene, camera)
  const canvas = renderer.domElement
  const snapshot: CameraSnapshot = {
    view_id: preset,
    origin: [camera.position.x, camera.position.y, camera.position.z],
    target: [controls.target.x, controls.target.y, controls.target.z],
    up: [camera.up.x, camera.up.y, camera.up.z],
    fov_deg: camera.fov,
    width: canvas.width,
    height: canvas.height,
  }
  const dataUrl = canvas.toDataURL('image/png')
  camera.position.copy(savedPos)
  controls.target.copy(savedTarget)
  camera.fov = savedFov
  camera.updateProjectionMatrix()
  controls.update()
  requestRender() // restore visible state
  return {
    png_base64: dataUrl.replace(/^data:image\/png;base64,/, ''),
    camera: snapshot,
  }
}

defineExpose({ takeScreenshot })

// --- Model + highlight rebuilds ---------------------------------------------

function rebuildModel(model: McModel | null) {
  if (modelGroup) {
    scene.remove(modelGroup)
    disposeGroup(modelGroup)
    modelGroup = null
  }
  if (model) {
    const g = mcModelToThree(model, { vanillaVersion: props.vanillaVersion })
    scene.add(g)
    modelGroup = g
  }
  refreshHighlight()
  requestRender()
}

function clearHighlight() {
  if (highlightLines) {
    scene.remove(highlightLines)
    highlightLines.geometry.dispose()
    ;(highlightLines.material as THREE.Material).dispose()
    highlightLines = null
  }
  if (highlightFill) {
    scene.remove(highlightFill)
    highlightFill.geometry.dispose()
    ;(highlightFill.material as THREE.Material).dispose()
    highlightFill = null
  }
}

function refreshHighlight() {
  clearHighlight()
  const sel = props.selectedFace
  const model = props.model
  if (!sel || !model || !model.elements) return
  const el = model.elements[sel.elementIndex]
  if (!el) return
  const [x1, y1, z1] = el.from
  const [x2, y2, z2] = el.to
  const f = sel.face
  const corners: THREE.Vector3[] = []
  if (f === 'east' || f === 'west') {
    const x = f === 'east' ? x2 : x1
    corners.push(
      new THREE.Vector3(x, y1, z1),
      new THREE.Vector3(x, y1, z2),
      new THREE.Vector3(x, y2, z2),
      new THREE.Vector3(x, y2, z1),
    )
  } else if (f === 'up' || f === 'down') {
    const y = f === 'up' ? y2 : y1
    corners.push(
      new THREE.Vector3(x1, y, z1),
      new THREE.Vector3(x2, y, z1),
      new THREE.Vector3(x2, y, z2),
      new THREE.Vector3(x1, y, z2),
    )
  } else {
    const z = f === 'south' ? z2 : z1
    corners.push(
      new THREE.Vector3(x1, y1, z),
      new THREE.Vector3(x2, y1, z),
      new THREE.Vector3(x2, y2, z),
      new THREE.Vector3(x1, y2, z),
    )
  }
  if (el.rotation && el.rotation.angle !== 0) {
    const origin = new THREE.Vector3(...el.rotation.origin)
    const axis = new THREE.Vector3(
      el.rotation.axis === 'x' ? 1 : 0,
      el.rotation.axis === 'y' ? 1 : 0,
      el.rotation.axis === 'z' ? 1 : 0,
    )
    const angleRad = (el.rotation.angle * Math.PI) / 180
    for (const c of corners) c.sub(origin).applyAxisAngle(axis, angleRad).add(origin)
  }
  const verts: number[] = []
  for (let i = 0; i < 4; i++) {
    const a = corners[i]!
    const b = corners[(i + 1) % 4]!
    verts.push(a.x, a.y, a.z, b.x, b.y, b.z)
  }
  const lineGeom = new THREE.BufferGeometry()
  lineGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(verts), 3))
  const lineMat = new THREE.LineBasicMaterial({
    color: 0xfacc15,
    depthTest: false,
    transparent: true,
    opacity: 1,
  })
  highlightLines = markRaw(new THREE.LineSegments(lineGeom, lineMat))
  highlightLines.renderOrder = 999
  scene.add(highlightLines)

  const eps = 0.01
  const normal = new THREE.Vector3(...FACE_NORMAL[f]).multiplyScalar(eps)
  const triVerts: number[] = []
  const cs = corners.map((c) => c.clone().add(normal))
  for (const idx of [0, 1, 2, 0, 2, 3]) {
    const c = cs[idx]!
    triVerts.push(c.x, c.y, c.z)
  }
  const fillGeom = new THREE.BufferGeometry()
  fillGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(triVerts), 3))
  fillGeom.computeVertexNormals()
  const fillMat = new THREE.MeshBasicMaterial({
    color: 0xfacc15,
    transparent: true,
    opacity: 0.28,
    depthTest: false,
    side: THREE.DoubleSide,
  })
  highlightFill = markRaw(new THREE.Mesh(fillGeom, fillMat))
  highlightFill.renderOrder = 998
  scene.add(highlightFill)
}

// --- Click handling ----------------------------------------------------------

function onPointerDown(ev: MouseEvent) {
  if (ev.button !== 0) return
  const startX = ev.clientX
  const startY = ev.clientY
  const onUp = (up: MouseEvent) => {
    canvasContainer.value?.removeEventListener('pointerup', onUp)
    const dx = up.clientX - startX
    const dy = up.clientY - startY
    if (dx * dx + dy * dy > 16) return
    if (!modelGroup || !canvasContainer.value) return
    const picked = pickFace(up, canvasContainer.value, camera, modelGroup)
    if (picked) {
      emit('update:selectedFace', {
        elementIndex: picked.elementIndex,
        face: picked.face,
      })
    } else {
      emit('update:selectedFace', null)
    }
  }
  canvasContainer.value?.addEventListener('pointerup', onUp)
}

// --- Lifecycle ---------------------------------------------------------------

onMounted(() => {
  if (!canvasContainer.value) return
  renderer = markRaw(
    new THREE.WebGLRenderer({
      antialias: true,
      alpha: false,
      stencil: false,
      depth: true,
      preserveDrawingBuffer: false,
      powerPreference: 'high-performance',
    }),
  )
  // DPR cap at 1.5: NearestFilter pixel-art doesn't benefit from DPR=2/3
  // (one source pixel just covers more screen pixels at higher fill cost).
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
  canvasContainer.value.appendChild(renderer.domElement)

  controls = markRaw(new OrbitControls(camera, renderer.domElement))
  controls.target.set(8, 8, 8)
  controls.enableDamping = true
  controls.update()
  controls.addEventListener('change', requestRender)

  // Texture loads trigger a re-render so the model paints in when assets arrive.
  setRenderTrigger(requestRender)

  rebuildModel(props.model)
  resizeIfNeeded()
  requestRender()

  resizeObserver = new ResizeObserver(onResize)
  resizeObserver.observe(canvasContainer.value)
  canvasContainer.value.addEventListener('pointerdown', onPointerDown)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  canvasContainer.value?.removeEventListener('pointerdown', onPointerDown)
  controls?.removeEventListener('change', requestRender)
  setRenderTrigger(null)
  clearHighlight()
  if (modelGroup) {
    scene.remove(modelGroup)
    disposeGroup(modelGroup)
  }
  controls?.dispose()
  if (renderer) {
    renderer.dispose()
    renderer.domElement.parentElement?.removeChild(renderer.domElement)
  }
})

// HMR: drop cached materials/textures so module reloads don't leak GL contexts.
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    setRenderTrigger(null)
  })
}

watch(() => props.model, rebuildModel)
watch(() => props.vanillaVersion, () => rebuildModel(props.model))
watch(() => props.selectedFace, () => {
  refreshHighlight()
  requestRender()
})
</script>

<template>
  <div ref="canvasContainer" class="viewport"></div>
</template>

<style scoped>
.viewport {
  width: 100%;
  height: 100%;
  background: #0e0e10;
  position: relative;
}
.viewport :deep(canvas) {
  display: block;
  width: 100% !important;
  height: 100% !important;
}
</style>
