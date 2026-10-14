/*
 * okno-gpu-probe: can Linux programs in Okno use the phone's Adreno GPU?
 *
 * Android exposes Qualcomm GPUs through the KGSL driver (/dev/kgsl-3d0),
 * not the DRM render nodes desktop Linux uses. Mesa's Turnip Vulkan driver
 * can talk to KGSL directly, so what matters is whether this process may
 * do what Turnip does: query the GPU, create a context, allocate GPU memory.
 * Exit status 0 only if all of that works. errno is printed as is so a
 * denial (EACCES, SELinux) can be told apart from an unknown request.
 */
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* From the kernel's uapi/linux/msm_kgsl.h. */
#define KGSL_IOC_TYPE 0x09

struct kgsl_device_getproperty {
    unsigned int type;
    void *value;
    size_t sizebytes;
};
#define IOCTL_KGSL_DEVICE_GETPROPERTY _IOWR(KGSL_IOC_TYPE, 0x2, struct kgsl_device_getproperty)
#define KGSL_PROP_DEVICE_INFO 0x1

struct kgsl_devinfo {
    unsigned int device_id;
    unsigned int chip_id;
    unsigned int mmu_enabled;
    unsigned long gmem_gpubaseaddr;
    unsigned int gpu_id;
    size_t gmem_sizebytes;
};

struct kgsl_drawctxt_create {
    unsigned int flags;
    unsigned int drawctxt_id;
};
#define IOCTL_KGSL_DRAWCTXT_CREATE _IOWR(KGSL_IOC_TYPE, 0x13, struct kgsl_drawctxt_create)

struct kgsl_drawctxt_destroy {
    unsigned int drawctxt_id;
};
#define IOCTL_KGSL_DRAWCTXT_DESTROY _IOW(KGSL_IOC_TYPE, 0x14, struct kgsl_drawctxt_destroy)
#define KGSL_CONTEXT_SAVE_GMEM 0x1
#define KGSL_CONTEXT_NO_GMEM_ALLOC 0x2
#define KGSL_CONTEXT_PREAMBLE 0x10

struct kgsl_gpumem_alloc_id {
    unsigned int id;
    unsigned int flags;
    size_t size;
    size_t mmapsize;
    unsigned long gpuaddr;
    unsigned long pad[2];
};
#define IOCTL_KGSL_GPUMEM_ALLOC_ID _IOWR(KGSL_IOC_TYPE, 0x34, struct kgsl_gpumem_alloc_id)

struct kgsl_gpumem_free_id {
    unsigned int id;
    unsigned int pad;
};
#define IOCTL_KGSL_GPUMEM_FREE_ID _IOWR(KGSL_IOC_TYPE, 0x35, struct kgsl_gpumem_free_id)

int main(void)
{
    struct kgsl_devinfo info;
    struct kgsl_device_getproperty prop = { KGSL_PROP_DEVICE_INFO, &info, sizeof(info) };
    struct kgsl_drawctxt_create ctx = {
        KGSL_CONTEXT_SAVE_GMEM | KGSL_CONTEXT_NO_GMEM_ALLOC | KGSL_CONTEXT_PREAMBLE, 0
    };
    struct kgsl_gpumem_alloc_id mem;
    int ok = 1;
    int fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);

    if (fd < 0) {
        printf("KGSL (/dev/kgsl-3d0): нет доступа (%s)\n", strerror(errno));
        return 1;
    }
    printf("KGSL (/dev/kgsl-3d0): открывается\n");

    memset(&info, 0, sizeof(info));
    if (ioctl(fd, IOCTL_KGSL_DEVICE_GETPROPERTY, &prop) == 0) {
        printf("  чип: chip_id 0x%08x, gpu_id %u, GMEM %zu КБ\n",
               info.chip_id, info.gpu_id, info.gmem_sizebytes / 1024);
    } else {
        printf("  чип: не отвечает (%s)\n", strerror(errno));
        ok = 0;
    }

    if (ioctl(fd, IOCTL_KGSL_DRAWCTXT_CREATE, &ctx) == 0) {
        struct kgsl_drawctxt_destroy done = { ctx.drawctxt_id };
        printf("  контекст GPU: создаётся\n");
        ioctl(fd, IOCTL_KGSL_DRAWCTXT_DESTROY, &done);
    } else {
        printf("  контекст GPU: нет (%s)\n", strerror(errno));
        ok = 0;
    }

    memset(&mem, 0, sizeof(mem));
    mem.size = 1 << 20;
    if (ioctl(fd, IOCTL_KGSL_GPUMEM_ALLOC_ID, &mem) == 0) {
        struct kgsl_gpumem_free_id done = { mem.id, 0 };
        printf("  память GPU: выделяется\n");
        ioctl(fd, IOCTL_KGSL_GPUMEM_FREE_ID, &done);
    } else {
        printf("  память GPU: нет (%s)\n", strerror(errno));
        ok = 0;
    }

    close(fd);
    return ok ? 0 : 2;
}
