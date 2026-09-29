/**
 * Generates the proposed AI architecture drawing, matching PNG previews and structural/layout QA.
 * This is documentation tooling only; it does not execute application workflows or call models.
 */
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {createCanvas,GlobalFonts}=require('C:/Users/tanek/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@napi-rs/canvas');
const base=__dirname,sections=JSON.parse(fs.readFileSync(path.join(base,'diagram-content.json'),'utf8'));
const ref=JSON.parse(fs.readFileSync(path.join(base,'../lending-intelligence-engine.excalidraw'),'utf8'));
const templates={};for(const type of ['rectangle','text','arrow'])templates[type]=ref.elements.find(e=>e.type===type&&!e.isDeleted);
const fontFile=path.join(base,'../qa/Virgil.woff2');if(fs.existsSync(fontFile))GlobalFonts.registerFromPath(fontFile,'Virgil');
const family=fs.existsSync(fontFile)?'Virgil':'Segoe UI',measure=createCanvas(1,1).getContext('2d');
const colors={ui:'#b2f2bb',backend:'#ffe99a',external:'#ffc9c9'},elements=[],pages=[],nodes=new Map(),errors=[];
const W=2620,H=1580,GAP=170;
let serial=0;
function seed(s){return parseInt(crypto.createHash('sha256').update(s).digest('hex').slice(0,7),16);}
function fresh(type,id){const e=structuredClone(templates[type]);delete e.index;delete e.customData;return Object.assign(e,{id,x:0,y:0,width:0,height:0,angle:0,seed:seed(id),version:1,versionNonce:seed(id+'v'),updated:1790467200000,isDeleted:false,groupIds:[],frameId:null,boundElements:[],link:null,locked:false});}
function width(s,size){measure.font=size+'px "'+family+'"';return measure.measureText(s).width;}
function wrap(value,size,max){const lines=[];for(const para of value.split('\n')){if(!para){lines.push('');continue;}let line='';for(const word of para.split(' ')){if(width((line?line+' ':'')+word,size)<=max){line+=(line?' ':'')+word;}else{if(line)lines.push(line);line=word;}}lines.push(line);}return lines;}
function text(id,value,x,y,size=22,max=2460,container=null){const lines=wrap(value,size,max);const e=fresh('text',id);Object.assign(e,{x,y,width:Math.max(1,...lines.map(s=>width(s,size))),height:lines.length*size*1.25,text:lines.join('\n'),originalText:lines.join('\n'),fontSize:size,fontFamily:1,textAlign:container?'center':'left',verticalAlign:container?'middle':'top',containerId:container,autoResize:true,lineHeight:1.25,strokeColor:'#182230',backgroundColor:'transparent',roughness:0});elements.push(e);return e;}
function card(id,role,title,body,x,y,w=500,h=250){const e=fresh('rectangle',id);Object.assign(e,{x,y,width:w,height:h,strokeColor:'#26364a',backgroundColor:colors[role],fillStyle:'solid',strokeWidth:2,strokeStyle:'solid',roughness:1,roundness:{type:3},customData:{architectureStatus:'proposed-unless-labeled-existing',role,title}});elements.push(e);const t=text(id+'-text',title+'\n\n'+body,0,0,22,w-34,id);t.x=x+(w-t.width)/2;t.y=y+(h-t.height)/2;e.boundElements.push({id:t.id,type:'text'});nodes.set(id,e);if(t.height>h-26||t.width>w-26)errors.push('Text overflow: '+id);return e;}
function port(n,side){return side==='R'?[n.x+n.width+8,n.y+n.height/2]:side==='L'?[n.x-8,n.y+n.height/2]:side==='T'?[n.x+n.width/2,n.y-8]:[n.x+n.width/2,n.y+n.height+8];}
function edge(a,b,sa,sb,points=null,label='',labelPos=null,dashed=false){const p=points||[port(a,sa),port(b,sb)],id='edge-'+(++serial),e=fresh('arrow',id),p0=p[0];Object.assign(e,{x:p0[0],y:p0[1],width:Math.max(...p.map(v=>v[0]))-Math.min(...p.map(v=>v[0])),height:Math.max(...p.map(v=>v[1]))-Math.min(...p.map(v=>v[1])),points:p.map(v=>[v[0]-p0[0],v[1]-p0[1]]),strokeColor:'#526277',strokeWidth:2,strokeStyle:dashed?'dashed':'solid',roughness:0,startArrowhead:null,endArrowhead:'arrow',startBinding:{elementId:a.id,mode:'orbit',fixedPoint:({L:[0,.5001],R:[1,.5001],T:[.5001,0],B:[.5001,1]})[sa]},endBinding:{elementId:b.id,mode:'orbit',fixedPoint:({L:[0,.5001],R:[1,.5001],T:[.5001,0],B:[.5001,1]})[sb]},elbowed:false,customData:{from:a.id,to:b.id}});elements.push(e);a.boundElements.push({id,type:'arrow'});b.boundElements.push({id,type:'arrow'});if(label){const q=labelPos||[(p0[0]+p[p.length-1][0])/2-45,(p0[1]+p[p.length-1][1])/2-64];text(id+'-label',label,q[0],q[1],17,110);}return e;}
function header(s,y){text('view-'+s.id+'-title',s.title,60,y+35,42,2500);text('view-'+s.id+'-subtitle',s.subtitle,60,y+113,22,2480);text('view-'+s.id+'-status','TARGET ARCHITECTURE • 27 SEP 2026 • No application implementation',60,y+188,18,2480);}
function topology(s,y){
 const ui=card('s1-ui','ui','Choose AI simulation','Analyst enters intent, reviews\nresults and confirms publication.',60,y+260,430,205);
 const o=card('s1-orchestrator','backend','Orchestrator agent','Java + GPT-6 Astra\nOwns plan, scope and delegation.',610,y+260,500,205);
 const st=card('s1-workflow','backend','Durable workflow store','PostgreSQL task links, artifacts,\nleases, budgets and audit.',1240,y+260,560,205);
 const ai=card('s1-openai','external','OpenAI GPT-6 Astra','Responses API\nExternal model provider.',1950,y+260,560,205);
 edge(ui,o,'R','L',null,'intent',[505,y+303]);edge(o,st,'R','L',null,'save plan',[1123,y+303]);
 const agents=[
 ['research','Research agent','Find supported ranges\nand applicable evidence.'],
 ['designer','Experiment Designer','Resolve fields and domains;\nplan bounded exploration.'],
 ['coordinator','Simulation Coordinator','Admit/recover runs;\nexecute and refine.'],
 ['analyzer','Analyzer agent','Compare computed results;\ninvestigate outcome gaps.'],
 ['reviewer','Reflection Reviewer','Challenge scope and claims;\nrequest corrections.']
 ].map((v,i)=>card('s1-'+v[0],'backend',v[1],v[2],60+i*500,y+690,450,230));
 text('s1-bus-label','Structured A2A tasks and artifacts • every specialist is a separate Java service with GPT-6 Astra',330,y+525,21,2100);
 for(const a of agents)edge(o,a,'B','T',[port(o,'B'),[860,y+605],[a.x+a.width/2,y+605],port(a,'T')]);
 const tool=card('s1-tools','backend','Java Tool Gateway + MCP client','Schema + role + field-scope checks\nRead / simulate / publish permissions',60,y+1130,700,230);
 const existing=card('s1-existing','backend','Existing engine boundaries','Business Simulation + Customer Steps 1–8\nAuthoritative HTTP APIs and event contracts',915,y+1130,815,230);
 const model=card('s1-model','backend','Shared model-call boundary','Each agent uses the same typed client,\nredaction, budgets and prompt registry.',1870,y+1130,640,230);
 for(const a of agents)edge(a,tool,'B','T',[port(a,'B'),[a.x+a.width/2,y+1010],[410,y+1010],port(tool,'T')],null,null,true);
 edge(tool,existing,'R','L',null,'tools',[780,y+1180]);edge(model,ai,'T','R',[port(model,'T'),[2190,y+1050],[2565,y+1050],[2565,y+362],port(ai,'R')],null,null,true);
 text('s1-model-note','ALL SIX AGENTS → model-call boundary; each agent also invokes the Tool Gateway under its own permissions.',60,y+1430,21,2500);
 text('s1-legend','Green: analyst/UI   Yellow: owned backend/data   Red: external services   Details: views 2–7',60,y+1500,19,2500);
}
function flow(s,y){const ns=[];s.nodes.forEach((n,i)=>{const col=i<4?i:7-i,row=i<4?0:1;ns.push(card('s'+s.id+'-node-'+i,n[0],n[1],n[2],60+col*630,y+285+row*465));});for(let i=0;i<ns.length-1;i++){const a=ns[i],b=ns[i+1],sa=i===3?'B':i<3?'R':'L',sb=i===3?'T':i<3?'L':'R';let lp;if(i===3)lp=[a.x+a.width/2+20,y+625];else lp=[Math.min(a.x,b.x)+515,a.y+62];edge(a,b,sa,sb,null,s.labels[i],lp);}s.notes.forEach((n,i)=>text('s'+s.id+'-note-'+i,n,60,y+1170+i*108,21,2470));text('s'+s.id+'-footer','See architecture.md and contracts.md for invariants, metric definitions, operation contracts and source evidence.',60,y+1485,18,2500);}
sections.forEach((s,i)=>{const y=i*(H+GAP),start=elements.length;header(s,y);if(s.kind==='topology')topology(s,y);else flow(s,y);pages.push({id:s.id,title:s.title,x:0,y,width:W,height:H,elementIds:elements.slice(start).map(e=>e.id)});});
function boxOverlap(a,b,pad=0){return a.x<b.x+b.width+pad&&a.x+a.width>b.x-pad&&a.y<b.y+b.height+pad&&a.y+a.height>b.y-pad;}
function crosses(p,q,r){return p[0]===q[0]?p[0]>r.x+2&&p[0]<r.x+r.width-2&&Math.max(p[1],q[1])>r.y+2&&Math.min(p[1],q[1])<r.y+r.height-2:p[1]===q[1]?p[1]>r.y+2&&p[1]<r.y+r.height-2&&Math.max(p[0],q[0])>r.x+2&&Math.min(p[0],q[0])<r.x+r.width-2:false;}
const ids=new Set();for(const e of elements){if(ids.has(e.id))errors.push('Duplicate ID: '+e.id);ids.add(e.id);if(!Number.isFinite(e.x)||!Number.isFinite(e.y))errors.push('Non-finite geometry');}
for(const page of pages){const pe=elements.filter(e=>page.elementIds.includes(e.id)),cards=pe.filter(e=>e.type==='rectangle'),free=pe.filter(e=>e.type==='text'&&!e.containerId);
for(let i=0;i<cards.length;i++)for(let j=i+1;j<cards.length;j++)if(boxOverlap(cards[i],cards[j]))errors.push('Cards overlap: '+cards[i].id+' / '+cards[j].id);
for(let i=0;i<free.length;i++)for(let j=i+1;j<free.length;j++)if(boxOverlap(free[i],free[j]))errors.push('Text overlap: '+free[i].id+' / '+free[j].id);
for(const t of free)for(const n of cards)if(boxOverlap(t,n))errors.push('Label/card overlap: '+t.id+' / '+n.id);
for(const e of pe.filter(e=>e.type==='arrow')){for(const binding of [e.startBinding,e.endBinding])if(!ids.has(binding.elementId))errors.push('Dangling arrow '+e.id);const ps=e.points.map(p=>[p[0]+e.x,p[1]+e.y]);for(let i=1;i<ps.length;i++)for(const n of cards)if(![e.startBinding.elementId,e.endBinding.elementId].includes(n.id)&&crosses(ps[i-1],ps[i],n))errors.push('Arrow crosses card: '+e.id+' / '+n.id);}
for(const e of pe){const ep=e.type==='arrow'?e.points.map(p=>[e.x+p[0],e.y+p[1]]):[[e.x,e.y],[e.x+e.width,e.y+e.height]];if(ep.some(p=>p[0]<page.x||p[1]<page.y||p[0]>W||p[1]>page.y+H))errors.push('Page bounds: '+e.id);}
}
function rounded(ctx,x,y,w,h,r){ctx.beginPath();ctx.roundRect(x,y,w,h,r);}
function draw(ctx,pe,offY){
 ctx.fillStyle='#ffffff';ctx.fillRect(0,0,W,H);ctx.save();ctx.translate(0,-offY);
 for(const e of pe.filter(e=>e.type==='arrow')){ctx.strokeStyle=e.strokeColor;ctx.lineWidth=2;ctx.setLineDash(e.strokeStyle==='dashed'?[9,7]:[]);const p=e.points.map(v=>[v[0]+e.x,v[1]+e.y]);ctx.beginPath();ctx.moveTo(...p[0]);for(const q of p.slice(1))ctx.lineTo(...q);ctx.stroke();const a=p[p.length-2],b=p[p.length-1],ang=Math.atan2(b[1]-a[1],b[0]-a[0]);ctx.beginPath();ctx.moveTo(b[0]-14*Math.cos(ang-.4),b[1]-14*Math.sin(ang-.4));ctx.lineTo(...b);ctx.lineTo(b[0]-14*Math.cos(ang+.4),b[1]-14*Math.sin(ang+.4));ctx.stroke();}
 ctx.setLineDash([]);
 for(const e of pe.filter(e=>e.type==='rectangle')){rounded(ctx,e.x,e.y,e.width,e.height,18);ctx.fillStyle=e.backgroundColor;ctx.fill();ctx.strokeStyle=e.strokeColor;ctx.lineWidth=2;ctx.stroke();}
 for(const e of pe.filter(e=>e.type==='text')){ctx.fillStyle=e.strokeColor;ctx.font=e.fontSize+'px "'+family+'"';ctx.textAlign=e.textAlign;ctx.textBaseline='top';e.text.split('\n').forEach((line,i)=>ctx.fillText(line,e.textAlign==='center'?e.x+e.width/2:e.x,e.y+i*e.fontSize*1.25));}
 ctx.restore();
}
fs.mkdirSync(path.join(base,'previews'),{recursive:true});
const canvases=[];for(const page of pages){const c=createCanvas(W,H),ctx=c.getContext('2d');draw(ctx,elements.filter(e=>page.elementIds.includes(e.id)),page.y);fs.writeFileSync(path.join(base,'previews','view-'+page.id+'.png'),c.toBuffer('image/png'));canvases.push(c);}
const contact=createCanvas(1310*2,790*4),cc=contact.getContext('2d');cc.fillStyle='#edf1f4';cc.fillRect(0,0,contact.width,contact.height);canvases.forEach((c,i)=>cc.drawImage(c,(i%2)*1310,Math.floor(i/2)*790,1310,790));fs.writeFileSync(path.join(base,'previews','all-views.png'),contact.toBuffer('image/png'));
fs.writeFileSync(path.join(base,'ai-simulation-architecture.excalidraw'),JSON.stringify({type:'excalidraw',version:2,source:'https://excalidraw.com',elements,appState:{viewBackgroundColor:'#ffffff',gridSize:null,scrollX:0,scrollY:0,zoom:{value:.4}},files:{}},null,2));
const qa={createdAt:'2026-09-27',pages:pages.map(p=>({id:p.id,title:p.title,bounds:{x:p.x,y:p.y,width:p.width,height:p.height},elements:p.elementIds.length})),elementCount:elements.length,cardCount:nodes.size,checks:['unique IDs','finite geometry','bound text fits','no card overlap','no label/card overlap','no free-text overlap','arrows avoid unrelated cards','valid endpoint bindings','page bounds'],errors};
fs.writeFileSync(path.join(base,'validation.json'),JSON.stringify(qa,null,2));console.log(JSON.stringify({pages:pages.length,elements:elements.length,cards:nodes.size,errors}));if(errors.length)process.exitCode=1;
