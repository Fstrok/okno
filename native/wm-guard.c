/*
 * okno-wm-guard: keeps windows usable on a phone-sized desktop.
 *
 * - A window that was dragged (or placed by its program) partly off the
 *   screen is moved back inside once the drag ends, so its title bar and
 *   buttons can always be reached.
 * - A new window that is larger than the screen, or nearly as large, opens
 *   maximized; dialogs keep their size unless they do not fit.
 *
 * All changes are requests to the window manager (EWMH), so xfwm4 stays in
 * charge of the windows. Started by okno-desktop; exits with the X server.
 */
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>

/* A new window wider or taller than this share of the screen opens maximized. */
#define MAXIMIZE_SHARE 0.75
/* Wait for this long after the last window move before checking. */
#define SETTLE_MS 250

static Display *dpy;
static Window root;
static Atom a_client_list, a_workarea, a_frame_extents, a_gtk_frame_extents;
static Atom a_state, a_state_max_v, a_state_max_h, a_state_fullscreen, a_state_hidden;
static Atom a_type, a_type_normal, a_type_dialog, a_type_utility;
static Atom a_moveresize;

static Window *known;
static unsigned long known_count;

static int ignore_errors(Display *d, XErrorEvent *e)
{
    (void) d;
    (void) e; /* windows can vanish at any moment */
    return 0;
}

/* Reads a CARDINAL/ATOM/WINDOW array property; returns item count (0 if absent). */
static unsigned long get_longs(Window w, Atom prop, Atom type, long max, unsigned long **out)
{
    Atom actual;
    int format;
    unsigned long n = 0, rest;
    unsigned char *data = NULL;

    *out = NULL;
    if (XGetWindowProperty(dpy, w, prop, 0, max, False, type, &actual, &format, &n, &rest, &data) != Success)
        return 0;
    if (!data || format != 32 || n == 0) {
        if (data)
            XFree(data);
        return 0;
    }
    *out = (unsigned long *) data; /* Xlib returns 32-bit items as longs */
    return n;
}

static int has_atom(Window w, Atom prop, Atom value)
{
    unsigned long *v, n = get_longs(w, prop, XA_ATOM, 64, &v), i;
    int found = 0;

    for (i = 0; i < n; i++)
        if (v[i] == value)
            found = 1;
    if (v)
        XFree(v);
    return found;
}

/* Only ordinary application windows and their dialogs; not panels, desktop, menus. */
static int is_app_window(Window w, int *is_normal)
{
    unsigned long *v, n = get_longs(w, a_type, XA_ATOM, 16, &v);
    int ok = (n == 0);

    *is_normal = (n == 0);
    if (n > 0) {
        if (v[0] == a_type_normal)
            ok = *is_normal = 1;
        else if (v[0] == a_type_dialog || v[0] == a_type_utility)
            ok = 1;
    }
    if (v)
        XFree(v);
    return ok;
}

static int get_workarea(int *x, int *y, int *w, int *h)
{
    unsigned long *v, n = get_longs(root, a_workarea, XA_CARDINAL, 4, &v);

    if (n < 4) {
        Screen *s = DefaultScreenOfDisplay(dpy);
        *x = *y = 0;
        *w = WidthOfScreen(s);
        *h = HeightOfScreen(s);
    } else {
        *x = (int) v[0];
        *y = (int) v[1];
        *w = (int) v[2];
        *h = (int) v[3];
    }
    if (v)
        XFree(v);
    return *w > 0 && *h > 0;
}

/* Left, right, top, bottom of a frame-extents property, zeros if absent. */
static void get_extents(Window w, Atom prop, long e[4])
{
    unsigned long *v, n = get_longs(w, prop, XA_CARDINAL, 4, &v), i;

    for (i = 0; i < 4; i++)
        e[i] = (n == 4) ? (long) v[i] : 0;
    if (v)
        XFree(v);
}

