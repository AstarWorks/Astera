// Capture real rendered evidence, not a test suite. The HTML can also be opened manually.
import {chromium} from '../skill-viewer/node_modules/playwright-core/index.mjs';
import {parseArgs} from 'node:util';
import {mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
const {values:opts,positionals}=parseArgs({allowPositionals:true,options:{yaw:{type:'string'},pitch:{type:'string'},zoom:{type:'string'},mode:{type:'string',default:'textured'},element:{type:'string'},context:{type:'string',default:'model'}}});
const [file,out,...requested]=positionals;
if(!file||!out)throw new Error('Usage: bun review.mjs viewer.html output-dir [item ...] [--yaw 35 --pitch -20 --zoom 2 --mode wireframe --element e0001 --context model]');
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_EXECUTABLE||'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
 const page=await browser.newPage({viewport:{width:1440,height:960}});
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));page.on('response',r=>{if(r.status()>=400)errors.push(`${r.status()} ${r.url()}`)});
 await page.goto(pathToFileURL(resolve(file)).href);
 await page.waitForFunction(()=>window.review?.ready);await page.evaluate(()=>window.review.ready);
 const available=await page.evaluate(()=>Object.keys(window.items));
 const names=requested.length?requested:available;
 for(const name of names)if(!available.includes(name))throw new Error('Unknown item '+name);
 await mkdir(out,{recursive:true});const records=[];
 const views=[];
 if(opts.yaw!==undefined||opts.pitch!==undefined||opts.zoom!==undefined||opts.element||opts.mode!=='textured'||opts.context!=='model'){
  views.push({name:'detail',context:opts.context,...((opts.yaw!==undefined||opts.pitch!==undefined||opts.zoom!==undefined)?{camera:{yaw:Number(opts.yaw||0),pitch:Number(opts.pitch||0),zoom:Number(opts.zoom||1)}}:{})});
 } else {
  for(const pitch of [-45,0,45])for(let yaw=0;yaw<360;yaw+=45)views.push({name:`orbit-${pitch}-${yaw}`,context:'model',camera:{yaw,pitch,zoom:1}});
  for(const pitch of [-90,90])views.push({name:`pole-${pitch}`,context:'model',camera:{yaw:0,pitch,zoom:1}});
  for(const context of ['gui','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','thirdperson_righthand_side','ground','ground_spin','fixed'])views.push({name:context,context});
 }
 for(const name of names){
  let previous;
  for(const view of views){
   if(previous!==view.context){await page.evaluate(async({name,context})=>await window.review.load(name,context),{name,context:view.context});previous=view.context;}
   const result=await page.evaluate(({view,opts})=>{
    window.review.setMode(opts.mode);
    if(opts.element){window.review.select([{id:opts.element}]);window.review.frameSelection();}
    if(view.camera)window.review.setCamera(view.camera);
    return window.review.capture();
   },{view,opts});
   const filename=`${name}-${view.name}.png`;
   await writeFile(resolve(out,filename),Buffer.from(result.png.split(',')[1],'base64'));
   records.push({file:filename,...result.metadata});
  }
  console.log(`${name}: ${views.length} views`);
 }
 await page.screenshot({path:resolve(out,'viewer-ui.png')});
 if(errors.length)throw new Error(errors.join('\n'));
 await writeFile(resolve(out,'cameras.json'),JSON.stringify(records,null,2)+'\n');
 console.log(`${records.length} captures, no browser errors`);
} finally {await browser.close();}
