#pragma once
#include <algorithm>
#include <cmath>

namespace am {
inline float RigClampAngle(float angle, float rest, float lo, float hi, bool enabled) {
    if (!std::isfinite(angle)) return rest;
    if (!enabled) return angle;
    const float centre = rest + (lo + hi) * .5f;
    angle += 360 * std::round((centre - angle) / 360);
    return std::clamp(angle, rest + lo, rest + hi);
}
inline float ParentScaleRatio(float scale, float weight) {
    if (!std::isfinite(scale) || !std::isfinite(weight) || std::abs(scale) < .00001f) return 1;
    if (weight == 1) return 1;
    // A zero weight also removes mirrored inheritance. Keep finite bounds near singular scales.
    const float value = weight == 0 ? 1 : std::copysign(std::pow(std::abs(scale), std::clamp(weight, -10.f, 10.f)), scale);
    return std::clamp(value / scale, -10000.f, 10000.f);
}
}
