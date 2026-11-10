#include "model_rig.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace am {
namespace {
struct Reader {
    const std::vector<uint8_t>& bytes;
    size_t at = 0;
    bool ok = true;
    int integer() {
        if (at + 4 > bytes.size()) { ok = false; return 0; }
        uint32_t v = 0; for (int k=0;k<4;k++) v |= uint32_t(bytes[at++]) << (k*8);
        return int32_t(v);
    }
    float number() { const int bits=integer(); float v; std::memcpy(&v,&bits,4); if(!std::isfinite(v)) ok=false; return v; }
    bool room(size_t count) { if (count > (bytes.size()-at)/4) ok=false; return ok; }
    SkM44 matrix() { float v[16]; for(float& x:v) x=number(); return SkM44::ColMajor(v); }
    std::vector<float> floats(size_t n) { if(!room(n)) return {}; std::vector<float> v(n); for(float& x:v) x=number(); return v; }
};

void Normalize(float* q) {
    const float length=std::sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
    if(length>1e-10f) for(int k=0;k<4;k++) q[k]/=length;
    else { q[0]=q[1]=q[2]=0; q[3]=1; }
}
SkM44 TRS(const float* t,const float* input,const float* s) {
    float q[4]; std::copy(input,input+4,q); Normalize(q);
    const float x=q[0], y=q[1], z=q[2], w=q[3];
    const float m[16]={
        (1-2*(y*y+z*z))*s[0],2*(x*y+z*w)*s[0],2*(x*z-y*w)*s[0],0,
        2*(x*y-z*w)*s[1],(1-2*(x*x+z*z))*s[1],2*(y*z+x*w)*s[1],0,
        2*(x*z+y*w)*s[2],2*(y*z-x*w)*s[2],(1-2*(x*x+y*y))*s[2],0,
        t[0],t[1],t[2],1};
    return SkM44::ColMajor(m);
}
void Sample(const ModelRig::Channel& channel,float time,float* out) {
    const int n=channel.path==1?4:3, stride=channel.interpolation==2?n*3:n;
    const int count=int(channel.times.size());
    const auto upper=std::upper_bound(channel.times.begin(),channel.times.end(),time);
    const int a=std::clamp(int(upper-channel.times.begin())-1,0,count-1), b=std::min(a+1,count-1);
    const float dt=channel.times[b]-channel.times[a];
    const float u=dt>0?std::clamp((time-channel.times[a])/dt,0.f,1.f):0;
    const float* va=channel.values.data()+a*stride+(channel.interpolation==2?n:0);
    const float* vb=channel.values.data()+b*stride+(channel.interpolation==2?n:0);
    if(channel.interpolation==0 || a==b) std::copy(va,va+n,out);
    else if(channel.interpolation==2) {
        const float u2=u*u,u3=u2*u;
        for(int k=0;k<n;k++) out[k]=(2*u3-3*u2+1)*va[k]+dt*(u3-2*u2+u)*va[n+k]+(-2*u3+3*u2)*vb[k]+dt*(u3-u2)*vb[k-n];
    } else if(channel.path==1) {
        float qa[4],qb[4];std::copy(va,va+4,qa);std::copy(vb,vb+4,qb);Normalize(qa);Normalize(qb);
        float dot=0;for(int k=0;k<4;k++) dot+=qa[k]*qb[k];
        const float sign=dot<0?-1.f:1.f;dot=std::clamp(std::fabs(dot),0.f,1.f);
        const float angle=std::acos(dot), sine=std::sin(angle);
        const float x=sine>1e-5f?std::sin((1-u)*angle)/sine:1-u, y=sine>1e-5f?std::sin(u*angle)/sine:u;
        for(int k=0;k<4;k++) out[k]=qa[k]*x+qb[k]*sign*y;
    } else for(int k=0;k<n;k++) out[k]=va[k]+u*(vb[k]-va[k]);
    if(channel.path==1) Normalize(out);
}
SkV3 Normal(const SkM44& matrix,float x,float y,float z) {
    const SkV4 v=matrix.map(x,y,z,0);
    const float length=std::sqrt(v.x*v.x+v.y*v.y+v.z*v.z);
    return std::isfinite(length) && length>1e-10f?SkV3{v.x/length,v.y/length,v.z/length}:SkV3{0,0,1};
}
}

