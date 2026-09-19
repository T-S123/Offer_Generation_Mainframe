/** Builds editable Excalidraw architecture sections and local validation previews from the supplied visual reference and source-linked content. */
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {sources,sections}=require('./diagram-content.cjs');
const root=path.resolve(__dirname,'../..');
const reference=JSON.parse(fs.readFileSync(process.argv[2]||'C:/Users/tanek/Downloads/rao.excalidraw','utf8'));
const {createCanvas,GlobalFonts}=require('C:/Users/tanek/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@napi-rs/canvas');
const qa=path.join(__dirname,'qa');fs.mkdirSync(qa,{recursive:true});
const fontFile=path.join(qa,'Virgil.woff2');
if(fs.existsSync(fontFile))GlobalFonts.registerFromPath(fontFile,'Virgil');
const font=fs.existsSync(fontFile)?'Virgil':'Comic Sans MS';
const measure=createCanvas(20,20).getContext('2d');
const rectTemplate=reference.elements.find(
    /** Selects the reference card shape used by generated sections. */
    e=>e.id==='01-click');
const textTemplate=reference.elements.find(
    /** Selects the reference text element used by generated cards. */
    e=>e.id==='01-click-text');
const arrowTemplate=reference.elements.find(
    /** Selects arrow elements for routing or validation. */
    e=>e.type==='arrow'&&!e.isDeleted);
const colors={ui:'#b2f2bb',backend:'#ffe99a',external:'#ffc9c9'};
const elements=[],nodes=new Map(),bounds=new Map(),sourceIndex=[];
let serial=0;
/** Derives a stable numeric drawing seed from an element identifier. */
const seed=s=>parseInt(crypto.createHash('sha256').update(s).digest('hex').slice(0,7),16);

/** Copies a reference element and resets its identity, bindings and mutable drawing metadata. */
function fresh(template,id){const e=structuredClone(template);delete e.index;delete e.created;delete e.customData;return Object.assign(e,{id,version:1,versionNonce:seed(id+'nonce'),seed:seed(id),updated:Date.now(),isDeleted:false,groupIds:[],boundElements:[],frameId:null,link:null,locked:false});}

/** Measures text width using the reference font at the requested size. */
function width(s,size){measure.font=`${size}px "${font}"`;return measure.measureText(s).width;}

/** Wraps text into measured lines that fit the available card or label width. */
function wrap(input,size,max){const lines=[];for(const paragraph of input.split('\n')){if(!paragraph){lines.push('');continue;}let line='';for(const word of paragraph.split(' ')){if(width((line?line+' ':'')+word,size)<=max){line+=(line?' ':'')+word;continue;}if(line){lines.push(line);line='';}if(width(word,size)<=max){line=word;continue;}let part='';for(const ch of word){if(width(part+ch,size)>max){lines.push(part);part='';}part+=ch;}line=part;}if(line)lines.push(line);}return lines;}

/** Creates a text element with reference typography and records it in the drawing. */
function text(id,value,x,y,size=22,max=3100,container=null){const lines=wrap(value,size,max),w=Math.max(...lines.map(
    /** Measures a wrapped line to determine its text element width. */
    s=>width(s,size)),1),h=lines.length*size*1.25;const e=fresh(textTemplate,id);Object.assign(e,{x,y,width:w,height:h,fontSize:size,fontFamily:1,text:lines.join('\n'),originalText:lines.join('\n'),textAlign:container?'center':'left',verticalAlign:container?'middle':'top',containerId:container,autoResize:true,lineHeight:1.25});elements.push(e);return e;}

/** Creates a colored architecture card, centers its text and attaches implementation source references. */
function card(id,role,value,x,y,src=[],h=270){const e=fresh(rectTemplate,id);Object.assign(e,{x,y,width:400,height:h,backgroundColor:colors[role]});const t=text(id+'-text',value,0,0,22,360,id);if(t.height>h-24)throw new Error(`${id}: ${t.height} text height exceeds card ${h}`);t.x=x+(400-t.width)/2;t.y=y+(h-t.height)/2;e.boundElements=[{id:t.id,type:'text'}];e.customData={step:id.split('-')[0],ownership:role,sourceFiles:src};elements.splice(elements.length-1,0,e);nodes.set(id,e);sourceIndex.push({id,text:t.originalText,sources:src});return e;}

