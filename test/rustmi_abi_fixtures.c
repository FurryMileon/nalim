#include <stdint.h>

// The SysV signatures match RustMi, not the Minecraft algorithms. The u64
// arguments are opaque address-sized values; these fixtures never dereference them.
static inline uint32_t fold(uint64_t x) {
    return (uint32_t)(x ^ (x >> 32));
}

// Keep one ordinary compiled call in the collision fixture: the actual collision
// kernel is not a leaf, so its Nalim adapter cannot just embed a few instructions.
__attribute__((noinline))
static uint32_t fold_pair(uint64_t a, uint64_t b) {
    return fold(a) + 3U * fold(b);
}

uint32_t abi_collide(uint64_t header, uint64_t solids, uint64_t out) {
    return fold_pair(header, solids) + 5U * fold(out);
}

uint32_t abi_spawncap_can_local(uint64_t handle, int32_t cat, int32_t x, int32_t z) {
    return fold(handle) + 3U * (uint32_t)cat - 5U * (uint32_t)x + 7U * (uint32_t)z;
}

uint32_t abi_distance_sort(uint64_t xs, uint64_t ys, uint64_t zs,
                           double tx, double ty, double tz, int32_t len, uint64_t out) {
    // The benchmark supplies finite binary fractions, well within int32_t range.
    int32_t coordinates = (int32_t)(13.0 * tx + 17.0 * ty - 19.0 * tz);
    return fold(xs) + 3U * fold(ys) + 5U * fold(zs) + 7U * (uint32_t)coordinates
            + 11U * (uint32_t)len + 23U * fold(out);
}