static void send_root_message(Window w, Atom type, long l0, long l1, long l2, long l3, long l4)
{
    XEvent ev;

    memset(&ev, 0, sizeof(ev));
    ev.xclient.type = ClientMessage;
    ev.xclient.window = w;
    ev.xclient.message_type = type;
    ev.xclient.format = 32;
    ev.xclient.data.l[0] = l0;
    ev.xclient.data.l[1] = l1;
    ev.xclient.data.l[2] = l2;
    ev.xclient.data.l[3] = l3;
    ev.xclient.data.l[4] = l4;
    XSendEvent(dpy, root, False, SubstructureRedirectMask | SubstructureNotifyMask, &ev);
}

static void maximize(Window w)
{
    send_root_message(w, a_state, 1 /* add */, (long) a_state_max_v, (long) a_state_max_h, 2 /* pager */, 0);
}

static int is_known(Window w)
{
    unsigned long i;

    for (i = 0; i < known_count; i++)
        if (known[i] == w)
            return 1;
    return 0;
}

/* Programs that fix their window size (min = max) cannot be maximized. */
static int is_fixed_size(Window w)
{
    XSizeHints hints;
    long supplied;

    if (!XGetWMNormalHints(dpy, w, &hints, &supplied))
        return 0;
    return (hints.flags & PMinSize) && (hints.flags & PMaxSize) &&
           hints.min_width == hints.max_width && hints.min_height == hints.max_height;
}

/* Checks one window. new_window: it just appeared in the client list. */
static void check_window(Window w, int new_window)
{
    XWindowAttributes attr;
    Window child;
    int x, y, wx, wy, ww, wh, is_normal, nx, ny;
    long frame[4], gtk[4];
    long vx, vy, vw, vh;

    if (!is_app_window(w, &is_normal))
        return;
    if (has_atom(w, a_state, a_state_hidden) || has_atom(w, a_state, a_state_fullscreen))
        return;
    if (has_atom(w, a_state, a_state_max_v) && has_atom(w, a_state, a_state_max_h))
        return;
    if (!XGetWindowAttributes(dpy, w, &attr) || attr.map_state != IsViewable)
        return;
    if (!XTranslateCoordinates(dpy, w, root, 0, 0, &x, &y, &child))
        return;
    if (!get_workarea(&wx, &wy, &ww, &wh))
        return;

    /* Visible rectangle: the client without GTK's shadow margins, plus the
     * window manager's frame (title bar and borders). */
    get_extents(w, a_frame_extents, frame);
    get_extents(w, a_gtk_frame_extents, gtk);
    vx = x + gtk[0] - frame[0];
    vy = y + gtk[2] - frame[2];
    vw = attr.width - gtk[0] - gtk[1] + frame[0] + frame[1];
    vh = attr.height - gtk[2] - gtk[3] + frame[2] + frame[3];

    if ((vw > ww || vh > wh || (new_window && is_normal &&
         (vw > ww * MAXIMIZE_SHARE || vh > wh * MAXIMIZE_SHARE))) && !is_fixed_size(w)) {
        maximize(w);
        return;
    }
    /* Otherwise keep it inside the screen; one that cannot fit shows at
     * least its title bar and top-left corner. */

    nx = (int) vx;
    ny = (int) vy;
    if (nx + vw > wx + ww)
        nx = (int) (wx + ww - vw);
    if (ny + vh > wy + wh)
        ny = (int) (wy + wh - vh);
    if (nx < wx)
        nx = wx;
    if (ny < wy)
        ny = wy;
    if (nx == vx && ny == vy)
        return;

    /* _NET_MOVERESIZE_WINDOW with NorthWest gravity places the outer frame's
     * corner, which for GTK windows includes the shadow margin. */
    send_root_message(w, a_moveresize, NorthWestGravity | (1 << 8) | (1 << 9) | (2 << 12),
                      nx - gtk[0], ny - gtk[2], 0, 0);
}

/* Checks every client and remembers the list, to tell new windows apart.
 * Windows already open when the guard starts do not count as new. */
static void check_all(void)
{
    unsigned long *list, n = get_longs(root, a_client_list, XA_WINDOW, 4096, &list), i;

    for (i = 0; i < n; i++)
        check_window((Window) list[i], known != NULL && !is_known((Window) list[i]));
    free(known);
    known = NULL;
    known_count = 0;
    if (n > 0) {
        known = malloc(n * sizeof(Window));
        if (known) {
            for (i = 0; i < n; i++)
                known[i] = (Window) list[i];
            known_count = n;
        }
    }
    if (known == NULL)
        known = malloc(sizeof(Window)); /* non-NULL: the first list was seen */
    if (list)
        XFree(list);
}

