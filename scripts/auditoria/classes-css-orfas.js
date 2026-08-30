const fs=require('fs'),path=require('path');
const root='web/src';
function walk(d,acc=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,acc);else acc.push(p);}return acc;}
const files=walk(root);
const cssFiles=files.filter(f=>f.endsWith('.css'));
const declared=new Set();
for(const f of cssFiles){const t=fs.readFileSync(f,'utf8');for(const m of t.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g))declared.add(m[1]);}
const used=new Map(); const dyn=[];
for(const f of files.filter(f=>/\.(tsx|ts)$/.test(f))){
  const t=fs.readFileSync(f,'utf8');
  // className="a b c"  ou className={'a b'} ou className={`a b`}
  for(const m of t.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\}|\{'([^']*)'\})/g)){
    const raw=m[1]||m[2]||m[3]||'';
    if(/\$\{/.test(raw)){dyn.push([f,raw]);}
    for(const c of raw.replace(/\$\{[^}]*\}/g,' ').split(/\s+/).filter(Boolean)){
      if(!used.has(c))used.set(c,new Set()); used.get(c).add(f);
    }
  }
  // classes dentro de expressões ternárias: className={x ? 'a' : 'b'}
  for(const m of t.matchAll(/className=\{[^}]*\}/g)){
    for(const s of m[0].matchAll(/['"`]([a-z][\w -]*)['"`]/g)){
      for(const c of s[1].split(/\s+/).filter(Boolean)){ if(!used.has(c))used.set(c,new Set()); used.get(c).add(f);}
    }
  }
}
const faltando=[...used.entries()].filter(([c])=>!declared.has(c)).sort();
console.log('=== CLASSES USADAS E NAO DECLARADAS ('+faltando.length+') ===');
for(const [c,fs_] of faltando) console.log(c+'  <-  '+[...fs_].join(', '));
console.log('\n=== className COM INTERPOLACAO (o script nao cobre) ('+dyn.length+') ===');
for(const [f,r] of dyn) console.log(f+'  ::  '+r);
