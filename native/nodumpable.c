/*
 * LD_PRELOAD shim for the SFTP server inside Okno.
 *
 * OpenSSH's sftp-server makes itself undumpable with prctl(PR_SET_DUMPABLE)
 * and exits if that fails. proot, which runs the whole Linux system by
 * tracing it, refuses that call (an undumpable tracee could no longer be
 * traced) and returns ENOSYS. Report success instead; every other prctl
 * goes to the kernel unchanged. The flag only guards SFTP-only accounts,
 * while here the same root user has a full shell anyway.
 */
#include <stdarg.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

int prctl(int option, ...)
{
    va_list ap;
    unsigned long arg2, arg3, arg4, arg5;

    va_start(ap, option);
    arg2 = va_arg(ap, unsigned long);
    arg3 = va_arg(ap, unsigned long);
    arg4 = va_arg(ap, unsigned long);
    arg5 = va_arg(ap, unsigned long);
    va_end(ap);

    if (option == PR_SET_DUMPABLE)
        return 0;
    return (int) syscall(SYS_prctl, option, arg2, arg3, arg4, arg5);
}