/** Checks whether an orthogonal connector segment crosses a padded card boundary. */
function intersects(a,b,r){const pad=3;if(a[0]===b[0])return a[0]>r.x-pad&&a[0]<r.x+r.width+pad&&Math.max(a[1],b[1])>r.y-pad&&Math.min(a[1],b[1])<r.y+r.height+pad;if(a[1]===b[1])return a[1]>r.y-pad&&a[1]<r.y+r.height+pad&&Math.max(a[0],b[0])>r.x-pad&&Math.min(a[0],b[0])<r.x+r.width+pad;return true;}

/** Returns the connector attachment point on the requested side of a card. */
function port(n,side){return side==='R'?[n.x+n.width+8,n.y+n.height/2]:side==='L'?[n.x-8,n.y+n.height/2]:side==='T'?[n.x+n.width/2,n.y-8]:[n.x+n.width/2,n.y+n.height+8];}

/** Finds an orthogonal connector path around intervening cards. */
function route(a,b,obstacles){const dx=b.x-a.x,dy=b.y-a.y;let sa,sb;if(Math.abs(dy)<50){sa=dx>0?'R':'L';sb=dx>0?'L':'R';}else{sa=dy>0?'B':'T';sb=dy>0?'T':'B';}const start=port(a,sa),end=port(b,sb);
    /** Checks whether a connector segment avoids all routing obstacles. */
    const clear=(p,q)=>!obstacles.some(
        /** Checks whether a connector segment intersects this obstacle. */
        r=>intersects(p,q,r));
 const direct=[start,end];if((start[0]===end[0]||start[1]===end[1])&&clear(start,end))return {points:direct,sa,sb};
 const xs=[...new Set([start[0],end[0],...obstacles.flatMap(
    /** Collects horizontal routing positions around the obstacle. */
    n=>[n.x-40,n.x+n.width+40,n.x+n.width/2])])].sort(
    /** Orders routing candidates by coordinate, distance or available segment length. */
    (a,b)=>a-b);
 const ys=[...new Set([start[1],end[1],...obstacles.flatMap(
    /** Collects vertical routing positions around the obstacle. */
    n=>[n.y-55,n.y+n.height+55,n.y+n.height/2])])].sort(
    /** Orders routing candidates by coordinate, distance or available segment length. */
    (a,b)=>a-b);
 /** Encodes grid coordinates as a routing-search key. */
 const index=(x,y)=>`${x},${y}`,first=index(xs.indexOf(start[0]),ys.indexOf(start[1])),last=index(xs.indexOf(end[0]),ys.indexOf(end[1]));
 const dist=new Map([[first,0]]),prev=new Map(),queue=[{k:first,d:0}],visited=new Set();
 while(queue.length){queue.sort(
    /** Orders routing candidates by coordinate, distance or available segment length. */
    (a,b)=>a.d-b.d);const q=queue.shift();if(visited.has(q.k))continue;visited.add(q.k);if(q.k===last)break;const [i,j]=q.k.split(',').map(Number);for(const [ni,nj] of [[i-1,j],[i+1,j],[i,j-1],[i,j+1]]){if(ni<0||nj<0||ni>=xs.length||nj>=ys.length)continue;const k=index(ni,nj),p=[xs[i],ys[j]],v=[xs[ni],ys[nj]];if(!clear(p,v))continue;const nd=q.d+Math.abs(p[0]-v[0])+Math.abs(p[1]-v[1])+1;if(nd<(dist.get(k)??Infinity)){dist.set(k,nd);prev.set(k,q.k);queue.push({k,d:nd});}}}
 if(!dist.has(last))throw new Error(`Cannot route ${a.id} -> ${b.id}`);const result=[];for(let k=last;k;k=prev.get(k)){const [i,j]=k.split(',').map(Number);result.push([xs[i],ys[j]]);}result.reverse();return {points:simplify(result),sa,sb};}

/** Removes intermediate collinear points from a connector path. */
function simplify(p){return p.filter(
    /** Keeps only connector endpoints and changes of direction. */
    (q,i)=>!i||i===p.length-1||!((p[i-1][0]===q[0]&&q[0]===p[i+1][0])||(p[i-1][1]===q[1]&&q[1]===p[i+1][1])));}

