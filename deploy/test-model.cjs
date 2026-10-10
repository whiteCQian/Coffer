// Deterministic acceptance fixture; no user content logging or external requests.
const http = require('node:http');
http.createServer(async (req, res) => {
  let raw = '';
  for await (const chunk of req) { raw += chunk; if (raw.length > 1000000) { res.writeHead(413).end(); return; } }
  let body;
  try { body = raw ? JSON.parse(raw) : {}; } catch { res.writeHead(400).end(); return; }
  if (req.url === '/v1/embeddings') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({object: 'list', model: body.model, data: [{object:'embedding', index:0, embedding:Array(body.dimensions || 1024).fill(0.01)}], usage:{prompt_tokens:1,total_tokens:1}}));
    return;
  }
  if (req.url === '/v1/chat/completions') {
    const content = JSON.stringify({summary:'Acceptance fixture', category:'OTHER', tags:['acceptance']});
    if (body.stream) {
      res.setHeader('Content-Type','text/event-stream');
      for (const choice of [{index:0,delta:{role:'assistant',content},finish_reason:null}, {index:0,delta:{},finish_reason:'stop'}]) {
        res.write('data: ' + JSON.stringify({id:'fixture',object:'chat.completion.chunk',created:1,model:body.model,choices:[choice]}) + '\n\n');
      }
      res.end('data: [DONE]\n\n');
    } else {
      res.setHeader('Content-Type','application/json');
      res.end(JSON.stringify({id:'fixture',object:'chat.completion',created:1,model:body.model,choices:[{index:0,message:{role:'assistant',content},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}}));
    }
    return;
  }
  res.writeHead(404).end();
}).listen(8088,'0.0.0.0');
