// Renders world-space textured quads from handover_render.py (scene.json) with three.js in headless Chromium.
import {chromium} from '../../../skill-viewer/node_modules/playwright-core/index.mjs';
import {readFile, writeFile} from 'node:fs/promises';
const [scenePath, out] = process.argv.slice(2);
const threeUrl = new URL('../../../skill-viewer/node_modules/three/build/three.module.js', import.meta.url);
const app = `
import * as THREE from '/three.js';
const renderer = new THREE.WebGLRenderer({antialias: true, preserveDrawingBuffer: true});
renderer.setSize(480, 270); renderer.setPixelRatio(1); renderer.setClearColor(0x8fb4e8);
document.body.append(renderer.domElement);
const data = await (await fetch('/scene.json')).json();
const mats = {};
for (const [k, uri] of Object.entries(data.textures)) {
  const t = await new THREE.TextureLoader().loadAsync(uri);
  t.magFilter = t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.SRGBColorSpace;
  mats[k] = new THREE.MeshLambertMaterial({map: t, alphaTest: .5, side: THREE.DoubleSide});
}
window.draw = (i) => {
  const v = data.views[i]; const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 1.6));
  const l = new THREE.DirectionalLight(0xffffff, 1.6); l.position.set(-2, 5, 3); scene.add(l);
  for (const q of v.quads) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(q.p.flat(), 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(q.uv.flat(), 2));
    g.setIndex([0,2,1,0,3,2]); g.computeVertexNormals();
    scene.add(new THREE.Mesh(g, q.t ? mats[q.t] : new THREE.MeshLambertMaterial({color: q.c, side: THREE.DoubleSide})));
  }
  const c = v.camera; const cam = new THREE.PerspectiveCamera(c.fov, 16/9, .03, 100);
  cam.position.set(...c.pos); cam.lookAt(new THREE.Vector3(...c.target)); cam.updateProjectionMatrix();
  renderer.render(scene, cam);
  const url = renderer.domElement.toDataURL();
  scene.traverse(o => o.geometry?.dispose());
  return url;
};
window.ready = true;`;
const server = Bun.serve({hostname: '127.0.0.1', port: 0, fetch(req) {
  const p = new URL(req.url).pathname;
  if (p === '/scene.json') return new Response(Bun.file(scenePath), {headers: {'Content-Type': 'application/json'}});
  if (p === '/three.js') return new Response(Bun.file(threeUrl), {headers: {'Content-Type': 'text/javascript'}});
  if (p === '/app.js') return new Response(app, {headers: {'Content-Type': 'text/javascript'}});
  return new Response('<style>body{margin:0}</style><script type="module" src="/app.js"></script>', {headers: {'Content-Type': 'text/html'}});
}});
let browser;
try {
  browser = await chromium.launch({executablePath: process.env.CHROMIUM_EXECUTABLE || '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  const page = await browser.newPage({viewport: {width: 480, height: 270}});
  page.on('pageerror', e => console.error(e));
  await page.goto('http://127.0.0.1:' + server.port); await page.waitForFunction(() => window.ready, null, {timeout: 60000});
  const scene = JSON.parse(await readFile(scenePath, 'utf8'));
  for (let i = 0; i < scene.views.length; i++) {
    const url = await page.evaluate(i => window.draw(i), i);
    await writeFile(out + '/' + scene.views[i].name + '.png', Buffer.from(url.split(',')[1], 'base64'));
  }
} finally { if (browser) await browser.close(); server.stop(); }
