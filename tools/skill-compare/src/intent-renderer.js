// Port of the zwei-s3 compare prototype's three.js "intent" renderer (my reading of a video, drawn with simple
// primitives). Times are fractional ticks (20/s). Factory so the page owns the DOM.
import * as THREE from "three";
THREE.ColorManagement.enabled = false; // the prototype was r128 (no colour management); keep its look

export function createIntentRenderer(els, intent) {
"use strict";
var DUR=intent.duration_ticks||100;
var V=THREE.Vector3;
var $=function(id){return els[id]};
/* ---------- utils ---------- */
function pad3(n){return String(n).padStart(3,"0")}
function clamp(x,a,b){return Math.max(a,Math.min(b,x))}
function lerp(a,b,u){return a+(b-a)*u}
function hex(c){c=c.replace("#","");return [parseInt(c.substr(0,2),16)/255,parseInt(c.substr(2,2),16)/255,parseInt(c.substr(4,2),16)/255]}
function rng(seed){var a=seed>>>0;return function(){a|=0;a=a+0x6D2B79F5|0;var t=Math.imul(a^a>>>15,1|a);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296}}
var EASE={linear:function(u){return u},out_expo:function(u){return u>=1?1:1-Math.pow(2,-10*u)},in_expo:function(u){return u<=0?0:Math.pow(2,10*u-10)},
  out_cubic:function(u){return 1-Math.pow(1-u,3)},in_cubic:function(u){return u*u*u},out_quad:function(u){return 1-(1-u)*(1-u)},in_quad:function(u){return u*u},
  smooth:function(u){return u*u*(3-2*u)},in_out:function(u){return u*u*(3-2*u)}};
function ease(name,u){u=clamp(u,0,1);return (EASE[name]||EASE.linear)(u)}
function seg(keys,t){ // returns [a,b,u] ; ease name taken from b
  if(t<=keys[0].t)return [keys[0],keys[0],0];
  var n=keys.length;if(t>=keys[n-1].t)return [keys[n-1],keys[n-1],0];
  for(var i=0;i<n-1;i++){if(t>=keys[i].t&&t<keys[i+1].t){var a=keys[i],b=keys[i+1];return [a,b,ease(b.ease,(t-a.t)/(b.t-a.t))]}}
  return [keys[n-1],keys[n-1],0];
}
function lerpArr(a,b,u){return a.map(function(v,i){return lerp(v,b[i],u)})}
function actorPos(act,t){var s=seg(act.keys,t);return lerpArr(s[0].pos,s[1].pos,s[2])}

/* ---------- 3D setup ---------- */
var cv=els.cv, stage=els.stage;
var renderer=new THREE.WebGLRenderer({canvas:cv,antialias:true,alpha:false,preserveDrawingBuffer:true});
renderer.setClearColor(0x080b12,1);renderer.outputColorSpace=THREE.LinearSRGBColorSpace;
var scene=new THREE.Scene();
var cam=new THREE.PerspectiveCamera(40,16/9,0.05,200);
scene.add(new THREE.AmbientLight(0xffffff,0.55*Math.PI));
var dl=new THREE.DirectionalLight(0xffffff,0.8*Math.PI);dl.position.set(3,8,6);scene.add(dl);
var floor=new THREE.Mesh(new THREE.PlaneGeometry(60,40),new THREE.MeshBasicMaterial({color:0x0e131c}));
floor.rotation.x=-Math.PI/2;floor.position.set(3,-0.002,0);scene.add(floor);
var grid=new THREE.GridHelper(40,40,0x3b4a62,0x1e2736);grid.position.set(3,0,0);scene.add(grid);
var axis=new THREE.Mesh(new THREE.PlaneGeometry(40,0.03),new THREE.MeshBasicMaterial({color:0x4a6a9a}));
axis.rotation.x=-Math.PI/2;axis.position.set(3,0.002,0);scene.add(axis);

function Batch(additive,maxQuads){
  this.max=maxQuads;this.pos=new Float32Array(maxQuads*12);this.col=new Float32Array(maxQuads*16);
  var idx=new Uint16Array(maxQuads*6);for(var i=0;i<maxQuads;i++){var o=i*4;idx.set([o,o+1,o+2,o+2,o+1,o+3],i*6)}
  this.geo=new THREE.BufferGeometry();
  this.geo.setAttribute("position",new THREE.BufferAttribute(this.pos,3).setUsage(THREE.DynamicDrawUsage));
  this.geo.setAttribute("acolor",new THREE.BufferAttribute(this.col,4).setUsage(THREE.DynamicDrawUsage));
  this.geo.setIndex(new THREE.BufferAttribute(idx,1));
  this.mat=new THREE.ShaderMaterial({vertexShader:"attribute vec4 acolor;varying vec4 vC;void main(){vC=acolor;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);}",
    fragmentShader:"varying vec4 vC;void main(){gl_FragColor=vC;}",transparent:true,depthWrite:false,side:THREE.DoubleSide,
    blending:additive?THREE.AdditiveBlending:THREE.NormalBlending});
  this.mesh=new THREE.Mesh(this.geo,this.mat);this.mesh.frustumCulled=false;this.mesh.renderOrder=additive?3:2;scene.add(this.mesh);this.n=0;
}
Batch.prototype.reset=function(){this.n=0};
Batch.prototype.flush=function(){this.geo.setDrawRange(0,this.n*6);this.geo.attributes.position.needsUpdate=true;this.geo.attributes.acolor.needsUpdate=true};
Batch.prototype.quad=function(p0,p1,p2,p3,c0,c1,c2,c3){
  if(this.n>=this.max)return;var o=this.n*12,k=this.n*16,P=[p0,p1,p2,p3],C=[c0,c1||c0,c2||c0,c3||c1||c0];
  for(var i=0;i<4;i++){this.pos[o+i*3]=P[i].x;this.pos[o+i*3+1]=P[i].y;this.pos[o+i*3+2]=P[i].z;
    for(var j=0;j<4;j++)this.col[k+i*4+j]=C[i][j]}
  this.n++;
};
// camera-facing ribbon along points; ws widths, cs [r,g,b,a]; side optional fixed vector
Batch.prototype.strip=function(pts,ws,cs,side){
  var n=pts.length,L=[],R=[],i,d=new V(),vd=new V();
  for(i=0;i<n;i++){
    var a=pts[Math.max(0,i-1)],b=pts[Math.min(n-1,i+1)];d.subVectors(b,a);
    var s;if(side)s=side.clone();else{vd.subVectors(cam.position,pts[i]);s=new V().crossVectors(d,vd)}
    if(s.lengthSq()<1e-12)s.set(0,1,0);s.normalize().multiplyScalar(ws[i]/2);
    L.push(pts[i].clone().add(s));R.push(pts[i].clone().sub(s));
  }
  for(i=0;i<n-1;i++)this.quad(L[i],R[i],L[i+1],R[i+1],cs[i],cs[i],cs[i+1],cs[i+1]);
};
var BA=new Batch(true,14000), BN=new Batch(false,2000);

/* actors & weapon */
var actorObjs={}, weaponGroup, bladeMesh, guard;
function buildScene(){
  intent.actors.forEach(function(a){
    var g=new THREE.Group(),H=a.height,col=new THREE.Color(a.color);
    var m=new THREE.MeshLambertMaterial({color:col});
    var body=new THREE.Mesh(new THREE.CylinderGeometry(0.2*H/1.8,0.22*H/1.8,0.55*H,16),m);body.position.y=0.55*H;g.add(body);
    var sh=new THREE.Mesh(new THREE.SphereGeometry(0.2*H/1.8,16,12),m);sh.position.y=0.82*H;sh.scale.y=0.5;g.add(sh);
    var head=new THREE.Mesh(new THREE.SphereGeometry(0.13*H/1.8,16,12),m);head.position.y=0.9*H;g.add(head);
    [-0.1,0.1].forEach(function(z){var l=new THREE.Mesh(new THREE.CylinderGeometry(0.07*H/1.8,0.07*H/1.8,0.3*H,10),m);l.position.set(0,0.15*H,z*H/1.8);g.add(l)});
    var face=a.id==="caster"?1:-1;
    var nose=new THREE.Mesh(new THREE.BoxGeometry(0.14,0.05,0.1),new THREE.MeshBasicMaterial({color:0xffffff}));nose.position.set(face*0.2*H/1.8,0.9*H,0);g.add(nose);
    scene.add(g);actorObjs[a.id]=g;addLabel(a.id,"actor");
  });
  var w=intent.weapon;weaponGroup=new THREE.Group();
  var inner=new THREE.Group();inner.name="inner";
  var bm=new THREE.MeshLambertMaterial({color:new THREE.Color(w.color),emissive:0x20242c});
  bladeMesh=new THREE.Mesh(new THREE.BoxGeometry(w.length,w.width,0.04),bm);bladeMesh.position.x=w.length/2;inner.add(bladeMesh);
  var tipg=new THREE.Mesh(new THREE.ConeGeometry(w.width/2,0.3,4),bm);tipg.rotation.z=-Math.PI/2;tipg.scale.z=0.12;tipg.position.x=w.length+0.15;inner.add(tipg);
  guard=new THREE.Mesh(new THREE.BoxGeometry(0.06,0.7,0.08),new THREE.MeshLambertMaterial({color:0x6a5a3a}));guard.position.x=0.02;inner.add(guard);
  var grip=new THREE.Mesh(new THREE.BoxGeometry(0.3,0.07,0.07),new THREE.MeshLambertMaterial({color:0x4a3a2a}));grip.position.x=-0.15;inner.add(grip);
  weaponGroup.add(inner);scene.add(weaponGroup);
  // fill carried spin_y
  var sp=0;w.keys.forEach(function(k){if(k.spin_y===undefined)k.spin_y=sp;else sp=k.spin_y});
  // precompute hit labels
  intent.effects.forEach(function(e,i){e._i=i;if(e.type==="hit")e._label=addLabel("HIT "+e.coin,"hit")});
}
var labelEls=[];
function addLabel(text,cls){var el=document.createElement("div");el.className="lbl "+cls;el.textContent=text;els.labels.appendChild(el);labelEls.push(el);return el}

function weaponState(t){
  var s=seg(intent.weapon.keys,t);
  return {angle:lerp(s[0].angle,s[1].angle,s[2]),spin:lerp(s[0].spin_y,s[1].spin_y,s[2])};
}
function casterPos(t){var a=intent.actors.filter(function(x){return x.id===intent.weapon.attach})[0];return actorPos(a,t)}
function tipAt(t){
  var w=intent.weapon,ws=weaponState(t),cp=casterPos(t),r=ws.angle*Math.PI/180;
  var p=new V(0.25+w.length*Math.cos(r),1.0+w.length*Math.sin(r),0);
  p.applyAxisAngle(new V(0,1,0),ws.spin*Math.PI/180);return p.add(new V(cp[0],cp[1],cp[2]));
}

/* camera */
function camState(t){
  var K=intent.camera,n=K.length,i=0;
  for(i=n-1;i>=0;i--){if(t>=K[i].t)break}
  if(i<0)i=0;var a=K[i],b=K[i+1],u=0;
  if(b&&b.t-a.t<=3){u=ease("smooth",(t-a.t)/(b.t-a.t))}else b=a;
  return {pos:lerpArr(a.pos,b.pos,u),target:lerpArr(a.target,b.target,u),fov:lerp(a.fov,b.fov,u),note:a.note||""};
}

/* ---------- effects ---------- */
var tmp=new V();
function col4(c,a){return [c[0],c[1],c[2],a]}
function fadeOut(t,t1,span){return clamp((t1-t)/span,0,1)}
var RING_VIEW_TILT=0; // display-only extra tilt so a horizontal ring reads from a near-horizontal camera
function ringPoint(e,deg,r){
  var a=deg*Math.PI/180,tilt=((e.tilt_deg||0)+RING_VIEW_TILT)*Math.PI/180;
  var x=r*Math.cos(a),z=r*Math.sin(a);
  // tilt about x axis (z->y)
  var p=new V(x,-z*Math.sin(tilt),z*Math.cos(tilt));
  return p.add(new V(e.center[0],e.center[1],e.center[2]));
}
function drawRing(e,t){
  var lt=t-e.t0,draw=e.draw_ticks||4,prog=clamp(lt/draw,0,1);
  var fa=1,tailMix=0;
  if(e.fade){fa=clamp((e.fade.to-t)/(e.fade.to-e.fade.from),0,1);tailMix=1-fa}
  var tailC=e.fade&&e.fade.tail_color?hex(e.fade.tail_color):null;
  var N=64;
  e.layers.forEach(function(L){
    var base=hex(L.color);if(tailC)base=[lerp(base[0],tailC[0],tailMix),lerp(base[1],tailC[1],tailMix),lerp(base[2],tailC[2],tailMix)];
    var pts=[],ws=[],cs=[],R=e.radius+(L.offset||0)*0.25;
    var headDeg=e.sweep_deg*prog;
    // tail erases once fully drawn (trailing edge catches up as time passes)
    var tailFrac=0;if(prog>=1&&e.fade)tailFrac=clamp((t-e.fade.from)/(e.fade.to-e.fade.from),0,1)*0.6;
    var m=Math.max(4,Math.round(N*prog));
    for(var i=0;i<=m;i++){
      var f=i/m,ang=e.sweep_deg*(tailFrac+(prog-tailFrac)*f);
      var deg=e.start_deg+ang;
      pts.push(ringPoint(e,deg,R));
      var along=(tailFrac+(prog-tailFrac)*f); // 0..1 along the full arc
      var tap=0.25+0.75*along; // thin tail, thick head
      ws.push((L.width*e.width/0.55)*tap*1.6);
      cs.push(col4(base,(L.alpha===undefined?1:L.alpha)*fa*(0.35+0.65*along)));
    }
    BA.strip(pts,ws,cs);
  });
}
function drawSparks(e,t){
  var r=rng(1000+e._i*77),count=e.count,pt=e.per_tick,tot=pt?Math.round(count*(e.t1-e.t0+1)):count,col=hex(e.color);
  var ringE=null;if(typeof e.dir==="string"&&e.dir.indexOf("tangent_of:")===0){var id=e.dir.split(":")[1];ringE=intent.effects.filter(function(x){return x.id===id})[0]}
  var g=e.gravity||0;
  for(var k=0;k<tot;k++){
    var ts=e.t0+(pt?Math.floor(k/count)+r()*1:(k/tot)*(e.t1-e.t0)+r()*0.5);var age=t-ts;var rr=[r(),r(),r(),r()];
    if(age<0||age>=e.life)continue;
    var origin=e.emitter==="weapon_tip"?tipAt(ts):new V(e.emitter[0],e.emitter[1],e.emitter[2]);
    var d;
    if(ringE){var prog=clamp((ts-ringE.t0)/(ringE.draw_ticks||4),0,1),deg=ringE.start_deg+ringE.sweep_deg*prog,a=(deg+90)*Math.PI/180;d=new V(Math.cos(a),0,Math.sin(a))}
    else if(e.dir==="up_back_arc"){d=new V(-0.5-rr[0]*0.8,0.8+rr[1]*0.8,(rr[2]-0.5)*0.6)}
    else d=new V(rr[0]-0.5,rr[1],rr[2]-0.5);
    d.normalize().add(new V(rr[1]-0.5,rr[2]-0.5,rr[3]-0.5).multiplyScalar(0.5)).normalize();
    var v=d.multiplyScalar(e.speed*(0.6+0.8*rr[3]));
    var as=age*0.05;var p=origin.clone().addScaledVector(v,as);p.y-=0.5*g*as*as;
    var vel=v.clone();vel.y-=g*as;
    var tail=p.clone().addScaledVector(vel,-0.07);
    var f=1-age/e.life;
    BA.strip([tail,p],[0.01,0.045],[col4(col,0),col4([1,0.95,0.8].map(function(x,i){return lerp(col[i],x,0.5)}),f)]);
  }
}
function drawHit(e,t){
  var lt=t-e.t;if(lt<0||lt>10)return;var c=new V(e.at[0],e.at[1],e.at[2]);
  if(lt<=3){
    var u=lt/3,r=lerp(0.15,0.85,ease("out_cubic",u)),a=1-u*0.9;
    cam.updateMatrixWorld();var right=new V().setFromMatrixColumn(cam.matrixWorld,0),up=new V().setFromMatrixColumn(cam.matrixWorld,1);
    var pts=[],ws=[],cs=[];for(var i=0;i<=40;i++){var an=i/40*Math.PI*2;pts.push(c.clone().addScaledVector(right,Math.cos(an)*r).addScaledVector(up,Math.sin(an)*r));ws.push(0.09*(1-u*0.6));cs.push([1,1,1,a])}
    BA.strip(pts,ws,cs);
    for(var s=0;s<8;s++){var an2=s/8*Math.PI*2+0.3;var d=right.clone().multiplyScalar(Math.cos(an2)).addScaledVector(up,Math.sin(an2));
      BA.strip([c.clone().addScaledVector(d,r*0.6),c.clone().addScaledVector(d,r*1.5)],[0.05,0.01],[[1,1,0.8,a],[1,1,0.8,0]])}
  }
  e._show=lt;
}
function drawDecal(e,t){
  var u=clamp((t-e.t0)/3,0,1),r=e.size/2*ease("out_cubic",u),c=hex(e.color),ctr=new V(e.at[0],e.at[1],e.at[2]),pts=[];
  for(var i=0;i<=32;i++){var a=i/32*Math.PI*2;var wob=1+0.12*Math.sin(a*5+1)+0.08*Math.sin(a*3);pts.push(new V(ctr.x+Math.cos(a)*r*wob*1.15,ctr.y,ctr.z+Math.sin(a)*r*wob*0.8))}
  for(i=0;i<32;i++)BN.quad(ctr,ctr,pts[i],pts[i+1],col4(c,0.8));
}
function drawSmear(e,t){
  var u=clamp((t-e.t0)/(e.t1-e.t0),0,1),c=hex(e.color),a0=Array.isArray(e.alpha)?e.alpha[0]:e.alpha;
  var f=new V(e.from[0],e.from[1],e.from[2]),to=new V(e.to[0],e.to[1],e.to[2]);
  var head=f.clone().lerp(to,ease("out_expo",Math.min(1,u*1.5+0.2)));var tl=f.clone().lerp(to,clamp(u-0.5,0,1)*0.6);
  var n=14,pts=[],ws=[],cs=[],fa=u>0.8?(1-u)/0.2:1;
  var rnd=rng(e._i*13+5);
  for(var i=0;i<=n;i++){var fr=i/n;var p=tl.clone().lerp(head,fr);p.y+=Math.sin(fr*9+1)*0.04;pts.push(p);
    ws.push(e.width*(0.35+0.65*Math.sin(Math.PI*Math.min(1,fr*0.9+0.1)))*(0.8+0.2*rnd()));cs.push(col4(c,a0*fa*(0.25+0.75*fr)))}
  BA.strip(pts,ws,cs,new V(0,1,0));
}
function drawSpeed(e,t){
  var r=rng(31+e._i),n=e.count,c=hex(e.color),ff=e.fade_from||e.t1,fa=clamp((e.t1+1-t)/(e.t1+1-ff),0,1);
  var lt=t-e.t0;
  for(var i=0;i<n;i++){
    var x=lerp(e.region.x[0],e.region.x[1],r()),y=lerp(e.region.y[0],e.region.y[1],r()),z=lerp(e.region.z[0],e.region.z[1],r());
    var len=lerp(e.length[0],e.length[1],r()),sp=0.6+r()*1.2,ph=r();
    var x2=x+lt*sp*0.5;var dir=new V(e.dir[0],e.dir[1],e.dir[2]).normalize();
    var head=new V(x2,y,z),tail=head.clone().addScaledVector(dir,-len);
    var wr=0.02+0.03*r();
    BA.strip([tail,head],[0.002,wr],[col4(c,0),col4(c,e.alpha*fa*(0.5+0.5*ph))]);
    if(i%4===0)BA.strip([tail.clone().lerp(head,0.5),head],[0.001,wr*0.8],[[1,1,1,0],[1,1,1,0.6*fa]]);
  }
}
function drawWhoosh(e,t){
  var r=rng(77+e._i),u=clamp((t-e.t0)/(e.t1-e.t0),0,1),c=hex(e.color),fa=u>0.7?(1-u)/0.3:1;
  var ctr=new V(e.at[0],e.at[1],e.at[2]);
  for(var k=0;k<e.count;k++){
    var rad=lerp(e.radius[0],e.radius[1],r()),a0=lerp(-60,40,r()),sw=lerp(90,150,r()),off=new V(0.3+r()*0.5,(r()-0.5)*0.6,(r()-0.5)*0.4);
    var du=clamp(u*1.6-k*0.15,0,1);if(du<=0)continue;var pts=[],ws=[],cs=[],m=24;
    for(var i=0;i<=m;i++){var f=i/m*du,an=(a0+sw*f)*Math.PI/180;pts.push(new V(ctr.x+off.x+Math.cos(an)*rad,ctr.y+off.y+Math.sin(an)*rad,ctr.z+off.z));
      var fr=i/m;ws.push(0.07*(0.2+0.8*fr));cs.push(col4(c,fa*(0.15+0.85*fr)))}
    BA.strip(pts,ws,cs);
  }
}
function drawGlint(e,t){
  var p=tipAt(t),u=(t-e.t0),pulse=0.55+0.45*Math.sin(u*1.1),c=hex(e.color),s=0.55*pulse+0.15;
  cam.updateMatrixWorld();var right=new V().setFromMatrixColumn(cam.matrixWorld,0),up=new V().setFromMatrixColumn(cam.matrixWorld,1);
  [[right,s],[up,s],[right.clone().add(up).normalize(),s*0.6],[right.clone().sub(up).normalize(),s*0.6]].forEach(function(d){
    BA.strip([p.clone().addScaledVector(d[0],-d[1]),p,p.clone().addScaledVector(d[0],d[1])],[0.005,0.06,0.005],[col4(c,0),col4([1,1,1],pulse),col4(c,0)]);
  });
}
var pillarMeshes=[];
function initMeshes(){
  intent.effects.forEach(function(e){
    if(e.type==="pillar"){
      var core=new THREE.Mesh(new THREE.BoxGeometry(e.width,e.height,e.width),new THREE.MeshBasicMaterial({color:new THREE.Color(e.color),transparent:true,blending:THREE.AdditiveBlending,depthWrite:false}));
      var glow=new THREE.Mesh(new THREE.BoxGeometry(e.width*2.6,e.height,e.width*2.6),new THREE.MeshBasicMaterial({color:new THREE.Color(e.glow),transparent:true,opacity:0.35,blending:THREE.AdditiveBlending,depthWrite:false}));
      var g=new THREE.Group();g.add(glow);g.add(core);g.renderOrder=3;scene.add(g);e._m={g:g,core:core,glow:glow};
    }
  });
}
function drawPillar(e,t){
  var m=e._m;m.g.visible=true;var u=clamp((t-e.t0)/(e.t1-e.t0),0,1),grow=ease("out_expo",clamp((t-e.t0)/2,0,1)),fa=u>0.5?1-(u-0.5)/0.5:1;
  m.g.position.set(e.at[0],e.at[1]+e.height*grow/2,e.at[2]);m.g.scale.set(1-0.4*u,Math.max(0.01,grow),1-0.4*u);
  m.core.material.opacity=fa;m.glow.material.opacity=0.35*fa;
}
function drawHbar(e,t){
  var u=clamp((t-e.t0)/(e.t1-e.t0),0,1),grow=ease("out_expo",clamp((t-e.t0)/2,0,1)),fa=u>0.4?1-(u-0.4)/0.6:1,hl=e.length/2*grow;
  var c=new V(e.center[0],e.center[1],e.center[2]),a=c.clone().add(new V(-hl,0,0)),b=c.clone().add(new V(hl,0,0)),th=e.thickness*(1-0.6*u);
  BA.strip([a,c,b],[th*0.2,th,th*0.2],[[1,1,1,0.3*fa],[1,1,1,fa],[1,1,1,0.3*fa]],new V(0,1,0));
  BA.strip([a,c,b],[th*3,th*5,th*3],[[1,0.9,0.4,0.0],[1,0.9,0.4,0.28*fa],[1,0.9,0.4,0.0]],new V(0,1,0));
}
function drawSpike(e,t){
  var r=rng(555+e._i),lt=t-e.t0,u=clamp(lt/(e.t1-e.t0),0,1),grow=ease("out_expo",clamp(lt/1.8,0,1)),fa=u>0.6?1-(u-0.6)/0.4:1;
  var c=hex(e.color),core=hex(e.core),ctr=new V(e.center[0],e.center[1],e.center[2]),fl=e.flicker||2;
  for(var k=0;k<e.count;k++){
    var base=lerp(e.spread_deg[0],e.spread_deg[1],(k+r()*0.8)/e.count),len=lerp(e.length[0],e.length[1],r())*grow,zdir=(r()-0.5)*0.7;
    var phase=Math.floor(lt/fl+k*0.37);var flick=(((phase*2654435761)>>>0)%100)/100;var fv=0.45+0.55*(flick>0.3?1:0.4);
    var an=base*Math.PI/180,d=new V(Math.cos(an),Math.sin(an),zdir).normalize(),perp=new V(-d.y,d.x,0);
    var m=e.zigzag*2+1,pts=[],ws=[],cs=[],cs2=[],ws2=[];
    for(var i=0;i<=m;i++){var f=i/m,off=(i===0||i===m)?0:((i%2)?1:-1)*0.12*len*(1-f);
      pts.push(ctr.clone().addScaledVector(d,len*f).addScaledVector(perp,off));ws.push(e.width*(1-f)*2.4);ws2.push(e.width*0.9*(1-f));
      cs.push(col4(c,fa*fv));cs2.push(col4(core,fa*fv))}
    BA.strip(pts,ws,cs);BA.strip(pts,ws2,cs2);
  }
  // soft hot center
  cam.updateMatrixWorld();var right=new V().setFromMatrixColumn(cam.matrixWorld,0);
  BA.strip([ctr.clone().addScaledVector(right,-0.6),ctr,ctr.clone().addScaledVector(right,0.6)],[0.0,0.5,0.0],[col4(c,0),col4(core,0.5*fa),col4(c,0)]);
}
function drawCracks(e,t){
  var r=rng(909+e._i),lt=t-e.t0,grow=ease("out_cubic",clamp(lt/4,0,1)),fa=clamp((e.t1-t)/(e.t1-e.fade_from),0,1),c=hex(e.color);
  var ctr=new V(e.center[0],e.center[1],e.center[2]);
  for(var k=0;k<e.count;k++){
    var an=(k+r()*0.6)/e.count*Math.PI*2,len=lerp(e.length[0],e.length[1],r())*grow,segs=6,pts=[],ws=[],cs=[],cur=an,p=ctr.clone();
    pts.push(p.clone());
    for(var i=1;i<=segs;i++){cur+= (r()-0.5)*0.9;p=p.clone().add(new V(Math.cos(cur),0,Math.sin(cur)).multiplyScalar(len/segs));pts.push(p)}
    for(i=0;i<=segs;i++){ws.push(0.07*(1-i/segs*0.7));cs.push(col4(c,fa*(1-i/segs*0.5)))}
    BA.strip(pts,ws,cs);
    BA.strip(pts,ws.map(function(w){return w*3.2}),cs.map(function(q){return [q[0],q[1],q[2],q[3]*0.18]}));
  }
}
var warned={},unknownMarkers=[];
function drawUnknown(e,t){
  if(!warned[e.type]){warned[e.type]=1;console.warn("unknown effect type:",e.type,e)}
  var p=e.at||e.center||e.emitter||[0,2.5,0];if(!Array.isArray(p))p=[0,2.5,0];
  var c=new V(p[0],p[1],p[2]),s=0.25;
  BA.strip([c.clone().add(new V(-s,0,0)),c.clone().add(new V(s,0,0))],[0.06,0.06],[[1,0,1,1],[1,0,1,1]]);
  BA.strip([c.clone().add(new V(0,-s,0)),c.clone().add(new V(0,s,0))],[0.06,0.06],[[1,0,1,1],[1,0,1,1]]);
}

/* ---------- frame update ---------- */
var flashEl=els.flash;
function projectTo(v){var p=v.clone().project(cam);return {x:(p.x*0.5+0.5)*100,y:(-p.y*0.5+0.5)*100,z:p.z}}
var lastT=0;
function render(t){
  lastT=t;
  t=Math.max(0,Math.min(DUR,t));
  var cs=camState(t);cam.position.set(cs.pos[0],cs.pos[1],cs.pos[2]);cam.fov=cs.fov;cam.lookAt(cs.target[0],cs.target[1],cs.target[2]);cam.updateProjectionMatrix();cam.updateMatrixWorld();
  intent.actors.forEach(function(a){var p=actorPos(a,t);var g=actorObjs[a.id];g.position.set(p[0],p[1],p[2]);
    var el=labelEls[intent.actors.indexOf(a)];var pr=projectTo(new V(p[0],p[1]+a.height+0.2,p[2]));el.style.left=pr.x+"%";el.style.top=pr.y+"%";});
  var ws=weaponState(t),cp=casterPos(t);
  weaponGroup.position.set(cp[0],cp[1],cp[2]);weaponGroup.rotation.y=ws.spin*Math.PI/180;
  var inner=weaponGroup.getObjectByName("inner");inner.position.set(0.25,1.0,0);inner.rotation.z=ws.angle*Math.PI/180;
  BA.reset();BN.reset();var flash=0,flashC=null;
  intent.effects.forEach(function(e){
    var vis;
    if(e._m)e._m.g.visible=false;
    if(e._label)e._label.style.display="none";
    switch(e.type){
      case "hit":
        if(t>=e.t&&t<=e.t+10){drawHit(e,t);var pr=projectTo(new V(e.at[0],e.at[1]+0.6+(t-e.t)*0.05,e.at[2]));var lt=t-e.t;
          e._label.style.display="block";e._label.style.left=pr.x+"%";e._label.style.top=pr.y+"%";e._label.style.opacity=lt<7?1:clamp((10-lt)/3,0,1)}
        break;
      case "screen_flash":
        if(t>=e.t0&&t<e.t1){var a=Array.isArray(e.alpha)?lerp(e.alpha[0],e.alpha[1],(t-e.t0)/(e.t1-e.t0)):e.alpha;if(a>flash){flash=a;flashC=e.color}}break;
      case "crescent_ring": if(t>=e.t0&&t<=e.t1)drawRing(e,t);break;
      case "sparks": if(t>=e.t0&&t<=e.t1+e.life)drawSparks(e,t);break;
      case "decal": if(t>=e.t0&&t<=e.t1)drawDecal(e,t);break;
      case "smear": if(t>=e.t0&&t<=e.t1)drawSmear(e,t);break;
      case "speedlines": if(t>=e.t0&&t<=e.t1)drawSpeed(e,t);break;
      case "whoosh_arcs": if(t>=e.t0&&t<=e.t1)drawWhoosh(e,t);break;
      case "glint": if(t>=e.t0&&t<=e.t1)drawGlint(e,t);break;
      case "pillar": if(t>=e.t0&&t<=e.t1)drawPillar(e,t);break;
      case "hbar": if(t>=e.t0&&t<=e.t1)drawHbar(e,t);break;
      case "spike_star": if(t>=e.t0&&t<=e.t1)drawSpike(e,t);break;
      case "ground_cracks": if(t>=e.t0&&t<=e.t1)drawCracks(e,t);break;
      default: drawUnknown(e,t);
    }
  });
  BA.flush();BN.flush();
  flashEl.style.background=flashC||"transparent";flashEl.style.opacity=flash;
  renderer.render(scene,cam);
  els.hud.textContent="cam: "+cs.note;
}
function resize(){var w=stage.clientWidth,h=stage.clientHeight;if(!w||!h)return;renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,2));renderer.setSize(w,h,false);cam.aspect=w/h;cam.updateProjectionMatrix();render(lastT)}
if(window.ResizeObserver)new ResizeObserver(resize).observe(stage);
buildScene();initMeshes();resize();
return {render:render,resize:resize,duration:DUR};
}
