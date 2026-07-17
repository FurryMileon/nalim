// Stack-argument probe: >6 integer / >8 FP parameters.
// gcc -O2 -shared -fPIC -fno-stack-protector -o libstack_args.so stack_args.c
#include <stdint.h>
int64_t take7(int64_t a, int64_t b, int64_t c, int64_t d,
              int64_t e, int64_t f, int64_t g) {
    return a*3 + b*5 + c*7 + d*11 + e*13 + f*17 + g*19;
}
int64_t take8(int64_t a, int64_t b, int64_t c, int64_t d,
              int64_t e, int64_t f, int64_t g, int64_t h) {
    return a*3 + b*5 + c*7 + d*11 + e*13 + f*17 + g*19 + h*23;
}
double take9d(double a, double b, double c, double d, double e,
              double f, double g, double h, double i) {
    return a*3 + b*5 + c*7 + d*11 + e*13 + f*17 + g*19 + h*23 + i*29;
}
double mixed10(int32_t a, double b, int64_t c, double d, int32_t e,
               double f, int64_t g, double h, int32_t i, double j) {
    return a*3 + b*5 + c*7 + d*11 + e*13 + f*17 + g*19 + h*23 + i*29 + j*31;
}