/** Creates a bound arrow and places an optional plain-text label without overlapping drawing elements. */
function arrow(a,b,p,sa,sb,label='',dashed=false){const id='edge-'+(++serial),e=fresh(arrowTemplate,id);const p0=p[0];Object.assign(e,{x:p0[0],y:p0[1],width:Math.max(...p.map(
    /** Selects the horizontal coordinate from a point. */
    x=>x[0]))-Math.min(...p.map(
    /** Selects the horizontal coordinate from a point. */
    x=>x[0])),height:Math.max(...p.map(
    /** Selects the vertical coordinate from a point. */
    x=>x[1]))-Math.min(...p.map(
    /** Selects the vertical coordinate from a point. */
    x=>x[1])),points:p.map(
    /** Converts an absolute connector point into element-relative coordinates. */
    x=>[x[0]-p0[0],x[1]-p0[1]]),strokeStyle:dashed?'dashed':'solid',startBinding:{elementId:a.id,mode:'orbit',fixedPoint:({L:[0,.5001],R:[1,.5001],T:[.5001,0],B:[.5001,1]})[sa]},endBinding:{elementId:b.id,mode:'orbit',fixedPoint:({L:[0,.5001],R:[1,.5001],T:[.5001,0],B:[.5001,1]})[sb]},startArrowhead:null,endArrowhead:'arrow',elbowed:false});elements.push(e);a.boundElements.push({id,type:'arrow'});b.boundElements.push({id,type:'arrow'});if(label){const segments=p.slice(1).map(
    /** Measures a connector segment for annotation placement. */
    (v,i)=>({u:p[i],v,length:Math.abs(v[0]-p[i][0])})).sort(
    /** Orders routing candidates by coordinate, distance or available segment length. */
    (a,b)=>b.length-a.length);const t=text(id+'-label',label,0,0,18,Math.max(80,Math.min(360,segments[0].length-16)));let found=false;for(const {u,v}of segments){if(u[1]!==v[1])continue;for(const fraction of [.5,.25,.75,.1,.9]){for(const offset of [-t.height-12,12]){const box={x:u[0]+(v[0]-u[0])*fraction-t.width/2,y:u[1]+offset,width:t.width,height:t.height};if(elements.some(
    /** Checks whether the candidate annotation overlaps an existing drawing element. */
    other=>other!==t&&other.type!=='arrow'&&box.x<other.x+other.width+4&&box.x+box.width>other.x-4&&box.y<other.y+other.height+4&&box.y+box.height>other.y-4))continue;t.x=box.x;t.y=box.y;found=true;break;}if(found)break;}if(found)break;}if(!found)throw new Error('Cannot place connector annotation: '+label);}return e;}

/** Routes and creates an arrow connecting two architecture cards. */
function link(a,b,obstacles,label='',dashed=false){const r=route(a,b,obstacles);arrow(a,b,r.points,r.sa,r.sb,label,dashed);}

