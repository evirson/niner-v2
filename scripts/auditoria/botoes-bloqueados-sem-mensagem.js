const fs=require('fs'),path=require('path');
function walk(d,acc=[]){for(const f of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,f.name);if(f.isDirectory())walk(p,acc);else if(/\.tsx$/.test(f.name))acc.push(p)}return acc}
const vistos=new Map();
for(const f of walk('.')){
  const s=fs.readFileSync(f,'utf8');
  const rel=path.relative('.',f).split(path.sep).join('/');
  for(const m of s.matchAll(/disabled=\{!(\w{4,})\b/g)){
    const v=m[1];
    if(!/^(pode|tem|ha|permite|valido|esta)/i.test(v)) continue;
    // ha explicacao? procuramos "!VAR &&" seguido de JSX, ou a palavra "motivo" no arquivo
    const padraoMsg = new RegExp('!' + v + '[\s\S]{0,40}&&[\s\S]{0,40}<');
    const temMensagem = padraoMsg.test(s) || /motivo|Motivo/.test(s);
    if(!temMensagem) vistos.set(rel+'|'+v, {rel, v});
  }
}
console.log('BOTOES BLOQUEADOS POR CONDICAO COMPOSTA, SEM MENSAGEM APARENTE ('+vistos.size+'):');
for(const {rel,v} of vistos.values()) console.log('  - '+rel+'  ->  disabled={!'+v+'}');
