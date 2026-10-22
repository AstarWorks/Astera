// Blockbench-compatible base transforms; illustrative rest-pose preview only.
import * as THREE from '/three.js';
const W=480,H=320;
const renderer=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});renderer.setSize(W,H);renderer.setPixelRatio(1);renderer.setClearColor(0x293441);document.body.append(renderer.domElement);
const rad=d=>d*Math.PI/180;
function box(scene,size,pos,color,rx=0){const mesh=new THREE.Mesh(new THREE.BoxGeometry(...size),new THREE.MeshLambertMaterial({color}));mesh.position.set(...pos);mesh.rotation.x=rad(rx);scene.add(mesh);return mesh;}
function player(scene,left){
 box(scene,[8,8,8],[0,28,0],0xa99882);box(scene,[8,12,4],[0,18,0],0x4e8590);box(scene,[4,12,4],[-2,6,0],0x455671);box(scene,[4,12,4],[2,6,0],0x455671);
 for(const sign of [-1,1]){const shoulder=new THREE.Group();shoulder.position.set(sign*6,22,0);shoulder.rotation.x=rad((left?sign<0:sign>0)?22.5:0);scene.add(shoulder);box(shoulder,[4,8,4],[0,-2,0],0x4e8590);box(shoulder,[4,4,4],[0,-8,0],0xa99882);}
 // Nose establishes facing -Z.
 box(scene,[2,2,1],[0,27,-4.5],0x7c6a58);
}
window.ready=fetch('/data.json').then(r=>r.json()).then(data=>window.items=data);
window.draw=async(name,view,fov=70)=>{
 await window.ready;const item=window.items[name],scene=new THREE.Scene();scene.add(new THREE.AmbientLight(0xffffff,2));const light=new THREE.DirectionalLight(0xffffff,2);light.position.set(-20,40,40);scene.add(light);
 const root=new THREE.Group(),model=new THREE.Group();root.add(model);scene.add(root);
 const materials={};for(const [k,url] of Object.entries(item.textures)){const tex=await new THREE.TextureLoader().loadAsync(url);tex.magFilter=THREE.NearestFilter;tex.minFilter=THREE.NearestFilter;tex.colorSpace=THREE.SRGBColorSpace;materials[k]=new THREE.MeshLambertMaterial({map:tex,alphaTest:.5});}
 for(const f of item.faces){const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(f.p.flat(),3));g.setAttribute('uv',new THREE.Float32BufferAttribute(f.uv.flat(),2));g.setIndex([0,2,1,0,3,2]);g.computeVertexNormals();model.add(new THREE.Mesh(g,materials[f.t]));}
 const slot=view.replace(/_(side|spin)$/,'');const d=item.display[slot];const left=slot.includes('lefthand');const a=[...d.rotation],t=[...d.translation];if(left){a[1]*=-1;a[2]*=-1;t[0]*=-1;}model.rotation.set(...a.map(rad));model.position.set(...t);model.scale.set(...d.scale);
 let camera=new THREE.PerspectiveCamera(fov,W/H,.1,500);
 if(slot.startsWith('firstperson')){root.position.set(left?-9.039:9.039,15.682,20.8);camera.position.set(0,24,32.4);camera.lookAt(0,24,0);}
 else if(slot.startsWith('thirdperson')){player(scene,left);const ang=rad(22.5);root.position.set(left?-6:6,22-Math.cos(ang)*10+Math.sin(ang)*2,-Math.sin(ang)*10-Math.cos(ang)*2);root.rotation.x=rad(-67.5);camera.fov=42;camera.position.set(...(view.endsWith('_side')?[48,22,0]:[-44,40,-44]));camera.lookAt(0,14,0);}
 else if(slot==='ground'){root.position.set(0,4,0);if(view.endsWith('_spin'))root.rotation.y=Math.PI/2;box(scene,[16,.2,16],[0,-.1,0],0x566a4a);camera.fov=42;camera.position.set(-24,21,-24);camera.lookAt(0,4,0);}
 else if(slot==='fixed'){root.scale.setScalar(.5);root.position.z=-1;box(scene,[12,12,1],[0,0,.5],0x765139);box(scene,[10,10,.2],[0,0,-.1],0x493e2e);camera.fov=42;camera.position.set(0,0,-22);camera.lookAt(0,0,0);}
 else {camera=new THREE.OrthographicCamera(-12,12,8,-8,.1,100);camera.position.z=40;camera.lookAt(0,0,0);}
 camera.updateProjectionMatrix();renderer.render(scene,camera);
 const result=renderer.domElement.toDataURL();scene.traverse(o=>{if(o.geometry)o.geometry.dispose()});for(const m of Object.values(materials)){m.map.dispose();m.dispose();}return result;
};
