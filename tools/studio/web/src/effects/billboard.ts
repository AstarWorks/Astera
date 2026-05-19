/**
 * Mini Three.js scene that mocks the in-game hologram look: a face-camera
 * quad textured by the live OffscreenCanvas, optionally accompanied by a
 * grid floor + wireframe player silhouette as scale references.
 *
 * Default state: helpers HIDDEN. The hologram quad floats against a dark
 * void — the cleaner look the user requested. Call `setHelpers(true)` to
 * bring back grid + player for scale-checking.
 *
 * Mirrors Bukkit ItemDisplay/BillboardMode.CENTER (always faces the camera).
 */
import * as THREE from 'three'

export type BillboardScene = {
  renderer: THREE.WebGLRenderer
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  texture: THREE.CanvasTexture
  setSource(canvas: OffscreenCanvas | HTMLCanvasElement): void
  setHelpers(visible: boolean): void
  resize(w: number, h: number): void
  render(): void
  dispose(): void
}

export function mountBillboard(
  container: HTMLElement,
  initialSource: OffscreenCanvas | HTMLCanvasElement,
): BillboardScene {
  const w = container.clientWidth
  const h = container.clientHeight

  const renderer = new THREE.WebGLRenderer({
    antialias: false,
    alpha: true,
    premultipliedAlpha: true,
  })
  renderer.setPixelRatio(window.devicePixelRatio)
  renderer.setSize(w, h, false)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  container.appendChild(renderer.domElement)

  const scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0e0e10)

  // Re-frame camera: with helpers gone the quad is now the focal point. Pull
  // back slightly and center on the hologram.
  const camera = new THREE.PerspectiveCamera(28, w / h, 0.1, 100)
  camera.position.set(0, 1.4, 4.6)
  camera.lookAt(0, 1.2, 0)

  // ── Helpers (off by default) ─────────────────────────────────────────────
  const helperGroup = new THREE.Group()
  helperGroup.visible = false

  const grid = new THREE.GridHelper(8, 16, 0x3b82f6, 0x2a2a2f)
  ;(grid.material as THREE.LineBasicMaterial).transparent = true
  ;(grid.material as THREE.LineBasicMaterial).opacity = 0.6
  helperGroup.add(grid)

  const playerGeom = new THREE.BoxGeometry(0.6, 1.8, 0.3)
  const playerMat = new THREE.LineBasicMaterial({
    color: 0x3b82f6,
    transparent: true,
    opacity: 0.35,
  })
  const playerEdges = new THREE.LineSegments(
    new THREE.EdgesGeometry(playerGeom),
    playerMat,
  )
  playerEdges.position.set(-1.4, 0.9, -0.2)
  helperGroup.add(playerEdges)
  const headGeom = new THREE.BoxGeometry(0.35, 0.35, 0.35)
  const headEdges = new THREE.LineSegments(
    new THREE.EdgesGeometry(headGeom),
    playerMat,
  )
  headEdges.position.set(-1.4, 2.0, -0.2)
  helperGroup.add(headEdges)
  scene.add(helperGroup)

  // ── Hologram quad ────────────────────────────────────────────────────────
  const texture = new THREE.CanvasTexture(
    initialSource as unknown as HTMLCanvasElement,
  )
  texture.minFilter = THREE.NearestFilter
  texture.magFilter = THREE.NearestFilter
  texture.wrapS = THREE.ClampToEdgeWrapping
  texture.wrapT = THREE.ClampToEdgeWrapping
  texture.colorSpace = THREE.SRGBColorSpace

  const quadGeom = new THREE.PlaneGeometry(1.6, 1.6)
  const quadMat = new THREE.MeshBasicMaterial({
    map: texture,
    transparent: true,
    side: THREE.DoubleSide,
    depthWrite: false,
  })
  const quad = new THREE.Mesh(quadGeom, quadMat)
  quad.position.set(0, 1.2, 0)
  scene.add(quad)

  scene.add(new THREE.AmbientLight(0xffffff, 0.6))

  function setSource(canvas: OffscreenCanvas | HTMLCanvasElement) {
    ;(texture as unknown as { image: TexImageSource }).image = canvas
    texture.needsUpdate = true
  }

  function setHelpers(visible: boolean) {
    helperGroup.visible = visible
  }

  function resize(nw: number, nh: number) {
    renderer.setSize(nw, nh, false)
    camera.aspect = nw / nh
    camera.updateProjectionMatrix()
  }

  function render() {
    quad.lookAt(camera.position)
    texture.needsUpdate = true
    renderer.render(scene, camera)
  }

  function dispose() {
    renderer.dispose()
    quadGeom.dispose()
    quadMat.dispose()
    texture.dispose()
    playerGeom.dispose()
    headGeom.dispose()
    container.removeChild(renderer.domElement)
  }

  return {
    renderer,
    scene,
    camera,
    texture,
    setSource,
    setHelpers,
    resize,
    render,
    dispose,
  }
}
