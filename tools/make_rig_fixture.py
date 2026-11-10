"""Deterministic original glTF skin/animation fixture (no external assets)."""
from pathlib import Path
import json, math, struct

root = Path(__file__).resolve().parents[1]
data = bytearray(); views=[]; accessors=[]
def accessor(values, components, kind='f', normalized=False):
    while len(data)%4: data.append(0)
    offset=len(data); data.extend(struct.pack('<'+kind*len(values),*values))
    views.append(dict(buffer=0,byteOffset=offset,byteLength=len(data)-offset))
    out=dict(bufferView=len(views)-1,componentType={'f':5126,'B':5121,'H':5123}[kind],
        count=len(values)//components,type={1:'SCALAR',3:'VEC3',4:'VEC4',16:'MAT4'}[components])
    if normalized: out['normalized']=True
    accessors.append(out);return len(accessors)-1

positions=accessor([-.5,0,0,.5,0,0,-.5,1,0,.5,1,0,-.5,2,0,.5,2,0],3)
normals=accessor([0,0,1]*6,3)
joints=accessor([0,0,0,0]*2+[0,1,0,0]*2+[1,0,0,0]*2,4,'B')
weights=accessor([255,0,0,0]*2+[128,127,0,0]*2+[255,0,0,0]*2,4,'B',True)
indices=accessor([0,1,2,1,3,2,2,3,4,3,5,4],1,'H')
def matrix(x,y): return [1,0,0,0,0,1,0,0,0,0,1,0,x,y,0,1]
inverse=accessor(matrix(-1,0)+matrix(-1,-1),16)
times=accessor([0,1,2],1)
bend=accessor([0,0,0,1,0,0,math.sqrt(.5),math.sqrt(.5),0,0,0,1],4)
steps=accessor([0,0,0,1,0,0,0,0,0],3)
cubic_times=accessor([0,1],1)
cubic=accessor([0,0,0,0,0,0,2,0,0, 0,0,0,1,0,0,0,0,0],3)
antipode=accessor([0,0,0,1,0,0,0,-1],4)
def animation(name,node,path,input,output,interpolation='LINEAR'):
    return dict(name=name,channels=[dict(sampler=0,target=dict(node=node,path=path))],samplers=[dict(input=input,output=output,interpolation=interpolation)])
scene=dict(asset=dict(version='2.0',generator='True Motion original regression fixture'),
    buffers=[dict(byteLength=len(data))],bufferViews=views,accessors=accessors,
    meshes=[dict(primitives=[dict(attributes=dict(POSITION=positions,NORMAL=normals,JOINTS_0=joints,WEIGHTS_0=weights),indices=indices,material=0)])],
    materials=[dict(doubleSided=True,pbrMetallicRoughness=dict(baseColorFactor=[1,.4,.1,1]))],
    nodes=[dict(name='Ignored skin mesh transform',mesh=0,skin=0,translation=[100,100,100]),
        dict(name='Root',children=[2]),dict(name='Tip',translation=[0,1,0]),dict(name='Unskinned ancestor',children=[1],translation=[1,0,0])],
    skins=[dict(joints=[1,2],inverseBindMatrices=inverse,skeleton=1)],
    animations=[animation('Bend',2,'rotation',times,bend),animation('Step',1,'translation',times,steps,'STEP'),
        animation('Cubic',1,'translation',cubic_times,cubic,'CUBICSPLINE'),animation('Antipodal',2,'rotation',cubic_times,antipode)],
    scenes=[dict(nodes=[0,3])],scene=0)
text=json.dumps(scene,separators=(',',':')).encode();text+=b' '*((-len(text))%4)
data+=b'\0'*((-len(data))%4)
glb=struct.pack('<III',0x46546c67,2,12+8+len(text)+8+len(data))+struct.pack('<II',len(text),0x4e4f534a)+text+struct.pack('<II',len(data),0x004e4942)+data
folder=root/'android/src/androidTest/assets/rig020';folder.mkdir(parents=True,exist_ok=True)
(folder/'TwoBone.glb').write_bytes(glb)
print('TwoBone.glb',len(glb))
