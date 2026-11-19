import json, pathlib, urllib.request, uuid
p=pathlib.Path('tmp/assignment/uploads.json')
items=json.loads(p.read_text(encoding='utf-8'))
out={}
for item in items:
    b='----codex'+uuid.uuid4().hex
    data=(f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{item["filename"]}"\r\nContent-Type: image/png\r\n\r\n'.encode()+pathlib.Path(item['path']).read_bytes()+f'\r\n--{b}--\r\n'.encode())
    headers=dict(item['upload_headers']);headers['Content-Type']='multipart/form-data; boundary='+b
    req=urllib.request.Request(item['upload_url'],data=data,headers=headers,method='POST')
    with urllib.request.urlopen(req,timeout=60) as r: out[item['key']]=json.load(r)
pathlib.Path('tmp/assignment/upload_results.json').write_text(json.dumps(out,ensure_ascii=False),encoding='utf-8')
print(json.dumps(out,ensure_ascii=False))
