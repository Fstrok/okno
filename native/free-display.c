/*
 * okno-free-display: prints the first X display number whose abstract
 * socket name (@/tmp/.X11-unix/X<n>) nobody holds.
 *
 * Abstract Unix sockets are shared by every app on the phone. X clients
 * (libxcb) try that name before the socket file, and give up instead of
 * falling back when it belongs to another app (SELinux answers EACCES).
 * So if, say, Termux runs a VNC server on :1, Okno's desktop must not use :1.
 *
 *   okno-free-display [FIRST]   (default 1; scans up to 99)
 */
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int abstract_name_is_free(int display)
{
    struct sockaddr_un addr;
    socklen_t len;
    int fd, rc, err;

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    len = offsetof(struct sockaddr_un, sun_path) + 1 +
          snprintf(addr.sun_path + 1, sizeof(addr.sun_path) - 1, "/tmp/.X11-unix/X%d", display);

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return 0;
    rc = connect(fd, (struct sockaddr *) &addr, len);
    err = errno;
    close(fd);
    /* Nobody listening: the name is free. Anything else (a listener of
     * ours or another app's, or EACCES from SELinux) means taken. */
    return rc < 0 && (err == ECONNREFUSED || err == ENOENT);
}

int main(int argc, char **argv)
{
    int display = argc > 1 ? atoi(argv[1]) : 1;

    for (; display <= 99; display++) {
        if (abstract_name_is_free(display)) {
            printf("%d\n", display);
            return 0;
        }
    }
    return 1;
}
