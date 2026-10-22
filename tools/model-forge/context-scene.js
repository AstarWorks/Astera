// Shared geometry and display contexts for interactive and headless review.
// Base transforms: Blockbench display_mode.js; rest pose, not live Minecraft animation.
import * as THREE from '../skill-viewer/node_modules/three/build/three.module.js';
export {THREE};
export const renderer = new THREE.WebGLRenderer({antialias: true, preserveDrawingBuffer: true});
renderer.setSize(480, 320);
renderer.setPixelRatio(1);
renderer.setClearColor(0x293441);
const rad = d => d * Math.PI / 180;
function box(scene, size, pos, color) {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), new THREE.MeshLambertMaterial({color}));
    mesh.position.set(...pos);
    scene.add(mesh);
    return mesh;
}
function player(scene, left) {
    box(scene, [8,8,8], [0,28,0], 0xa99882);
    box(scene, [8,12,4], [0,18,0], 0x4e8590);
    for (const sign of [-1,1]) {
        box(scene, [4,12,4], [sign*2,6,0], 0x455671);
        const shoulder = new THREE.Group();
        shoulder.position.set(sign*6,22,0);
        shoulder.rotation.x = rad((left ? sign<0 : sign>0) ? 22.5 : 0);
        scene.add(shoulder);
        box(shoulder, [4,8,4], [0,-2,0], 0x4e8590);
        box(shoulder, [4,4,4], [0,-8,0], 0xa99882);
    }
    box(scene, [2,2,1], [0,27,-4.5], 0x7c6a58); // Facing -Z.
}
export function disposeScene(scene) {
    const materials = new Set(), textures = new Set();
    scene.traverse(o => {
        o.geometry?.dispose();
        if (o.material) for (const m of [o.material].flat()) materials.add(m);
    });
    for (const m of materials) { if (m.map) textures.add(m.map); m.dispose(); }
    for (const texture of textures) texture.dispose();
}
export async function buildScene(item, view, fov=70, aspect=1.5) {
    const scene = new THREE.Scene();
    scene.add(new THREE.AmbientLight(0xffffff, 2));
    const light = new THREE.DirectionalLight(0xffffff, 2);
    light.position.set(-20,40,40);
    scene.add(light);
    const root = new THREE.Group(), model = new THREE.Group();
    root.add(model); scene.add(root);
    const materials = {};
    for (const k of new Set(item.faces.map(f=>f.t))) {
        const url = item.textures[k];
        const tex = await new THREE.TextureLoader().loadAsync(url);
        tex.magFilter = THREE.NearestFilter; tex.minFilter = THREE.NearestFilter;
        tex.colorSpace = THREE.SRGBColorSpace;
        materials[k] = new THREE.MeshLambertMaterial({map: tex, alphaTest: .5});
    }
    for (const f of item.faces) {
        const g = new THREE.BufferGeometry();
        g.setAttribute('position', new THREE.Float32BufferAttribute(f.p.flat(),3));
        g.setAttribute('uv', new THREE.Float32BufferAttribute(f.uv.flat(),2));
        g.setIndex([0,2,1,0,3,2]); g.computeVertexNormals();
        const mesh = new THREE.Mesh(g, materials[f.t]);
        mesh.userData = {id:f.id, name:f.name, face:f.face, original:materials[f.t]};
        model.add(mesh);
    }
    const slot = view.replace(/_(side|spin)$/, '');
    const d = item.display[slot] || {rotation:[0,0,0], translation:[0,0,0], scale:[1,1,1]};
    const left = slot.includes('lefthand');
    const a = [...d.rotation], t = [...d.translation];
    if (left) { a[1]*=-1; a[2]*=-1; t[0]*=-1; }
    model.rotation.set(...a.map(rad)); model.position.set(...t); model.scale.set(...d.scale);
    let camera = new THREE.PerspectiveCamera(fov, aspect, .1, 500);
    const target = new THREE.Vector3();
    if (slot === 'model') {
        const bounds = new THREE.Box3().setFromObject(model);
        bounds.getCenter(target);
        const radius = bounds.getSize(new THREE.Vector3()).length() * 1.1;
        camera.position.copy(target).add(new THREE.Vector3(.5,.3,1).normalize().multiplyScalar(radius));
    } else if (slot.startsWith('firstperson')) {
        root.position.set(left?-9.039:9.039,15.682,20.8);
        camera.position.set(0,24,32.4); target.set(0,24,0);
    } else if (slot.startsWith('thirdperson')) {
        player(scene,left);
        const ang = rad(22.5);
        root.position.set(left?-6:6,22-Math.cos(ang)*10+Math.sin(ang)*2,-Math.sin(ang)*10-Math.cos(ang)*2);
        root.rotation.x = rad(-67.5); camera.fov = 42;
        camera.position.set(...(view.endsWith('_side') ? [48,22,0] : [-44,40,-44])); target.set(0,14,0);
    } else if (slot === 'ground') {
        root.position.set(0,4,0);
        if (view.endsWith('_spin')) root.rotation.y = Math.PI/2;
        box(scene,[16,.2,16],[0,-.1,0],0x566a4a);
        camera.fov = 42; camera.position.set(-24,21,-24); target.set(0,4,0);
    } else if (slot === 'fixed') {
        root.scale.setScalar(.5); root.position.z = -1;
        box(scene,[12,12,1],[0,0,.5],0x765139); box(scene,[10,10,.2],[0,0,-.1],0x493e2e);
        camera.fov = 42; camera.position.set(0,0,-22);
    } else {
        camera = new THREE.OrthographicCamera(-8*aspect,8*aspect,8,-8,.1,100);
        camera.position.z = 40;
    }
    camera.lookAt(target); camera.updateProjectionMatrix(); scene.updateMatrixWorld(true);
    return {scene, model, root, camera, target};
}
// Keep the original headless command compatible. Interactive snapshots embed the same data.
window.ready = window.MODEL_DATA ? Promise.resolve(window.MODEL_DATA) : fetch('/data.json').then(r=>r.json());
window.ready = window.ready.then(data => window.items = data);
window.draw = async (name, view, fov=70) => {
    await window.ready;
    const state = await buildScene(window.items[name], view, fov);
    if (!renderer.domElement.isConnected) document.body.append(renderer.domElement);
    renderer.render(state.scene,state.camera);
    const result = renderer.domElement.toDataURL();
    disposeScene(state.scene);
    return result;
};
