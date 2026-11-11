// Embed only the non-secret default entry, never the full private policy.
export function configureWebScript(source,defaultOrigin){
  const origin=new URL(defaultOrigin);
  if(!['http:','https:'].includes(origin.protocol)||origin.origin!==defaultOrigin)throw new Error('打包默认入口必须是规范 HTTP(S) Origin');
  const marker='const packagedDefaultOrigin=null;';
  if(source.split(marker).length!==2)throw new Error('前端默认入口配置标记不唯一或缺失');
  return source.replace(marker,'const packagedDefaultOrigin='+JSON.stringify(defaultOrigin)+';');
}