/** Lays out one architecture section with connected flow cards, rule details and source notes. */
function buildSection(s,x,y){const allSources=[...new Set(s.source.flatMap(
    /** Expands a source group into its implementation file paths. */
    k=>sources[k]))];text(s.id+'-title',s.title,x,y,36,3140);text(s.id+'-subtitle',s.subtitle,x,y+65,20,3140);text(s.id+'-legend','Green = UI   Yellow = backend   Red = database / separate service',x,y+105,20,3100);
 const y0=y+160,flow=[];s.flow.forEach(
    /** Creates and positions the next source-linked card in this section. */
    (n,i)=>{let row=Math.floor(i/6),col=row%2?5-i%6:i%6;flow.push(card(s.id+'-'+n.key,n.role,n.text,x+30+col*530,y0+row*430,sources[n.source]||allSources));});
 const edges=s.id==='step4'?[[0,1],[1,2],[2,3],[3,4],[4,5],[5,8],[6,1,'Recheck',true],[7,1,'Current read',true]]:s.id==='step5'?[...Array.from({length:13},
    /** Builds adjacent card pairs for the ordered section flow. */
    (_,i)=>[i,i+1]),[6,14,'No improvement',true],[14,13,'Original only',true],[15,0,'HTTP recovery',true]]:s.id==='step6'?[[0,1],[1,2],[2,3],[3,4],[4,5],[5,6],[5,7],[8,6,'HTTP catch-up',true],[8,7,'HTTP catch-up',true],[6,9],[9,10],[4,11,'Step 7 feed',true]]:Array.from({length:flow.length-1},
    /** Builds adjacent card pairs for the ordered section flow. */
    (_,i)=>[i,i+1]);
 for(const [a,b,label,dashed]of edges)link(flow[a],flow[b],flow,label||'',dashed||false);
 const flowBottom=y0+(Math.ceil(flow.length/6)-1)*430+270;
 text(s.id+'-details-title',s.detailsTitle,x,flowBottom+100,28,3100);const detailStart=flowBottom+170;
 const details=s.details.map(
    /** Creates and positions the next source-linked card in this section. */
    (n,i)=>card(s.id+'-detail-'+n.key,n.role,n.text,x+30+(i%6)*530,detailStart+Math.floor(i/6)*330,sources[n.source]||allSources));
 if(s.id==='step5'){
  for(let i=0;i<5;i++)link(details[i],details[i+1],details);
  const trained=details[4],apply=flow[3],start=port(trained,'B'),end=port(apply,'T'),side=x+3270;
  arrow(trained,apply,[start,[start[0],trained.y+trained.height+30],[side,trained.y+trained.height+30],[side,y0-20],[end[0],y0-20],end],'B','T','Apply saved model',true);
 }
 const end=detailStart+(Math.ceil(s.details.length/6)-1)*330+270;
 const footer=text(s.id+'-note',s.note,x,end+45,20,3080);text(s.id+'-sources','Source details and exact payload example: architecture-guide.md',x,end+footer.height+65,18,3080);
 const result={id:s.id,x,y,width:3110,height:end+footer.height+125-y,flowBottom,flow,flowStart:y0};bounds.set(s.id,result);return result;
}
text('title','Lending Intelligence Engine - end-to-end architecture',50,30,48,6500);
text('scope','Current local implementation | Customer Steps 1-8 + isolated Business Simulation | Source trace: 18 September 2026',50,110,24,6500);
for(const [i,role,label]of [[0,'ui','Green = frontend / UI actions'],[1,'backend','Yellow = backend processing / endpoints'],[2,'external','Red = database / Kafka / separate local services']]){const e=fresh(rectTemplate,'legend-'+role);Object.assign(e,{x:50+i*2140,y:175,width:2010,height:72,backgroundColor:colors[role]});elements.push(e);text('legend-'+role+'-text',label,e.x+24,e.y+18,28,1940);}
text('reading','Read each flow left to right, then follow arrows onto the next row. Detail cards below each flow explain rules, fields and failure behavior.',50,285,24,6500);
text('boundary','All terminal actions use HTTP. Separate Java services share local PostgreSQL through owned tables/schemas; domain policy executes in COBOL.',50,330,24,6500);
let top=450;
for(let i=0;i<sections.length;i+=2){const left=buildSection(sections[i],50,top);const right=i+1<sections.length?buildSection(sections[i+1],3500,top):null;top+=Math.max(left.height,right?.height||0)+210;}
const handoff={step1:'handoff',step2:'handoff',step3:'handoff',step4:'handoff',step5:'store',step6:'handoff',step7:'handoff'};
for(let i=0;i<7;i++){const a=bounds.get('step'+(i+1)),b=bounds.get('step'+(i+2));const an=nodes.get(a.id+'-'+handoff[a.id]),bn=b.flow[0];const start=port(an,'B'),end=port(bn,'L');let p;
 if(a.y===b.y)p=[start,[start[0],a.flowBottom+60],[3330,a.flowBottom+60],[3330,end[1]],end];
 else p=[start,[start[0],a.flowBottom+60],[6740,a.flowBottom+60],[6740,b.y-80],[15,b.y-80],[15,end[1]],end];
 arrow(an,bn,simplify(p),'B','L','Continue to Step '+(i+2),true);}

