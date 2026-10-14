/*
 * okno-cpubench: a short CPU benchmark, built three times: as a native ARM64
 * Linux program and as 64- and 32-bit Windows programs. okno-gfx-test runs
 * all three to show how much speed the x86 emulation (FEX for x86-64, Box64
 * for 32-bit x86) keeps. Integer and memory work, floating point, copying.
 * Prints the time in milliseconds.
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>

static double now_ms(void)
{
    LARGE_INTEGER freq, count;

    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&count);
    return (double) count.QuadPart * 1000.0 / (double) freq.QuadPart;
}
#else
#include <time.h>

static double now_ms(void)
{
    struct timespec t;

    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}
#endif

static unsigned char buf[1 << 20];

int main(void)
{
    double start = now_ms(), f = 0;
    uint32_t h = 2166136261u;
    int round, i;
    size_t j;

    for (round = 0; round < 40; round++) {
        for (j = 0; j < sizeof(buf); j++) { /* FNV-1a over a buffer it rewrites */
            h ^= (uint32_t) (buf[j] + round);
            h *= 16777619u;
            buf[j] = (unsigned char) h;
        }
        for (i = 1; i < 200000; i++)
            f += sqrt((double) i) / i;
        memmove(buf + 1, buf, sizeof(buf) - 1);
    }
    printf("%.0f\n", now_ms() - start);
    /* The results must stay alive, or the compiler drops the work. */
    return h == 42 && f < 0;
}
