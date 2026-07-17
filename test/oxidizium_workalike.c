// Workalike of liboxidizium.so exporting the exact OxRepro symbol set, for
// sandboxes where the real library cannot load (glibc version). Semantics
// follow Minecraft Mth where exactly defined; every function is
// argument-order-sensitive so any register mis-assignment changes results.
// The boolean-returning functions live in oxidizium_workalike_bool.S and
// deliberately dirty the upper bits of RAX before setting AL — exactly what
// the System V ABI permits and what exposes missing narrow-return handling.
//
// gcc -O2 -shared -fPIC -fno-stack-protector -o liboxidizium_workalike.so \
//     oxidizium_workalike.c oxidizium_workalike_bool.S -lm

#include <math.h>
#include <stdint.h>

int32_t positive_ceil_div_int(int32_t input, int32_t divisor) {
    // Mth.positiveCeilDiv: -floorDiv(-input, divisor)
    int32_t q = -input / divisor;
    if ((-input % divisor != 0) && ((-input < 0) != (divisor < 0))) q--;
    return -q;
}

int64_t positive_ceil_div_long(int64_t input, int64_t divisor) {
    int64_t q = -input / divisor;
    if ((-input % divisor != 0) && ((-input < 0) != (divisor < 0))) q--;
    return -q;
}

int32_t floor_div(int32_t dividend, int32_t divisor) {
    int32_t q = dividend / divisor;
    if ((dividend % divisor != 0) && ((dividend < 0) != (divisor < 0))) q--;
    return q;
}

int32_t round_towards_int(int32_t input, int32_t multiple) {
    // Mth.roundToward: positiveCeilDiv(input, multiple) * multiple
    return positive_ceil_div_int(input, multiple) * multiple;
}

int64_t round_towards_long(int64_t input, int64_t multiple) {
    return positive_ceil_div_long(input, multiple) * multiple;
}

int32_t ceil_div(int32_t a, int32_t b) {
    int32_t q = a / b;
    if ((a % b != 0) && ((a < 0) == (b < 0))) q++;
    return q;
}

int32_t round_up_to_multiple(int32_t value, int32_t divisor) {
    return ceil_div(value, divisor) * divisor;
}

float wrap_degrees_90(float angle) {
    float f = fmodf(angle, 90.0f);
    if (f >= 45.0f) f -= 90.0f;
    if (f < -45.0f) f += 90.0f;
    return f;
}

float wrap_degrees_float(float degrees) {
    float f = fmodf(degrees, 360.0f);
    if (f >= 180.0f) f -= 360.0f;
    if (f < -180.0f) f += 360.0f;
    return f;
}

double wrap_degrees_double(double degrees) {
    double d = fmod(degrees, 360.0);
    if (d >= 180.0) d -= 360.0;
    if (d < -180.0) d += 360.0;
    return d;
}

int32_t wrap_degrees_int(int32_t degrees) {
    int32_t d = degrees % 360;
    if (d >= 180) d -= 360;
    if (d < -180) d += 360;
    return d;
}

float wrap_degrees_long(int64_t degrees) {
    return wrap_degrees_float((float) degrees);
}

double atan_2(double y, double x) {
    return atan2(y, x);          // argument order matters: atan2(y, x)
}

float sin_float(float x)  { return sinf(x); }
float sin_double(double x) { return (float) sin(x); }
float cos_float(float x)  { return cosf(x); }
float lithium_sin_float(float x) { return sinf(x * 0.5f) * 2.0f; } // stand-in, order-free

int32_t floor_float(float x) {
    int32_t i = (int32_t) x;
    return x < (float) i ? i - 1 : i;
}

double magnitude_int(int32_t a, double b, int32_t c) {
    // int, double, int — the register-interleave probe. Distinct multipliers
    // make any argument swap or misrouting visible in the result.
    return a * 3.0 + b * 5.0 + c * 7.0;
}

int32_t round_down_to_multiple(double a, int32_t b) {
    // double first, int second — opposite interleave direction.
    return (int32_t) (floor(a / b) * b);
}

int32_t clamp_int(int32_t v, int32_t lo, int32_t hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

int32_t abs_int(int32_t x) { return x < 0 ? -x : x; }