static int buttons_down(void)
{
    Window r, c;
    int rx, ry, x, y;
    unsigned int mask = 0;

    XQueryPointer(dpy, root, &r, &c, &rx, &ry, &x, &y, &mask);
    return (mask & (Button1Mask | Button2Mask | Button3Mask)) != 0;
}

static long now_ms(void)
{
    struct timeval tv;

    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000L + tv.tv_usec / 1000;
}

int main(void)
{
    long due = 0; /* time of a pending full check, 0 if none */
    int fd;

    dpy = XOpenDisplay(NULL);
    if (!dpy) {
        fprintf(stderr, "okno-wm-guard: cannot open display\n");
        return 1;
    }
    XSetErrorHandler(ignore_errors);
    root = DefaultRootWindow(dpy);

    a_client_list = XInternAtom(dpy, "_NET_CLIENT_LIST", False);
    a_workarea = XInternAtom(dpy, "_NET_WORKAREA", False);
    a_frame_extents = XInternAtom(dpy, "_NET_FRAME_EXTENTS", False);
    a_gtk_frame_extents = XInternAtom(dpy, "_GTK_FRAME_EXTENTS", False);
    a_state = XInternAtom(dpy, "_NET_WM_STATE", False);
    a_state_max_v = XInternAtom(dpy, "_NET_WM_STATE_MAXIMIZED_VERT", False);
    a_state_max_h = XInternAtom(dpy, "_NET_WM_STATE_MAXIMIZED_HORZ", False);
    a_state_fullscreen = XInternAtom(dpy, "_NET_WM_STATE_FULLSCREEN", False);
    a_state_hidden = XInternAtom(dpy, "_NET_WM_STATE_HIDDEN", False);
    a_type = XInternAtom(dpy, "_NET_WM_WINDOW_TYPE", False);
    a_type_normal = XInternAtom(dpy, "_NET_WM_WINDOW_TYPE_NORMAL", False);
    a_type_dialog = XInternAtom(dpy, "_NET_WM_WINDOW_TYPE_DIALOG", False);
    a_type_utility = XInternAtom(dpy, "_NET_WM_WINDOW_TYPE_UTILITY", False);
    a_moveresize = XInternAtom(dpy, "_NET_MOVERESIZE_WINDOW", False);

    /* Frames are children of the root: their moves arrive as ConfigureNotify. */
    XSelectInput(dpy, root, PropertyChangeMask | SubstructureNotifyMask);
    check_all();
    XFlush(dpy);
    fd = ConnectionNumber(dpy);

    for (;;) {
        while (XPending(dpy)) {
            XEvent ev;
            XNextEvent(dpy, &ev);
            if (ev.type == PropertyNotify && ev.xproperty.atom == a_client_list) {
                /* Give a new window a moment to get its frame and position. */
                due = now_ms() + SETTLE_MS;
            } else if (ev.type == PropertyNotify && ev.xproperty.atom == a_workarea) {
                due = now_ms() + SETTLE_MS;
            } else if (ev.type == ConfigureNotify || ev.type == MapNotify) {
                due = now_ms() + SETTLE_MS;
            }
        }

        if (due && now_ms() >= due) {
            if (buttons_down()) {
                due = now_ms() + SETTLE_MS; /* still dragging */
            } else {
                due = 0;
                check_all();
                XFlush(dpy);
            }
        }

        {
            fd_set fds;
            struct timeval tv;
            long wait = due ? due - now_ms() : 60000;

            if (wait < 10)
                wait = 10;
            FD_ZERO(&fds);
            FD_SET(fd, &fds);
            tv.tv_sec = wait / 1000;
            tv.tv_usec = (wait % 1000) * 1000;
            if (select(fd + 1, &fds, NULL, NULL, &tv) < 0)
                continue;
        }
        /* A broken connection (X server gone) ends the program inside Xlib. */
    }
}
