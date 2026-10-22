import {THREE, renderer, buildScene, disposeScene} from './context-scene.js';
import {OrbitControls} from '../skill-viewer/node_modules/three/examples/jsm/controls/OrbitControls.js';
const $ = id => document.getElementById(id);
const viewport = $('viewport');
viewport.append(renderer.domElement);
const raycaster = new THREE.Raycaster();
let state, controls, itemName, context, radius, selected=[], hidden=new Set(), mode='textured', loadVersion=0, preferredFov=70;
const clay = new THREE.MeshLambertMaterial({color:0xbac5d2});
const wire = new THREE.MeshBasicMaterial({color:0xadcaf7,wireframe:true});
const xray = new THREE.MeshBasicMaterial({color:0xa5bbd6,transparent:true,opacity:.15,depthWrite:false,side:THREE.DoubleSide});
const highlights = [0xffa83b,0x44deeb].map(color=>new THREE.MeshBasicMaterial({color,side:THREE.DoubleSide}));
function resize() {
    if (!state) return;
    const w=viewport.clientWidth, h=viewport.clientHeight;
    renderer.setSize(w,h);
    if (state.camera.isPerspectiveCamera) state.camera.aspect=w/h;
    else {state.camera.left=-8*w/h; state.camera.right=8*w/h;}
    state.camera.updateProjectionMatrix();
}
function paint() {
    for (const mesh of state.model.children) {
        mesh.visible=!hidden.has(mesh.userData.id);
        const index=selected.findIndex(s=>s.id===mesh.userData.id && (!s.face || s.face===mesh.userData.face));
        mesh.material=index>=0 ? highlights[index%2] : mode==='wireframe' ? wire : mode==='clay' ? clay : mode==='xray' ? xray : mesh.userData.original;
    }
}
function choose(value) {
    if(value.some(s=>!window.items[itemName].faces.some(f=>f.id===s.id && (!s.face||f.face===s.face)))) throw new Error('Unknown element/face');
    selected=value;
    paint();
    $('selection').textContent=value.length ? value.map(s=>`${s.id} / ${s.face || 'all faces'}`).join(' ↔ ') : '面をクリックすると部品を選択できます。';
    $('element').value=value.length===1 ? value[0].id : '';
}
function frameSelection() {
    const box=new THREE.Box3();
    for(const mesh of state.model.children) if(mesh.visible && (!selected.length || selected.some(s=>s.id===mesh.userData.id && (!s.face||s.face===mesh.userData.face)))) box.expandByObject(mesh);
    if(box.isEmpty()) return;
    const target=box.getCenter(new THREE.Vector3());
    radius=Math.max(1,box.getSize(new THREE.Vector3()).length()*1.1);
    const direction=state.camera.position.clone().sub(controls.target).normalize();
    controls.target.copy(target);state.camera.position.copy(target).addScaledVector(direction,radius);
    if(state.camera.isOrthographicCamera) {state.camera.zoom=16/Math.max(1,box.getSize(new THREE.Vector3()).length());state.camera.updateProjectionMatrix();}
    controls.update();
}
function setCamera({yaw=0,pitch=0,zoom=1,target}={}) {
    if (![yaw,pitch,zoom].every(Number.isFinite) || zoom<=0 || pitch < -90 || pitch > 90) throw new Error('Invalid camera');
    if(target) controls.target.fromArray(target);
    const y=THREE.MathUtils.degToRad(yaw),p=THREE.MathUtils.degToRad(pitch);
    const offset=new THREE.Vector3(Math.sin(y)*Math.cos(p),Math.sin(p),Math.cos(y)*Math.cos(p));
    state.camera.up.set(0,1,0);
    // OrbitControls clamps exact poles by an epsilon; these are near-exact top/bottom views.
    state.camera.position.copy(controls.target).addScaledVector(offset,radius/zoom);
    if(state.camera.isOrthographicCamera) {state.camera.zoom=zoom;state.camera.updateProjectionMatrix();}
    controls.update();
}
function setMode(value) {
    if(!['textured','clay','wireframe','xray'].includes(value)) throw new Error('Unknown render mode');
    mode=value;$('mode').value=value;paint();
}
function pairsList(item) {
    const exact=item.pairs.filter(p=>p.kind==='coplanar');
    $('counts').textContent=`同一平面 ${exact.length}組 / 近接 ${item.pairs.length-exact.length}組`;
    const fragment=document.createDocumentFragment();
    for(const p of item.pairs) {
        const button=document.createElement('button');
        button.className='pair';button.textContent=`${p.kind==='coplanar'?'同一平面':'近接'} ${p.gap} · ${p.a.join('/')} ↔ ${p.b.join('/')}`;
        button.onclick=()=>{choose([{id:p.a[0],face:p.a[1]},{id:p.b[0],face:p.b[1]}]);frameSelection();};
        fragment.append(button);
    }
    $('pairs').replaceChildren(fragment);
}
async function load(name, slot='model') {
    const item=window.items[name];if(!item)throw new Error('Unknown item '+name);
    if(slot!=='model' && !item.display[slot.replace(/_(side|spin)$/,'')])throw new Error('Unknown display context');
    const version=++loadVersion;
    $('status').textContent='読み込み中…';
    const next=await buildScene(item,slot,preferredFov,viewport.clientWidth/viewport.clientHeight);
    if(version!==loadVersion){disposeScene(next.scene);return;}
    if(state) {
        controls.dispose();
        // Restore shared texture materials so disposal includes maps and excludes reusable diagnostic materials.
        for(const mesh of state.model.children)mesh.material=mesh.userData.original;
        disposeScene(state.scene);
    }
    state=next;itemName=name;context=slot;selected=[];hidden.clear();
    $('fov').disabled=!state.camera.isPerspectiveCamera;
    $('fov').value=state.camera.fov||preferredFov;
    $('item').value=name;$('context').value=slot;
    controls=new OrbitControls(state.camera,renderer.domElement);
    controls.target.copy(state.target);controls.enableDamping=false;controls.minDistance=.2;controls.maxDistance=400;
    radius=state.camera.position.distanceTo(state.target);controls.update();
    const elements=new Map(item.faces.map(f=>[f.id,f.name]));
    $('element').replaceChildren(new Option('部品を選択…',''),...Array.from(elements,([id,name])=>new Option(`${id} · ${name}`,id)));
    $('source').textContent=`${item.source} · SHA256 ${item.sha256.slice(0,12)} · ${elements.size}部品`;
    $('selection').textContent='面をクリックすると部品を選択できます。';
    pairsList(item);paint();resize();$('status').textContent='ドラッグ: 回転 · ホイール: 拡大 · 右ドラッグ: 移動';
}
function capture() {
    renderer.render(state.scene,state.camera);
    return {png:renderer.domElement.toDataURL(),metadata:{item:itemName,source:window.items[itemName].source,sha256:window.items[itemName].sha256,context,mode,
        camera:{position:state.camera.position.toArray(),target:controls.target.toArray(),up:state.camera.up.toArray(),zoom:state.camera.zoom,fov:state.camera.fov},
        selected,hidden:[...hidden],size:renderer.getSize(new THREE.Vector2()).toArray()}};
}
function download(content,name){const a=document.createElement('a');a.href=content;a.download=name;a.click();}
window.review={load,setCamera,setMode,select:choose,frameSelection,capture,
    hide(ids){for(const id of ids)hidden.add(id);paint();},
    showAll(){hidden.clear();paint();},
    state:()=>capture().metadata,
    async restore(saved) {
        if(window.items[saved.item]?.sha256!==saved.sha256)throw new Error('Asset hash differs from saved capture');
        await load(saved.item,saved.context);setMode(saved.mode);choose(saved.selected);hidden=new Set(saved.hidden);paint();
        state.camera.position.fromArray(saved.camera.position);state.camera.up.fromArray(saved.camera.up);
        state.camera.zoom=saved.camera.zoom;
        if(state.camera.isPerspectiveCamera)state.camera.fov=saved.camera.fov;
        controls.target.fromArray(saved.camera.target);state.camera.updateProjectionMatrix();controls.update();
    },
    setFov(value){if(!Number.isFinite(value)||value<30||value>110)throw new Error('FOV must be 30..110');preferredFov=value;$('fov').value=value;if(state.camera.isPerspectiveCamera){state.camera.fov=value;state.camera.updateProjectionMatrix();}}
};
window.review.ready=window.ready.then(async items=>{
    $('item').replaceChildren(...Object.keys(items).map(n=>new Option(n,n)));
    await load(Object.keys(items)[0]);
    renderer.setAnimationLoop(()=>{if(state)renderer.render(state.scene,state.camera);});
});
$('item').onchange=()=>load($('item').value,$('context').value);
$('context').onchange=()=>load($('item').value,$('context').value);
$('mode').onchange=()=>setMode($('mode').value);
$('element').onchange=()=>choose($('element').value?[{id:$('element').value}]:[]);
$('fov').oninput=()=>window.review.setFov(Number($('fov').value));
$('frame').onclick=frameSelection;
$('reset').onclick=()=>load(itemName,context);
$('hide').onclick=()=>window.review.hide(selected.map(s=>s.id));
$('isolate').onclick=()=>{if(!selected.length)return;hidden=new Set(window.items[itemName].faces.map(f=>f.id).filter(id=>!selected.some(s=>s.id===id)));paint();frameSelection();};
$('show').onclick=()=>{hidden.clear();choose([]);};
$('save').onclick=()=>download(capture().png,`${itemName}-${context}.png`);
$('save-camera').onclick=()=>download('data:application/json;charset=utf-8,'+encodeURIComponent(JSON.stringify(capture().metadata,null,2)),`${itemName}-${context}.json`);
for(const b of document.querySelectorAll('[data-yaw]'))b.onclick=()=>setCamera({yaw:Number(b.dataset.yaw),pitch:Number(b.dataset.pitch)});
let down;
renderer.domElement.addEventListener('pointerdown',e=>down=[e.clientX,e.clientY]);
renderer.domElement.addEventListener('pointerup',e=>{
    if(!state||!down||Math.hypot(e.clientX-down[0],e.clientY-down[1])>4)return;
    const r=renderer.domElement.getBoundingClientRect();
    raycaster.setFromCamera(new THREE.Vector2((e.clientX-r.left)/r.width*2-1,-(e.clientY-r.top)/r.height*2+1),state.camera);
    const hit=raycaster.intersectObjects(state.model.children).find(h=>h.object.visible);
    if(hit)choose([{id:hit.object.userData.id,face:hit.object.userData.face}]);else choose([]);
});
new ResizeObserver(resize).observe(viewport);

window.addEventListener('unhandledrejection',event=>{$('status').textContent='表示エラー: '+event.reason;});