std::shared_ptr<const ModelRig> ReadModelRig(const std::vector<uint8_t>& bytes,size_t vertices) {
    if(bytes.empty() || bytes.size()>128u*1024*1024) return nullptr;
    Reader r{bytes};
    if(r.integer()!=0x47524d54 || r.integer()!=1) return nullptr;
    const int nodeCount=r.integer(),bindingCount=r.integer(),clipCount=r.integer(),vertexCount=r.integer();
    if(nodeCount<1 || nodeCount>8192 || bindingCount<1 || bindingCount>262144 || clipCount<0 || clipCount>1024 || vertexCount<1 || size_t(vertexCount)!=vertices) return nullptr;
    auto rig=std::make_shared<ModelRig>(); rig->normalization=r.matrix();
    if(!r.room(size_t(nodeCount)*28+size_t(bindingCount)*17+vertices*22)) return nullptr;
    rig->nodes.resize(nodeCount);
    for(int i=0;i<nodeCount;i++) {
        auto& node=rig->nodes[i];node.parent=r.integer();node.matrixNode=r.integer()!=0;node.matrix=r.matrix();
        for(float& v:node.translation) v=r.number();for(float& v:node.rotation) v=r.number();for(float& v:node.scale) v=r.number();
        const auto base="bone"+std::to_string(i)+".";node.keys={base+"move",base+"rotate",base+"scale"};
        if(node.parent < -1 || node.parent>=nodeCount || node.parent==i) return nullptr;
    }
    for(int i=0;i<nodeCount;i++) {
        int at=i,depth=0;while(at>=0 && depth++<=256) at=rig->nodes[at].parent;
        if(at>=0) return nullptr;
    }
    rig->bindings.resize(bindingCount);
    for(auto& binding:rig->bindings) {binding.node=r.integer();binding.inverseBind=r.matrix();if(binding.node<0 || binding.node>=nodeCount) return nullptr;}
    rig->positions=r.floats(vertices*3);rig->normals=r.floats(vertices*3);
    rig->influences.resize(vertices*8);for(int& v:rig->influences) {v=r.integer();if(v < -1 || v>=bindingCount) return nullptr;}
    rig->weights=r.floats(vertices*8);
    if(!r.ok) return nullptr;
    for(size_t v=0;v<vertices;v++) {
        float total=0;for(int k=0;k<8;k++) {const auto at=v*8+k;const float w=rig->weights[at];if(w<0 || (w>0 && rig->influences[at]<0)) return nullptr;total+=w;}
        if(!(total>0) || !std::isfinite(total)) return nullptr;
        for(int k=0;k<8;k++) rig->weights[v*8+k]/=total;
    }
    rig->clips.resize(clipCount);
    size_t values=0;
    for(auto& clip:rig->clips) {
        clip.duration=r.number();const int count=r.integer();if(clip.duration<0 || count<0 || count>32768 || !r.room(size_t(count)*4)) return nullptr;
        clip.channels.resize(count);
        for(auto& channel:clip.channels) {
            channel.node=r.integer();channel.path=r.integer();channel.interpolation=r.integer();const int count=r.integer();
            if(channel.node<0 || channel.node>=nodeCount || channel.path<0 || channel.path>2 || channel.interpolation<0 || channel.interpolation>2 || count<1 || count>16000000) return nullptr;
            const size_t n=size_t(count)*(channel.path==1?4:3)*(channel.interpolation==2?3:1);
            values+=n;if(values>64000000 || !r.room(size_t(count)+n)) return nullptr;
            channel.times=r.floats(count);channel.values=r.floats(n);
            if(!r.ok || channel.times.front()<0 || channel.times.back()>clip.duration+1e-4f) return nullptr;
            for(int k=1;k<count;k++) if(channel.times[k]<=channel.times[k-1]) return nullptr;
        }
    }
    return r.ok && r.at==bytes.size()?rig:nullptr;
}

