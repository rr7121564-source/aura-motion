#pragma once
#include "scene.h"

namespace am {

struct ModelPose {
    std::vector<float> positions, normals, joints;
    std::vector<SkM44> worlds;
};
struct ModelRig {
    struct Node {
        int parent = -1;
        bool matrixNode = false;
        SkM44 matrix;
        std::array<float, 3> translation{0,0,0}, scale{1,1,1};
        std::array<float, 4> rotation{0,0,0,1};
        std::array<std::string, 3> keys;
    };
    struct Binding { int node = 0; SkM44 inverseBind; };
    struct Channel { int node = 0, path = 0, interpolation = 1; std::vector<float> times, values; };
    struct Clip { float duration = 0; std::vector<Channel> channels; };
    SkM44 normalization;
    std::vector<Node> nodes;
    std::vector<Binding> bindings;
    std::vector<Clip> clips;
    std::vector<float> positions, normals, weights;
    std::vector<int> influences;
    // One current pose, shared by shadows/effects/depth passes; no frame-history allocations.
    mutable std::shared_ptr<const ModelPose> cached;
};

std::shared_ptr<const ModelRig> ReadModelRig(const std::vector<uint8_t>& bytes, size_t vertices);
std::shared_ptr<const ModelPose> EvaluateModelRig(const ModelRig& rig, const Layer& layer, double timeMs);

}
