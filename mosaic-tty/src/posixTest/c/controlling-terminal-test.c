#define _GNU_SOURCE
#include "mosaic-tty-posix.h"
#include <fcntl.h>
#if defined(__APPLE__) && !defined(__linux__)
#include <util.h>
#else
#include <pty.h>
#endif
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define assert(condition) do { \
	if (!(condition)) { \
		fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); \
		_exit(1); \
	} \
} while (0)

static int callbacks;

static void on_resize(void *opaque UNUSED, int cols, int rows, int w UNUSED, int h UNUSED) {
	assert(cols == 91 && rows == 29);
	callbacks++;
}

static int64_t monotonic_ms(void) {
	struct timespec now;
	assert(clock_gettime(CLOCK_MONOTONIC, &now) == 0);
	return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void expect_read(MosaicTty *tty, int timeout, int count, uint8_t expected) {
	uint8_t actual = 0;
	MosaicIoResult result = mosaic_tty_read_with_timeout(tty, &actual, 1, timeout);
	assert(result.error == 0 && result.count == count);
	if (count > 0) assert(actual == expected);
}

static void run_child(int published, int proceed, bool high) {
	int devnull = open("/dev/null", O_RDONLY);
	assert(devnull >= 0 && dup2(devnull, STDIN_FILENO) == STDIN_FILENO);
	assert(!isatty(STDIN_FILENO));
	int held[1040];
	size_t held_count = 0;
	if (high) {
		struct rlimit limit;
		assert(getrlimit(RLIMIT_NOFILE, &limit) == 0);
		if (limit.rlim_cur < 1050) {
			assert(limit.rlim_max >= 1050);
			limit.rlim_cur = 1050;
			assert(setrlimit(RLIMIT_NOFILE, &limit) == 0);
		}
		do {
			assert(held_count < sizeof(held) / sizeof(held[0]));
			held[held_count] = dup(devnull);
			assert(held[held_count] >= 0);
		} while (held[held_count++] < 1030);
	}

	MosaicTtyInitResult init = mosaic_tty_init();
	assert(init.tty && !init.error && !init.no_tty);
	MosaicTty *tty = init.tty;
	if (high) assert(tty->fd > 1024);
	assert(mosaic_tty_enable_raw_mode(tty) == 0);
	assert(mosaic_tty_enable_window_resize_events(tty) == 0);
	MosaicTtyCallback resize = { .onResize = on_resize };
	mosaic_tty_set_callback(tty, &resize);
	callbacks = 0;
	assert(write(published, "r", 1) == 1);

	const int64_t start = monotonic_ms();
	expect_read(tty, 35, 0, 0);
	assert(monotonic_ms() - start >= 20);
	assert(write(published, "i", 1) == 1);
	uint8_t message;
	assert(read(proceed, &message, 1) == 1);
	struct winsize size = { .ws_col = 91, .ws_row = 29 };
	assert(ioctl(tty->fd, TIOCSWINSZ, &size) == 0);
	assert(raise(SIGWINCH) == 0);
	assert(mosaic_tty_interrupt_read(tty) == 0);
	expect_read(tty, 500, 0, 0);
	assert(callbacks == 1); // resize is dispatched prior to interrupt, and input stays unread.
	expect_read(tty, 500, 1, 'p');
	assert(mosaic_tty_free(tty) == 0);
	while (held_count > 0) close(held[--held_count]);
	close(devnull);
	_exit(0);
}

static void wait_ok(pid_t child) {
	int status;
	assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void controlling_terminal(bool high) {
	int ready[2], proceed[2], master;
	int diagnostics = dup(STDERR_FILENO);
	assert(diagnostics >= 0);
	assert(pipe(ready) == 0 && pipe(proceed) == 0);
	pid_t child = forkpty(&master, NULL, NULL, NULL);
	assert(child >= 0);
	if (child == 0) {
		assert(dup2(diagnostics, STDERR_FILENO) == STDERR_FILENO);
		close(diagnostics);
		close(ready[0]);
		close(proceed[1]);
		run_child(ready[1], proceed[0], high);
	}
	close(diagnostics);
	close(ready[1]);
	close(proceed[0]);
	uint8_t message;
	assert(read(ready[0], &message, 1) == 1 && message == 'r');
	assert(read(ready[0], &message, 1) == 1 && message == 'i');
	assert(write(master, "p", 1) == 1 && write(proceed[1], "g", 1) == 1);
	wait_ok(child);
	close(master);
	close(ready[0]);
	close(proceed[1]);
}

int main(void) {
	alarm(5);
	pid_t detached = fork();
	assert(detached >= 0);
	if (detached == 0) {
		assert(setsid() >= 0);
		MosaicTtyInitResult init = mosaic_tty_init();
		assert(init.no_tty && init.error && init.tty == NULL);
		_exit(0);
	}
	wait_ok(detached);
	controlling_terminal(false);
	controlling_terminal(true);
	puts("PASS controlling /dev/tty, redirected stdin, timeout, resize/interrupt, input, and high fd");
	return 0;
}
