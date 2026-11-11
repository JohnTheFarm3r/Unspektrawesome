#ifndef SPEKTRA_LINEAR_DISPATCH_H
#define SPEKTRA_LINEAR_DISPATCH_H

#define SPEKTRA_LINEAR_GROUP_SIZE 256u
#define SPEKTRA_LINEAR_GROUPS_PER_ROW 512u

#ifdef __cplusplus
#include <array>
#include <cstdint>

namespace spektrafilm {
constexpr std::array<uint32_t, 2> linearDispatchGroups(uint32_t count, uint32_t maxGroupsX) {
  const uint32_t groups = static_cast<uint32_t>((uint64_t{count} + SPEKTRA_LINEAR_GROUP_SIZE - 1u) /
                                               SPEKTRA_LINEAR_GROUP_SIZE);
  if (groups <= maxGroupsX) return {groups, 1u};
  // Vulkan guarantees 65535 groups per axis; 32768 rows cover any uint32_t count.
  return {SPEKTRA_LINEAR_GROUPS_PER_ROW,
          (groups + SPEKTRA_LINEAR_GROUPS_PER_ROW - 1u) / SPEKTRA_LINEAR_GROUPS_PER_ROW};
}
}
#else
uint spektraLinearIndex() {
  // A fixed row stride also works when cooperative submissions use dispatch bases.
  return gl_GlobalInvocationID.x + gl_GlobalInvocationID.y *
    (SPEKTRA_LINEAR_GROUP_SIZE * SPEKTRA_LINEAR_GROUPS_PER_ROW);
}
#endif

#endif
