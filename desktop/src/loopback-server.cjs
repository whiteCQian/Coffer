'use strict';
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { regularFile, within } = require('./safe-paths.cjs');
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'";
const TYPES = {'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2','.ico':'image/x-icon'};
class LoopbackServer {
  constructor(webRoot, shellRoot, backend) { this.webRoot=webRoot; this.shellRoot=shellRoot; this.backend=backend; this.capability=crypto.randomBytes(32).toString('hex'); }
  async start() {
    this.server=http.createServer((req,res) => this.handle(req,res));
    await new Promise((resolve,reject) => { this.server.once('error',reject); this.server.listen(0,'127.0.0.1',resolve); });
    this.origin=`http://127.0.0.1:${this.server.address().port}`; return this.origin;
  }
  handle(req,res) {
    const securityHeaders={'Content-Security-Policy':CSP,'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Cross-Origin-Resource-Policy':'same-origin','X-Frame-Options':'DENY','Cache-Control':'no-store'};
    for (const [key,value] of Object.entries(securityHeaders)) res.setHeader(key,value);
    const supplied=req.headers['x-coffer-window-capability'];
    const expected=Buffer.from(this.capability);
    if (typeof supplied!=='string' || !/^[a-f0-9]{64}$/.test(supplied) || !crypto.timingSafeEqual(expected,Buffer.from(supplied))
        || req.headers.host!==new URL(this.origin).host || req.headers.origin && req.headers.origin!==this.origin) {
      res.writeHead(403,{'Content-Type':'application/json'}); res.end(JSON.stringify({code:403,msg:'请从受信桌面窗口访问',data:null})); return;
    }
    let url; try { url=new URL(req.url,this.origin); } catch { res.writeHead(400); res.end(); return; }
    if (url.origin!==this.origin) { res.writeHead(403); res.end(); return; }
    if (url.pathname.startsWith('/api/')) {
      if (url.pathname.startsWith('/api/desktop/native-inbox')) { res.writeHead(403); res.end(); return; }
      const backend=this.backend();
      if (!backend) { res.writeHead(503,{'Content-Type':'application/json'}); res.end(JSON.stringify({code:503,msg:'本地后台未就绪，请在启动页重启',data:null})); return; }
      const headers={'X-Coffer-Desktop-Token':backend.token};
      for (const name of ['content-type','content-length','cookie','x-xsrf-token','x-coffer-model-version','x-coffer-allow-sensitive','idempotency-key','range','if-range']) if(req.headers[name]) headers[name]=req.headers[name];
      if (Number(headers['content-length'] ?? 0)>34*1024*1024) { res.writeHead(413); res.end(); return; }
      const upstream=http.request({hostname:'127.0.0.1',port:backend.port,path:url.pathname+url.search,method:req.method,headers,timeout:60000}, response => {
        const forwarded={};
        for(const name of ['content-type','content-length','content-range','accept-ranges','etag','set-cookie','content-disposition','cache-control']) if(response.headers[name]) forwarded[name]=response.headers[name];
        res.writeHead(response.statusCode,forwarded); response.pipe(res);
      });
      upstream.on('timeout',()=>upstream.destroy()); upstream.on('error',()=>{if(!res.headersSent)res.writeHead(503);res.end();});
      let length=0; req.on('data', chunk=>{length+=chunk.length;if(length>34*1024*1024){upstream.destroy();if(!res.headersSent)res.writeHead(413);res.end();}});
      req.on('aborted',()=>upstream.destroy()); req.pipe(upstream); return;
    }
    if (!['GET','HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return; }
    let root=this.webRoot, relative;
    try {
      const decoded=decodeURIComponent(url.pathname);
      if (decoded.includes('\\') || decoded.includes('\0') || decoded.split('/').includes('..')) throw new Error();
      if (decoded.startsWith('/__desktop/')) {root=this.shellRoot;relative=decoded.slice('/__desktop/'.length);if(relative==='start')relative='startup.html';}
      else relative=decoded.slice(1) || 'index.html';
      let file=path.resolve(root,relative);
      if (!within(root,file)) throw new Error();
      if (!fs.existsSync(file) && root===this.webRoot && !path.extname(relative)) file=path.join(root,'index.html');
      regularFile(file); res.writeHead(200,{'Content-Type':TYPES[path.extname(file)] || 'application/octet-stream'});
      if(req.method==='HEAD')res.end();else fs.createReadStream(file).on('error',()=>res.destroy()).pipe(res);
    } catch { res.writeHead(404); res.end(); }
  }
  async close() { if(this.server){this.server.closeAllConnections();await new Promise(resolve=>this.server.close(resolve));} }
}
module.exports={LoopbackServer,CSP};
