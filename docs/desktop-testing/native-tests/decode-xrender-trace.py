from pathlib import Path
import struct,json,collections,hashlib,argparse
p=argparse.ArgumentParser();p.add_argument('trace',type=Path);p.add_argument('--render',type=int,default=140);p.add_argument('--shm',type=int,default=130);args=p.parse_args()
buf=args.trace.read_bytes();pos=0;requests=[];counts=collections.Counter();clients=collections.Counter();records=0
rendernames={4:'CreatePicture',5:'ChangePicture',6:'SetPictureClipRectangles',7:'FreePicture',8:'Composite',10:'Trapezoids',11:'Triangles',12:'TriStrip',13:'TriFan',17:'CreateGlyphSet',20:'AddGlyphs',23:'CompositeGlyphs8',24:'CompositeGlyphs16',25:'CompositeGlyphs32',26:'FillRectangles',28:'SetPictureTransform',30:'SetPictureFilter',32:'AddTraps',33:'CreateSolidFill',34:'CreateLinearGradient',35:'CreateRadialGradient',36:'CreateConicalGradient'}
corenames={53:'CreatePixmap',54:'FreePixmap',55:'CreateGC',56:'ChangeGC',57:'CopyGC',58:'SetDashes',59:'SetClipRectangles',60:'FreeGC',61:'ClearArea',62:'CopyArea',63:'CopyPlane',64:'PolyPoint',65:'PolyLine',66:'PolySegment',67:'PolyRectangle',68:'PolyArc',69:'FillPoly',70:'PolyFillRectangle',71:'PolyFillArc',72:'PutImage',73:'GetImage',74:'PolyText8',75:'PolyText16',76:'ImageText8',77:'ImageText16'}
while pos<len(buf):
    header=struct.unpack_from('<7I',buf,pos);pos+=28;magic,client,time,seq,category,swapped,size=header
    assert magic==0x58524331,(pos,header)
    data=buf[pos:pos+size];pos+=size;records+=1
    assert len(data)==size
    if category!=1:continue
    endian='>' if swapped else '<'
    q=0
    while q<len(data):
        opcode,minor,length=struct.unpack_from(endian+'BBH',data,q)
        total=length*4
        if not total:total=struct.unpack_from(endian+'I',data,q+4)[0]*4
        assert total>=4 and q+total<=len(data),(header,q,total,len(data))
        raw=data[q:q+total];q+=total
        name=('Render.'+rendernames.get(minor,str(minor))) if opcode==args.render else ('SHM.'+str(minor)) if opcode==args.shm else corenames.get(opcode,str(opcode))
        counts[name]+=1;clients[hex(client)]+=1
        rec={'client':hex(client),'time':time,'seq':seq,'name':name,'length':total,'raw':raw.hex()}
        u=lambda off,n=1:struct.unpack_from(endian+str(n)+'I',raw,off)
        s=lambda off,n=1:struct.unpack_from(endian+str(n)+'h',raw,off)
        if opcode==args.render:
            if minor==4:rec.update(zip(['picture','drawable','format','mask'],u(4,4)))
            elif minor in [5,6,7,28,30,32,33]:rec['picture']=u(4)[0]
            elif minor==8:
                rec['op']=raw[4];rec.update(zip(['src','mask','dst'],u(8,3)));rec['coords']=s(20,6);rec['size']=struct.unpack_from(endian+'HH',raw,32)
            elif minor in [10,11,12,13]:
                rec['op']=raw[4];rec.update(zip(['src','dst','maskFormat'],u(8,3)));rec['sourceOffset']=s(20,2)
                if minor==10:rec['trapezoids']=[[v/65536 for v in struct.unpack_from(endian+'10i',raw,o)] for o in range(24,len(raw),40)]
                elif minor==11:rec['triangles']=[[v/65536 for v in struct.unpack_from(endian+'6i',raw,o)] for o in range(24,len(raw),24)]
            elif minor==26:
                rec['op']=raw[4];rec['dst']=u(8)[0];rec['color']=struct.unpack_from(endian+'4H',raw,12);rec['rectangles']=[struct.unpack_from(endian+'hhHH',raw,o) for o in range(20,len(raw),8)]
            elif minor in [34,35,36]:
                rec['picture']=u(4)[0];n=u({34:24,35:32,36:20}[minor])[0]
                points=4 if minor==34 else 6 if minor==35 else 3
                rec['geometry']=[v/65536 for v in struct.unpack_from(endian+str(points)+'i',raw,8)]
                start={34:28,35:36,36:24}[minor]
                rec['stops']=[v/65536 for v in struct.unpack_from(endian+str(n)+'i',raw,start)]
                rec['colors']=[struct.unpack_from(endian+'4H',raw,start+n*4+i*8) for i in range(n)]
        elif opcode==53:
            rec['depth']=minor;rec['pixmap'],rec['drawable']=u(4,2);rec['size']=struct.unpack_from(endian+'HH',raw,12)
        elif opcode==72:
            rec['format']=minor;rec['drawable'],rec['gc']=u(4,2);rec['size']=struct.unpack_from(endian+'HH',raw,12);rec['dst']=s(16,2);rec['depth']=raw[21];rec['data_sha256']=hashlib.sha256(raw[24:]).hexdigest()
        requests.append(rec)
out=args.trace.with_suffix('.json');out.write_text(json.dumps(requests,indent=2)+'\n')
print('records',records,'requests',len(requests),'clients',dict(clients))
print('counts',json.dumps(dict(counts.most_common()),indent=2))
for rec in requests:
    if rec['name'] in ['Render.CreateLinearGradient','Render.CreateRadialGradient','Render.CreateConicalGradient']:
        print(json.dumps({k:v for k,v in rec.items() if k!='raw'}))
print('decoded',out)
