const fs=require('fs'),path=require('path');
function walk(d,acc=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,acc);else acc.push(p);}return acc;}
function corpoBalanceado(t,i){ // i = indice do '(' de abertura
  let n=0;for(let j=i;j<t.length;j++){const c=t[j];if(c==='(')n++;else if(c===')'){n--;if(n===0)return t.slice(i+1,j);}}return null;
}
function tiraAnotacoes(s){
  let out='',i=0;
  while(i<s.length){
    if(s[i]==='@'){
      let j=i+1;while(j<s.length&&/[\w.]/.test(s[j]))j++;
      while(j<s.length&&/\s/.test(s[j]))j++;
      if(s[j]==='('){const c=corpoBalanceado(s,j);j=j+ (c===null?1:c.length+2);}
      i=j;continue;
    }
    out+=s[i++];
  }
  return out;
}
function dividirNivel0(s){
  const partes=[];let n=0,cur='';
  for(const c of s){
    if(c==='<'||c==='('||c==='[')n++;
    else if(c==='>'||c===')'||c===']')n--;
    if(c===','&&n===0){partes.push(cur);cur='';}else cur+=c;
  }
  if(cur.trim())partes.push(cur);
  return partes;
}
const javaFiles=walk('api/src/main/java').filter(f=>f.endsWith('.java'));
const records=new Map();
for(const f of javaFiles){
  const t=fs.readFileSync(f,'utf8');
  const re=/\brecord\s+([A-Z]\w*)\s*(?:<[^>]*>)?\s*\(/g;let m;
  while((m=re.exec(t))){
    const nome=m[1];const abre=t.indexOf('(',m.index+m[0].length-1);
    const corpo=corpoBalanceado(t,abre);if(corpo===null)continue;
    const campos=new Set();
    for(const parte of dividirNivel0(tiraAnotacoes(corpo))){
      const p=parte.trim();if(!p)continue;
      const toks=p.split(/\s+/);const nc=toks[toks.length-1].replace(/[^\w]/g,'');
      if(nc&&/^[a-z_]/.test(nc))campos.add(nc);
    }
    if(!records.has(nome))records.set(nome,campos);else for(const c of campos)records.get(nome).add(c);
  }
}
const tsFiles=walk('web/src/lib').filter(f=>f.endsWith('.ts'));
const problemas=[];let casados=0;
for(const f of tsFiles){
  const t=fs.readFileSync(f,'utf8');
  const re=/export\s+(?:interface|type)\s+([A-Z]\w*)\s*=?\s*\{([^}]*)\}/gs;let m;
  while((m=re.exec(t))){
    const nome=m[1],corpo=m[2];
    const alvo=[nome,nome+'Response',nome+'Request',nome+'Dto'].find(n=>records.has(n));
    if(!alvo)continue;
    casados++;
    const jc=records.get(alvo);
    const tsCampos=[...corpo.matchAll(/^\s*(\w+)\??\s*:/gm)].map(x=>x[1]);
    const faltando=tsCampos.filter(c=>!jc.has(c));
    if(faltando.length)problemas.push({nome:nome+" -> "+alvo,f,faltando,java:[...jc]});
  }
}
console.log('Records Java: '+records.size+' | tipos TS casados por nome: '+casados);
console.log('=== TS declara campo que o record Java NAO tem ('+problemas.length+') ===');
for(const p of problemas)console.log(p.nome+'  ('+p.f+')\n   TS-so: '+p.faltando.join(', ')+'\n   Java : '+p.java.join(', ')+'\n');
