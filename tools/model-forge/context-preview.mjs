// Offline display-context preview. Uses the existing skill-viewer dependencies.
// Reference: https://github.com/JannisX11/blockbench/blob/master/js/display_mode/display_mode.js
// Player rest pose only: no Minecraft animation, use/swing, glint or exact client lighting.
import {chromium} from '../skill-viewer/node_modules/playwright-core/index.mjs';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
const [dataPath,out]=process.argv.slice(2);
if(!dataPath || !out) throw new Error('Usage: bun context-preview.mjs data.json output-directory');
const app = await readFile(new URL('./context-scene.js', import.meta.url), 'utf8');
const server=Bun.serve({hostname:'127.0.0.1',port:0,fetch(req){
 const path=new URL(req.url).pathname;
 if(path==='/data.json')return new Response(Bun.file(dataPath),{headers:{'Content-Type':'application/json'}});
 if(path==='/three.js')return new Response(Bun.file(new URL('../skill-viewer/node_modules/three/build/three.module.js',import.meta.url)),{headers:{'Content-Type':'text/javascript'}});
 if(path==='/app.js')return new Response(app,{headers:{'Content-Type':'text/javascript'}});
 if(path!=='/')return new Response('Not found',{status:404});
 return new Response('<style>body{margin:0}</style><script type="module" src="/app.js"></script>',{headers:{'Content-Type':'text/html'}});
}});
let browser;
try {
 browser=await chromium.launch({executablePath:process.env.CHROMIUM_EXECUTABLE || '/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const page=await browser.newPage({viewport:{width:480,height:320}});
 page.on('pageerror',e=>console.error(e));
 await page.goto('http://127.0.0.1:'+server.port);await page.waitForFunction(()=>window.items);
 const items=JSON.parse(await readFile(dataPath,'utf8'));await mkdir(out,{recursive:true});
 for(const name of Object.keys(items)){
  for(const view of ['firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','thirdperson_righthand_side','ground','ground_spin','fixed','gui']){
   const url=await page.evaluate(async({name,view})=>await window.draw(name,view),{name,view});
   await writeFile(out+'/'+name+'-'+view+'.png',Buffer.from(url.split(',')[1],'base64'));
  }
  console.log(name);
 }
} finally {
 if(browser)await browser.close();server.stop();
}