const pub=nodes.get('business-catalog'),target=nodes.get('step2-catalog'),s=port(pub,'B'),t=port(target,'T');
const catalogEntryY=bounds.get('step2').flowStart-20;
arrow(pub,target,[s,[s[0],bounds.get('business').flowBottom+70],[6920,bounds.get('business').flowBottom+70],[6920,catalogEntryY],[t[0],catalogEntryY],t],'B','T','Published offers and rules',true);
const artifact={type:'excalidraw',version:2,source:'https://excalidraw.com',elements,appState:{...reference.appState,viewBackgroundColor:'#ffffff'},files:{}};
fs.writeFileSync(path.join(__dirname,'lending-intelligence-engine.excalidraw'),JSON.stringify(artifact,null,2)+'\n');
fs.writeFileSync(path.join(qa,'source-index.json'),JSON.stringify(sourceIndex,null,2)+'\n');
fs.writeFileSync(path.join(qa,'section-bounds.json'),JSON.stringify([...bounds.values()].map(
    /** Serializes section bounds without retaining live card objects. */
    ({flow,...b})=>b),null,2)+'\n');

/** Renders a selected diagram region to a PNG image for visual inspection. */
function render(file,box,scale){const canvas=createCanvas(Math.ceil(box.width*scale),Math.ceil(box.height*scale)),g=canvas.getContext('2d');g.fillStyle='#fff';g.fillRect(0,0,canvas.width,canvas.height);g.scale(scale,scale);g.translate(-box.x,-box.y);for(const e of elements){g.globalAlpha=e.opacity/100;if(e.type==='rectangle'){if(e.x+e.width<box.x||e.x>box.x+box.width||e.y+e.height<box.y||e.y>box.y+box.height)continue;g.fillStyle=e.backgroundColor;g.strokeStyle=e.strokeColor;g.lineWidth=e.strokeWidth;g.beginPath();g.roundRect(e.x,e.y,e.width,e.height,14);g.fill();g.stroke();}else if(e.type==='text'){g.fillStyle=e.strokeColor;g.font=`${e.fontSize}px "${font}"`;g.textBaseline='middle';g.textAlign=e.textAlign;const x=e.textAlign==='center'?e.x+e.width/2:e.x;e.text.split('\n').forEach(
    /** Draws one measured text line at its vertical position. */
    (line,i)=>g.fillText(line,x,e.y+e.fontSize*.625+i*e.fontSize*1.25));}else if(e.type==='arrow'){const ps=e.points.map(
    /** Converts an element-relative point into an absolute diagram coordinate. */
    p=>[p[0]+e.x,p[1]+e.y]);g.strokeStyle=e.strokeColor;g.lineWidth=e.strokeWidth;g.setLineDash(e.strokeStyle==='dashed'?[8,8]:[]);g.beginPath();ps.forEach(
    /** Adds the connector point to the preview path. */
    (p,i)=>i?g.lineTo(...p):g.moveTo(...p));g.stroke();g.setLineDash([]);const a=ps.at(-2),b=ps.at(-1),angle=Math.atan2(b[1]-a[1],b[0]-a[0]);g.beginPath();g.moveTo(b[0]-13*Math.cos(angle-.45),b[1]-13*Math.sin(angle-.45));g.lineTo(...b);g.lineTo(b[0]-13*Math.cos(angle+.45),b[1]-13*Math.sin(angle+.45));g.stroke();}}
 fs.writeFileSync(path.join(qa,file),canvas.toBuffer('image/png'));}
for(const b of bounds.values())render(b.id+'.png',{x:b.x-25,y:b.y-20,width:3200,height:b.height+40},.65);
render('overview.png',{x:0,y:0,width:7000,height:top},.17);
const errors=[];const ids=new Set(elements.map(
    /** Selects the element identifier for reference validation. */
    e=>e.id));if(ids.size!==elements.length)errors.push('Duplicate element IDs');