std::shared_ptr<const ModelPose> EvaluateModelRig(const ModelRig& rig,const Layer& layer,double timeMs) {
    const size_t count=rig.nodes.size();const float t=layer.localTime(timeMs);
    std::vector<std::array<float,10>> trs(count);
    for(size_t i=0;i<count;i++) {
        std::copy(rig.nodes[i].translation.begin(),rig.nodes[i].translation.end(),trs[i].begin());
        std::copy(rig.nodes[i].rotation.begin(),rig.nodes[i].rotation.end(),trs[i].begin()+3);
        std::copy(rig.nodes[i].scale.begin(),rig.nodes[i].scale.end(),trs[i].begin()+7);
    }
    const float selected=layer.value("modelClip",0,t,rig.clips.empty()?0:1);
    const int clipIndex=std::isfinite(selected)?int(std::clamp(selected,0.f,float(rig.clips.size())))-1:-1;
    if(clipIndex>=0) {
        const auto& clip=rig.clips[clipIndex];
        double seconds=(layer.steppedMs(timeMs)-layer.startTime)/1000*layer.value("modelAnimSpeed",0,t,1)+layer.value("modelAnimOffset",0,t,0);
        if(!std::isfinite(seconds)) seconds=0;
        if(clip.duration>0 && layer.value("modelAnimLoop",0,t,1)>=.5f) {seconds=std::fmod(seconds,clip.duration);if(seconds<0) seconds+=clip.duration;}
        else seconds=std::clamp(seconds,0.,double(clip.duration));
        for(const auto& channel:clip.channels) Sample(channel,float(seconds),trs[channel.node].data()+(channel.path==0?0:channel.path==1?3:7));
    }
    std::vector<SkM44> locals(count),worlds(count);
    for(size_t i=0;i<count;i++) {
        const auto& node=rig.nodes[i];const auto& values=trs[i];
        locals[i]=node.matrixNode?node.matrix:TRS(values.data(),values.data()+3,values.data()+7);
        float move[3]={0,0,0},rotate[3]={0,0,0},scale[3]={1,1,1};float* groups[3]={move,rotate,scale};
        bool changed=false;
        for(int group=0;group<3;group++) if(layer.prop(node.keys[group].c_str())) for(int k=0;k<3;k++) {
            const float fallback=group==2?1:0, value=layer.value(node.keys[group].c_str(),k,t,fallback);
            groups[group][k]=std::isfinite(value)?value:fallback;changed|=groups[group][k]!=fallback;
        }
        if(changed) {
            constexpr float radians=3.14159265358979323846f/180;
            locals[i]=locals[i]*SkM44::Translate(move[0],move[1],move[2])*
                SkM44::Rotate({0,0,1},rotate[2]*radians)*SkM44::Rotate({0,1,0},rotate[1]*radians)*SkM44::Rotate({1,0,0},rotate[0]*radians)*SkM44::Scale(scale[0],scale[1],scale[2]);
        }
    }
    std::vector<uint8_t> ready(count,0);
    auto visit=[&](auto&& self,size_t i)->void {if(ready[i]) return;const int parent=rig.nodes[i].parent;if(parent>=0) self(self,size_t(parent));worlds[i]=parent>=0?worlds[parent]*locals[i]:locals[i];ready[i]=1;};
    for(size_t i=0;i<count;i++) visit(visit,i);
    for(const auto& matrix:worlds) {float values[16];matrix.getColMajor(values);for(float value:values) if(!std::isfinite(value)) return nullptr;}
    if(rig.cached && worlds==rig.cached->worlds) return rig.cached;
    auto pose=std::make_shared<ModelPose>();pose->worlds=std::move(worlds);pose->joints.resize(count*3);
    for(size_t i=0;i<count;i++) {const auto p=(rig.normalization*pose->worlds[i]).map(0,0,0,1);pose->joints[i*3]=p.x;pose->joints[i*3+1]=p.y;pose->joints[i*3+2]=p.z;}
    std::vector<SkM44> matrices(rig.bindings.size()),normalMatrices(rig.bindings.size());
    for(size_t i=0;i<matrices.size();i++) {
        matrices[i]=rig.normalization*pose->worlds[rig.bindings[i].node]*rig.bindings[i].inverseBind;
        SkM44 inverse;if(matrices[i].invert(&inverse)) normalMatrices[i]=inverse.transpose();else normalMatrices[i]=matrices[i];
    }
    const size_t vertices=rig.positions.size()/3;pose->positions.resize(vertices*3);pose->normals.resize(vertices*3);
    for(size_t v=0;v<vertices;v++) {
        SkV3 p{0,0,0},n{0,0,0};
        for(int k=0;k<8;k++) {
            const float weight=rig.weights[v*8+k];if(weight<=0) continue;const int id=rig.influences[v*8+k];
            const auto q=matrices[id].map(rig.positions[v*3],rig.positions[v*3+1],rig.positions[v*3+2],1);
            if(!std::isfinite(q.x) || !std::isfinite(q.y) || !std::isfinite(q.z)) return nullptr;
            p=p+SkV3{q.x,q.y,q.z}*weight;n=n+Normal(normalMatrices[id],rig.normals[v*3],rig.normals[v*3+1],rig.normals[v*3+2])*weight;
        }
        const float length=n.length();if(std::isfinite(length) && length>1e-10f) n=n*(1/length);else n={0,0,1};
        pose->positions[v*3]=p.x;pose->positions[v*3+1]=p.y;pose->positions[v*3+2]=p.z;
        pose->normals[v*3]=n.x;pose->normals[v*3+1]=n.y;pose->normals[v*3+2]=n.z;
    }
    rig.cached=pose;return pose;
}
}
