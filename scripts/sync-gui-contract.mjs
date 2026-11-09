/**
 * Synchronizes referenced owner definitions into the GUI's OpenAPI 3.1 document.
 * Customer personalization, network workflows, model-free Performance and local presenter routes are projected from owner definitions; budget suggestions expose the owner-controlled allocation query.
 * Owner provenance is retained for repeatable refreshes; 3.0 nullable schemas are translated without changing payloads.
 */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parse} from '../frontend/node_modules/yaml/dist/index.js';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const target=path.join(root,'contracts/gui.openapi.json');
const documents=new Map();
function read(file){if(!documents.has(file))documents.set(file,parse(fs.readFileSync(file,'utf8')));return documents.get(file);}
function resolve(reference,base,chain){
  const [relative,pointer='']=reference.split('#');
  const file=path.resolve(path.dirname(base),relative||path.basename(base));
  if(!file.startsWith(root+path.sep))throw Error('Contract reference leaves project');
  const key=file+'#'+pointer;
  if(chain.includes(key))throw Error('Recursive owner schema requires a named definition: '+key);
  let node=read(file);
  for(const part of pointer.split('/').slice(1))node=node?.[part.replaceAll('~1','/').replaceAll('~0','~')];
  if(node===undefined)throw Error('Missing contract reference: '+reference);
  const expanded=visit(structuredClone(node),file,[...chain,key]);
  expanded['x-owner-contract']=path.relative(path.dirname(target),file).replaceAll('\\','/')+'#'+pointer;
  return expanded;
}
function visit(node,base,chain=[]){
  if(!node||typeof node!=='object')return node;
  if(Array.isArray(node))return node.map(v=>visit(v,base,chain));
  if(node['x-owner-contract'])return resolve(node['x-owner-contract'],target,chain);
  if(node.$ref && !(base===target && node.$ref.startsWith('#/')))return resolve(node.$ref,base,chain);
  const value=Object.fromEntries(Object.entries(node).map(([k,v])=>[k,visit(v,base,chain)]));
  if(value.required?.length===0)delete value.required;
  if(value.nullable){
    delete value.nullable;
    if(value.type)value.type=Array.isArray(value.type)?[...new Set([...value.type,'null'])]:[value.type,'null'];
    else return {anyOf:[value,{type:'null'}]};
  }
  if(value.exclusiveMinimum===true){value.exclusiveMinimum=value.minimum;delete value.minimum;}
  if(value.exclusiveMaximum===true){value.exclusiveMaximum=value.maximum;delete value.maximum;}
  return value;
}
const contract=visit(read(target),target);
contract.openapi='3.1.0';
const budgetDefaults=contract.paths['/ai/defaults'].get;
budgetDefaults.summary='Read fresh budget defaults or a recommended phase split for a new experiment';
budgetDefaults.parameters=[{name:'maxEvaluations',in:'query',required:false,schema:{type:'integer',minimum:4,maximum:1000},description:'Returns a recommended allocation including one baseline. Apply only exploration, refinement and validation to a new experiment; retain other edited budget values. Existing run extensions keep their original allocation.'}];
const workflowList=contract.paths['/ai/workflows']?.get;
if(workflowList){
  workflowList.parameters=[...(workflowList.parameters??[]).filter(p=>p.name!=='network'),{name:'network',in:'query',required:false,schema:{type:'string',enum:['SIMULATION','FEEDBACK']},description:'Filters by server-owned A2A network, including saved legacy workflows.'}];
}
for(const [route,item] of Object.entries(contract.paths))if(route.startsWith('/ai/workflows')){
  for(const network of ['simulation','feedback']){
    const targetRoute=route.replace('/ai/workflows','/ai/'+network+'/workflows');
    const scoped=structuredClone(item);
    if(scoped.get)scoped.get.parameters=(scoped.get.parameters??[]).filter(p=>p.name!=='network');
    if(network==='feedback'&&(route==='/ai/workflows'||route.endsWith('/scope-confirmations')||route.endsWith('/publication-previews')))delete scoped.post;
    if(Object.keys(scoped).some(k=>['get','post','put'].includes(k)))contract.paths[targetRoute]=scoped;
  }
}
const presenterFile=path.join(root,'contracts/presenter.openapi.json');
for(const route of Object.keys(read(presenterFile).paths)){
  const pointer='/paths/'+route.replaceAll('~','~0').replaceAll('/','~1');
  contract.paths[route]=resolve('./presenter.openapi.json#'+pointer,target,[]);
}
const performanceFile=path.join(root,'contracts/performance.openapi.json');
for(const route of Object.keys(read(performanceFile).paths)){
  const pointer='/paths/'+route.replaceAll('~','~0').replaceAll('/','~1');
  contract.paths['/ai'+route]=resolve('./performance.openapi.json#'+pointer,target,[]);
}
const customerFile=path.join(root,'contracts/personalization.openapi.json');
for(const route of Object.keys(read(customerFile).paths)){
  const pointer='/paths/'+route.replaceAll('~','~0').replaceAll('/','~1');
  const mapped=route.replace('/customer-personalization/{customerId}','/customer/personalization');
  const item=resolve('./personalization.openapi.json#'+pointer,target,[]);
  delete item['x-owner-contract'];
  for(const operation of Object.values(item))if(operation&&typeof operation==='object'&&operation.parameters)operation.parameters=operation.parameters.filter(p=>p.name!=='customerId');
  contract.paths[mapped]=item;
}
const offerReview=resolve('./marketing-offers.openapi.yaml#/paths/~1customers~1{customerId}~1offers~1{id}',target,[]);
delete offerReview['x-owner-contract'];offerReview.get.parameters=offerReview.get.parameters.filter(p=>p.name!=='customerId');
contract.paths['/customer/offer-review/{id}']=offerReview;
for(const [route,item] of Object.entries(contract.paths))for(const method of ['get','post','put']){
  const operation=item[method];if(!operation)continue;
  operation.summary??=operation.description?.split(/\.\s/)[0]?.slice(0,120) || method.toUpperCase()+' '+route;
  operation.operationId='gui_'+method+'_'+route.replace(/[{}]/g,'').replace(/[^a-zA-Z0-9]/g,'_');
  if(method!=='get')operation.parameters=[...(operation.parameters??[]).filter(p=>p.name!=='X-CSRF-Token'),{name:'X-CSRF-Token',in:'header',required:true,schema:{type:'string'}}];
}
const output=JSON.stringify(contract,null,2)+'\n';
if(process.argv.includes('--check')){if(fs.readFileSync(target,'utf8')!==output)throw Error('Owner contracts changed. Run node scripts/sync-gui-contract.mjs and review the contract diff.');}
else fs.writeFileSync(target,output);
console.log('Synchronized '+Object.keys(contract.paths).length+' browser API paths and owner schemas.');