for(const e of elements){if(e.type==='text'&&e.containerId){const n=nodes.get(e.containerId);if(!n||e.x<n.x||e.y<n.y||e.x+e.width>n.x+n.width||e.y+e.height>n.y+n.height)errors.push('Text overflow: '+e.id);}for(const b of e.boundElements||[])if(!ids.has(b.id))errors.push('Missing binding: '+e.id);}
const sourceHashes={};
for(const [key,files]of Object.entries(sources))for(const file of files){if(!fs.existsSync(path.join(root,file)))errors.push('Missing source: '+key+' '+file);else sourceHashes[file]=crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex');}
fs.writeFileSync(path.join(qa,'source-hashes.json'),JSON.stringify(sourceHashes,null,2)+'\n');
for(const e of elements.filter(
    /** Selects arrow elements for routing or validation. */
    e=>e.type==='arrow')){const points=e.points.map(
    /** Converts an element-relative point into an absolute diagram coordinate. */
    q=>[q[0]+e.x,q[1]+e.y]);for(let i=1;i<points.length;i++)for(const n of nodes.values()){if(intersects(points[i-1],points[i],{x:n.x+3,y:n.y+3,width:n.width-6,height:n.height-6}))errors.push('Arrow crosses box: '+e.id+' / '+n.id);}}
const guide=fs.readFileSync(path.join(__dirname,'architecture-guide.md'),'utf8');for(const match of guide.matchAll(/\]\(([^)]+)\)/g)){if(!match[1].includes('://')&&!fs.existsSync(path.resolve(__dirname,match[1].split('#')[0])))errors.push('Missing guide link: '+match[1]);}
const payload=JSON.parse(fs.readFileSync(path.join(__dirname,'qualification-response.example.json')));
const schema=JSON.parse(fs.readFileSync(path.join(root,'contracts/decision-response.schema.json')));

/** Checks the example payload against the JSON Schema constructs used by its qualification contract. */
function conforms(v,s){if(s.$ref){if(!s.$ref.startsWith('#/'))throw new Error('Unexpected external schema in example');let target=schema;for(const key of s.$ref.slice(2).split('/'))target=target[key];return conforms(v,target);}if(s.anyOf)return s.anyOf.some(
    /** Validates the nested value against its corresponding schema constraint. */
    t=>conforms(v,t));if(s.allOf&&!s.allOf.every(
    /** Validates the nested value against its corresponding schema constraint. */
    t=>conforms(v,t)))return false;if(s.const!==undefined&&v!==s.const)return false;if(s.enum&&!s.enum.includes(v))return false;const type=v===null?'null':Array.isArray(v)?'array':typeof v;if(s.type&&!([s.type].flat().some(
    /** Checks whether the value matches an allowed JSON Schema type. */
    t=>t===type||(t==='integer'&&type==='number'&&Number.isInteger(v)))))return false;
 if(type==='object'){if((s.required||[]).some(
    /** Checks whether a required schema property is missing from the object. */
    k=>!(k in v)))return false;if(s.additionalProperties===false&&Object.keys(v).some(
    /** Checks that an object field is allowed by the declared schema. */
    k=>!(k in(s.properties||{}))))return false;return Object.entries(v).every(
    /** Validates the nested value against its corresponding schema constraint. */
    ([k,val])=>!s.properties?.[k]||conforms(val,s.properties[k]));}
 if(type==='array')return !s.items||v.every(
    /** Validates the nested value against its corresponding schema constraint. */
    x=>conforms(x,s.items));if(type==='number')return !(s.minimum!==undefined&&v<s.minimum||s.maximum!==undefined&&v>s.maximum||s.multipleOf&&Math.abs(v/s.multipleOf-Math.round(v/s.multipleOf))>1e-6);if(type==='string'){if(s.minLength!==undefined&&v.length<s.minLength||s.maxLength!==undefined&&v.length>s.maxLength||s.pattern&&!new RegExp(s.pattern).test(v))return false;if(s.format==='uuid'&&!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v))return false;if(['date','date-time'].includes(s.format)&&Number.isNaN(Date.parse(v)))return false;}return true;}
if(!conforms(payload,schema))errors.push('Qualification example violates declared contract constraints');
const report={sections:sections.length,elements:elements.length,cards:nodes.size,arrows:elements.filter(
    /** Selects arrow elements for routing or validation. */
    e=>e.type==='arrow').length,referenceFontFamily:1,previewFont:font,checkedSourceFiles:Object.keys(sourceHashes).length,checkedGuideLinks:true,checkedArrowBoxCrossings:true,checkedQualificationExample:true,errors};
fs.writeFileSync(path.join(qa,'validation.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));if(errors.length)process.exitCode=1;
