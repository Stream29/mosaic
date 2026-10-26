// Public-API before/after probe; runs unchanged against the original and repaired C sources.
#define _GNU_SOURCE
#include "mosaic-tty-posix.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <unistd.h>

static volatile sig_atomic_t inRaise;
static volatile sig_atomic_t callbacks;
static volatile sig_atomic_t signalCallbacks;

static void on_resize(void *opaque UNUSED, int columns UNUSED, int rows UNUSED, int width UNUSED, int height UNUSED) {
	callbacks++;
	if (inRaise) signalCallbacks++;
}

int main(void) {
	alarm(5);
	int parent = posix_openpt(O_RDWR | O_NOCTTY);
	if (parent < 0 || grantpt(parent) || unlockpt(parent)) return 2;
	int child = open(ptsname(parent), O_RDWR | O_NOCTTY);
	if (child < 0) return 2;
	MosaicTtyInitResult init = mosaic_tty_init_with_fd(child);
	if (!init.tty || init.error) return 2;
	MosaicTty *tty = init.tty;
	if (mosaic_tty_enable_raw_mode(tty) || mosaic_tty_enable_window_resize_events(tty)) return 2;
	MosaicTtyCallback callback = { .onResize = on_resize };
	mosaic_tty_set_callback(tty, &callback);
	struct winsize size = { .ws_col = 100, .ws_row = 40 };
	if (ioctl(parent, TIOCSWINSZ, &size)) return 2;
	inRaise = 1;
	if (raise(SIGWINCH)) return 2;
	inRaise = 0;
	int beforeRead = callbacks;
	uint8_t byte;
	MosaicIoResult result = mosaic_tty_read_with_timeout(tty, &byte, 1, 0);
	printf("before_read=%d after_read=%d signal_context_callbacks=%d read_count=%d read_error=%u\n",
		beforeRead, (int) callbacks, (int) signalCallbacks, result.count, result.error);
	int error = beforeRead || callbacks != 1 || signalCallbacks || result.error || result.count;
	mosaic_tty_free(tty);
	close(parent);
	puts(error ? "FAIL callback entered from signal context" : "PASS callback deferred to ordinary read context");
	return error ? 1 : 0;
}
