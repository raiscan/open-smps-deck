"""Find and dismiss the named GTK chooser on Studio's isolated Xvfb display."""

import ctypes as c
import sys
import time

x11 = c.CDLL("libX11.so.6")
xtst = c.CDLL("libXtst.so.6")
display_type = c.c_void_p
window_type = c.c_ulong

def bind(lib, name, result, *args):
    function = getattr(lib, name)
    function.restype = result
    function.argtypes = args
    return function

open_display = bind(x11, "XOpenDisplay", display_type, c.c_char_p)
root_window = bind(x11, "XDefaultRootWindow", window_type, display_type)
query_tree = bind(x11, "XQueryTree", c.c_int, display_type, window_type,
                  c.POINTER(window_type), c.POINTER(window_type),
                  c.POINTER(c.POINTER(window_type)), c.POINTER(c.c_uint))
fetch_name = bind(x11, "XFetchName", c.c_int, display_type, window_type, c.POINTER(c.c_char_p))
free = bind(x11, "XFree", c.c_int, c.c_void_p)
focus = bind(x11, "XSetInputFocus", c.c_int, display_type, window_type, c.c_int, c.c_ulong)
keycode = bind(x11, "XKeysymToKeycode", c.c_uint, display_type, c.c_ulong)
key = bind(xtst, "XTestFakeKeyEvent", c.c_int, display_type, c.c_uint, c.c_int, c.c_ulong)
close = bind(x11, "XCloseDisplay", c.c_int, display_type)

def find_window(display, title):
    root = window_type()
    parent = window_type()
    windows = c.POINTER(window_type)()
    count = c.c_uint()
    if not query_tree(display, root_window(display), c.byref(root), c.byref(parent),
                      c.byref(windows), c.byref(count)):
        raise RuntimeError("Cannot list windows on isolated display")
    try:
        for window in windows[:count.value]:
            name = c.c_char_p()
            if fetch_name(display, window, c.byref(name)):
                matches = name.value == title
                free(name)
                if matches:
                    return window
    finally:
        if windows:
            free(windows)
    return None


def main():
    action, title = sys.argv[1:]
    display = open_display(None)
    if not display:
        sys.exit("Cannot open isolated display")
    try:
        if action == "probe":
            return
        deadline = time.monotonic() + 4
        while not (window := find_window(display, title.encode())):
            if time.monotonic() >= deadline:
                sys.exit("Native chooser did not appear on the isolated display")
            time.sleep(0.05)
        if action == "dismiss":
            # There is no window manager to assign keyboard focus under Xvfb.
            focus(display, window, 2, 0)
            escape = keycode(display, 0xff1b)
            key(display, escape, 1, 0)
            key(display, escape, 0, 0)
    finally:
        close(display)


if __name__ == "__main__":
    main()
