// Regression probe for the rebase relocation: ADJACENT direct RIP-relative
// operands into writable .data (static => internal linkage => no GOT), the
// exact shape that made verify-then-patch self-collide.
// gcc -O2 -shared -fPIC -fno-stack-protector -o librebase_probe.so rebase_probe.c
#include <stdint.h>

static int32_t g1 = 111, g2 = 222, g3 = 333;
static float gf1 = 1.5f, gf2 = 2.25f;
static double gd1 = 3.5, gd2 = 0.25;

// keeps the statics non-constant so loads stay in the code
void poke(int32_t a, float b, double c) { g1 += a; g2 += a; g3 += a; gf1 += b; gf2 += b; gd1 += c; gd2 += c; }

int32_t two_globals(void) { return g1 + g2; }
int32_t three_globals(void) { return g1 + g2 + g3; }
float float_globals(void) { return gf1 * gf2; }
double double_globals(void) { return gd1 * gd2; }
float mixed_globals(void) { return (float) g1 * gf2; }
