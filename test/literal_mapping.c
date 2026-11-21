// Linux AMD64: cc -O2 -fPIC -shared -o libliteral_mapping.so literal_mapping.c
#include <sys/mman.h>
#include <unistd.h>

// Occupy one complete base page; changing its permissions must not affect the
// dynamic loader's data or any code. The generated load reads this cell directly.
static volatile int cell[4096 / sizeof(int)] __attribute__((aligned(4096))) = {11};

int literal_page_writable(int writable) {
    if (sysconf(_SC_PAGESIZE) != (long)sizeof(cell)) return -1;
    return mprotect((void *)cell, sizeof(cell), PROT_READ | (writable ? PROT_WRITE : 0));
}

int literal_page_load(void) {
    return cell[0];
}

void literal_page_store(int value) {
    cell[0] = value;
}
