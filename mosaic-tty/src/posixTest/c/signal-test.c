// From mosaic-tty/, compile with cc -std=gnu11 -Wall -Wextra -Werror -pthread -Isrc/commonMain/c
// and inputs src/posixTest/c/signal-test.c src/commonMain/c/mosaic-utils-posix.c -o signal-test.
// The fixture owns all PTYs, threads and signals. No Kotlin runtime is involved.
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static atomic_int pauseWrite;
static atomic_int writeEntered;
static atomic_int releaseWrite;

static ssize_t fixture_write(int fd, const void *bytes, size_t size) {
	if (atomic_load(&pauseWrite)) {
		atomic_store(&writeEntered, 1);
		while (!atomic_load(&releaseWrite)) {}
	}
	return write(fd, bytes, size);
}

// Expose the admission gate and inject a paused write for the deterministic close race.
#define write fixture_write
#include "mosaic-tty-posix.c"
#undef write

// A failed fixture must not create core files or alter the host's core/ulimit policy.
#undef assert
#define assert(condition) do { \
	if (!(condition)) { \
		fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); \
		_exit(1); \
	} \
} while (0)

typedef struct Fixture {
	MosaicTty *tty;
	int parent;
} Fixture;

static atomic_int callbacks;
static atomic_int callbackEntered;
static atomic_int releaseCallback;
static atomic_int callbackMode;
static pthread_t callbackThread;
static int columns;
static int dimensions[3];

static void on_resize(void *opaque, int cols, int rows, int width, int height) {
	MosaicTty *tty = opaque;
	callbackThread = pthread_self();
	columns = cols;
	dimensions[0] = rows;
	dimensions[1] = width;
	dimensions[2] = height;
	atomic_fetch_add(&callbacks, 1);
	if (atomic_load(&callbackMode) == 1) {
		atomic_store(&callbackEntered, 1);
		while (!atomic_load(&releaseCallback)) usleep(100);
	} else if (atomic_load(&callbackMode) == 2) {
		mosaic_tty_set_callback(tty, NULL);
	} else if (atomic_load(&callbackMode) == 3) {
		assert(mosaic_tty_free(tty) == 0);
	} else if (atomic_load(&callbackMode) == 4) {
		assert(mosaic_tty_reset(tty) == 0);
	} else if (atomic_load(&callbackMode) == 5 && atomic_load(&callbacks) < 200) {
		// Refill before returning, with no poll-empty gap that could hide a deadline starvation.
		usleep(1000);
		assert(raise(SIGWINCH) == 0);
	}
}

static MosaicTtyCallback callback = { .onResize = on_resize };

static Fixture open_fixture(void) {
	Fixture f = {};
	f.parent = posix_openpt(O_RDWR | O_NOCTTY);
	assert(f.parent >= 0);
	assert(grantpt(f.parent) == 0);
	assert(unlockpt(f.parent) == 0);
	int child = open(ptsname(f.parent), O_RDWR | O_NOCTTY);
	assert(child >= 0);
	MosaicTtyInitResult result = mosaic_tty_init_with_fd(child);
	assert(result.tty && !result.error);
	f.tty = result.tty;
	assert(mosaic_tty_enable_raw_mode(f.tty) == 0);
	assert(mosaic_tty_enable_window_resize_events(f.tty) == 0);
	callback.opaque = f.tty;
	mosaic_tty_set_callback(f.tty, &callback);
	atomic_store(&callbacks, 0);
	atomic_store(&callbackMode, 0);
	atomic_store(&callbackEntered, 0);
	atomic_store(&releaseCallback, 0);
	return f;
}

static void close_fixture(Fixture f) {
	assert(mosaic_tty_free(f.tty) == 0);
	assert(close(f.parent) == 0);
}

static void resize_fixture(Fixture f, int cols) {
	struct winsize size = { .ws_col = cols, .ws_row = 37, .ws_xpixel = 900, .ws_ypixel = 600 };
	assert(ioctl(f.parent, TIOCSWINSZ, &size) == 0);
	// O_NOCTTY avoids changing this process's foreground group. Signal only this fixture process.
	assert(raise(SIGWINCH) == 0);
}

static MosaicIoResult timed_read(MosaicTty *tty, int timeout) {
	uint8_t byte;
	return mosaic_tty_read_with_timeout(tty, &byte, 1, timeout);
}

static void wait_for(atomic_int *value) {
	for (int i = 0; i < 10000 && !atomic_load(value); i++) usleep(100);
	assert(atomic_load(value));
}

typedef struct Reader {
	MosaicTty *tty;
	MosaicIoResult result;
	atomic_int done;
} Reader;

static void *read_thread(void *opaque) {
	Reader *reader = opaque;
	uint8_t byte;
	reader->result = mosaic_tty_read(reader->tty, &byte, 1);
	atomic_store(&reader->done, 1);
	return NULL;
}

static pthread_t start_reader(Reader *reader) {
	pthread_t thread;
	assert(pthread_create(&thread, NULL, read_thread, reader) == 0);
	for (int i = 0; i < 10000; i++) {
		lock_tty();
		bool reading = reader->tty->reading;
		pthread_mutex_unlock(&ttyMutex);
		if (reading) return thread;
		usleep(100);
	}
	assert(false);
	return thread;
}

static void deferred_and_input(void) {
	Fixture f = open_fixture();
	resize_fixture(f, 123);
	assert(atomic_load(&callbacks) == 0);
	errno = EDOM;
	mosaic_tty_sigwinch_handler(SIGWINCH);
	assert(errno == EDOM);
	uint8_t incoming = 'x', outgoing = 0;
	assert(write(f.parent, &incoming, 1) == 1);
	MosaicIoResult result = mosaic_tty_read(f.tty, &outgoing, 1);
	assert(!result.error && result.count == 1 && outgoing == incoming);
	assert(atomic_load(&callbacks) == 1 && columns == 123);
	assert(dimensions[0] == 37 && dimensions[1] == 900 && dimensions[2] == 600);
	assert(pthread_equal(callbackThread, pthread_self()));
	close_fixture(f);
	puts("PASS deferred callback, errno preservation, coalescing, resize before input");
}

static void resize_does_not_cancel(void) {
	Fixture f = open_fixture();
	Reader reader = { .tty = f.tty };
	pthread_t thread = start_reader(&reader);
	resize_fixture(f, 124);
	wait_for(&callbacks);
	usleep(20000);
	assert(!atomic_load(&reader.done));
	assert(pthread_equal(callbackThread, thread));
	assert(mosaic_tty_interrupt_read(f.tty) == 0);
	assert(pthread_join(thread, NULL) == 0);
	assert(!reader.result.error && reader.result.count == 0);
	assert(timed_read(f.tty, 0).count == 0);
	close_fixture(f);
	puts("PASS resize-only keeps read alive; explicit interrupt wakes same reader");
}

static void full_pipes_and_bind(void) {
	Fixture f = open_fixture();
	int fd = open("/dev/null", O_RDONLY);
	assert(fd >= 0);
	MosaicTtyInitResult rejected = mosaic_tty_init_with_fd(fd);
	assert(!rejected.tty && rejected.already_bound && !rejected.error);
	assert(fcntl(fd, F_GETFD) != -1);
	assert(close(fd) == 0);
	fd = dup(f.tty->fd);
	assert(fd >= 0);
	rejected = mosaic_tty_init_with_fd(fd);
	assert(rejected.already_bound);
	assert(close(fd) == 0);
	assert(globalTty == f.tty);
	uint8_t byte = 1;
	while (write(f.tty->resize_fd_writer, &byte, 1) == 1) {}
	assert(errno == EAGAIN || errno == EWOULDBLOCK);
	resize_fixture(f, 125);
	MosaicIoResult result = timed_read(f.tty, 0);
	assert(!result.error && !result.count);
	assert(atomic_load(&callbacks) == 1 && columns == 125);
	for (int i = 0; i < 100000; i++) assert(mosaic_tty_interrupt_read(f.tty) == 0);
	result = timed_read(f.tty, 0);
	assert(!result.error && !result.count);
	close_fixture(f);
	puts("PASS full nonblocking pipes and repeated rejected binding preserve owner");
}

typedef struct Flood {
	pthread_t target;
	atomic_int stop;
} Flood;

static void ignore_signal(int signal UNUSED) {}

static void *flood_thread(void *opaque) {
	Flood *flood = opaque;
	while (!atomic_load(&flood->stop)) {
		assert(pthread_kill(flood->target, SIGWINCH) == 0);
		assert(pthread_kill(flood->target, SIGUSR1) == 0);
		usleep(300);
	}
	return NULL;
}

static void timeout_and_eintr(void) {
	Fixture f = open_fixture();
	struct sigaction action = { .sa_handler = ignore_signal }, old;
	sigemptyset(&action.sa_mask);
	assert(sigaction(SIGUSR1, &action, &old) == 0);
	Flood flood = { .target = pthread_self() };
	pthread_t thread;
	assert(pthread_create(&thread, NULL, flood_thread, &flood) == 0);
	int64_t start = monotonic_millis();
	MosaicIoResult result = timed_read(f.tty, 80);
	int64_t elapsed = monotonic_millis() - start;
	atomic_store(&flood.stop, 1);
	assert(pthread_join(thread, NULL) == 0);
	assert(!result.error && !result.count);
	assert(elapsed >= 60 && elapsed < 500);
	assert(atomic_load(&callbacks) > 0);
	assert(sigaction(SIGUSR1, &old, NULL) == 0);
	close_fixture(f);
	printf("PASS timeout across SIGWINCH and unrelated EINTR (%lldms)\n", (long long) elapsed);
}

static void timeout_with_always_ready_resize(void) {
	for (int timeout = 0; timeout <= 80; timeout += 80) {
		Fixture f = open_fixture();
		atomic_store(&callbackMode, 5);
		resize_fixture(f, 124);
		int64_t start = monotonic_millis();
		MosaicIoResult result = timed_read(f.tty, timeout);
		int64_t elapsed = monotonic_millis() - start;
		assert(!result.error && !result.count);
		assert(elapsed < 180);
		if (timeout == 0) assert(atomic_load(&callbacks) == 1);
		else {
			assert(elapsed >= 60);
			assert(atomic_load(&callbacks) > 1 && atomic_load(&callbacks) < 200);
		}
		atomic_store(&callbackMode, 0);
		// The last callback deliberately left a notification pending. Ordinary input remains readable.
		assert(write(f.parent, "q", 1) == 1);
		uint8_t byte;
		result = mosaic_tty_read_with_timeout(f.tty, &byte, 1, 0);
		assert(!result.error && result.count == 1 && byte == 'q');
		close_fixture(f);
	}
	puts("PASS zero and finite timeout stay bounded with resize ready after every callback");
}

static void *clear_thread(void *opaque) {
	Reader *state = opaque;
	mosaic_tty_set_callback(state->tty, NULL);
	atomic_store(&state->done, 1);
	return NULL;
}

static void callback_barrier(void) {
	Fixture f = open_fixture();
	atomic_store(&callbackMode, 1);
	Reader reader = { .tty = f.tty }, clearer = { .tty = f.tty };
	pthread_t thread = start_reader(&reader);
	resize_fixture(f, 126);
	wait_for(&callbackEntered);
	// Foreign callbacks must not retain the lifecycle mutex while waiting on application work.
	MosaicTtyTerminalSizeResult size = mosaic_tty_current_terminal_size(f.tty);
	assert(!size.error && size.columns == 126);
	pthread_t clear;
	assert(pthread_create(&clear, NULL, clear_thread, &clearer) == 0);
	usleep(20000);
	assert(!atomic_load(&clearer.done));
	atomic_store(&releaseCallback, 1);
	assert(pthread_join(clear, NULL) == 0);
	assert(mosaic_tty_interrupt_read(f.tty) == 0);
	assert(pthread_join(thread, NULL) == 0);
	resize_fixture(f, 127);
	assert(!timed_read(f.tty, 0).error);
	assert(atomic_load(&callbacks) == 1);
	close_fixture(f);
	puts("PASS foreign callback releases lifecycle lock; clear waits; no callback after clear");
}

static void *close_thread(void *opaque) {
	Reader *state = opaque;
	assert(mosaic_tty_free(state->tty) == 0);
	atomic_store(&state->done, 1);
	return NULL;
}

static void *one_signal_thread(void *opaque UNUSED) {
	assert(raise(SIGWINCH) == 0);
	return NULL;
}

static void close_admitted_handler(void) {
	Fixture f = open_fixture();
	atomic_store(&writeEntered, 0);
	atomic_store(&releaseWrite, 0);
	atomic_store(&pauseWrite, 1);
	pthread_t signalThread;
	assert(pthread_create(&signalThread, NULL, one_signal_thread, NULL) == 0);
	wait_for(&writeEntered);
	Reader closer = { .tty = f.tty };
	pthread_t closeThread;
	assert(pthread_create(&closeThread, NULL, close_thread, &closer) == 0);
	usleep(20000);
	assert(!atomic_load(&closer.done));
	atomic_store(&releaseWrite, 1);
	assert(pthread_join(signalThread, NULL) == 0);
	assert(pthread_join(closeThread, NULL) == 0);
	atomic_store(&pauseWrite, 0);
	assert(close(f.parent) == 0);
	int fds[2];
	assert(create_pipe(fds) == 0);
	mosaic_tty_sigwinch_handler(SIGWINCH);
	uint8_t byte;
	assert(read(fds[0], &byte, 1) == -1 && (errno == EAGAIN || errno == EWOULDBLOCK));
	close(fds[0]);
	close(fds[1]);
	puts("PASS close waits for admitted handler before fd reuse; disarmed handler is inert");
}

static void close_blocked_reader(void) {
	Fixture f = open_fixture();
	Reader reader = { .tty = f.tty };
	pthread_t thread = start_reader(&reader);
	assert(mosaic_tty_free(f.tty) == 0);
	assert(pthread_join(thread, NULL) == 0);
	assert(!reader.result.error && !reader.result.count);
	close(f.parent);
	puts("PASS close wakes and joins active read before releasing descriptors");
}

enum { BlockedWriteSize = 64 * 1024 };

static void *write_thread(void *opaque) {
	Reader *writer = opaque;
	uint8_t bytes[BlockedWriteSize] = {};
	writer->result = mosaic_tty_write(writer->tty, bytes, sizeof(bytes));
	atomic_store(&writer->done, 1);
	return NULL;
}

static void blocked_output_does_not_block_interrupt(void) {
	Fixture f = open_fixture();
	int flags = fcntl(f.tty->fd, F_GETFL);
	assert(fcntl(f.tty->fd, F_SETFL, flags | O_NONBLOCK) == 0);
	uint8_t bytes[4096] = {};
	while (write(f.tty->fd, bytes, sizeof(bytes)) > 0) {}
	assert(errno == EAGAIN || errno == EWOULDBLOCK);
	assert(fcntl(f.tty->fd, F_SETFL, flags) == 0);
	Reader writer = { .tty = f.tty }, reader = { .tty = f.tty };
	pthread_t writerThread;
	assert(pthread_create(&writerThread, NULL, write_thread, &writer) == 0);
	for (int i = 0; i < 10000; i++) {
		lock_tty();
		bool writing = f.tty->writers != 0;
		pthread_mutex_unlock(&ttyMutex);
		if (writing) break;
		assert(i < 9999);
		usleep(100);
	}
	pthread_t readerThread = start_reader(&reader);
	assert(mosaic_tty_interrupt_read(f.tty) == 0);
	assert(pthread_join(readerThread, NULL) == 0);
	assert(!reader.result.error && !reader.result.count);
	assert(fcntl(f.parent, F_SETFL, O_NONBLOCK) == 0);
	for (int i = 0; i < 10000 && !atomic_load(&writer.done); i++) {
		read(f.parent, bytes, sizeof(bytes));
		usleep(100);
	}
	wait_for(&writer.done);
	assert(pthread_join(writerThread, NULL) == 0);
	assert(!writer.result.error && writer.result.count == BlockedWriteSize);
	// TCSAFLUSH may wait for queued output on macOS; drain our deliberately saturated PTY first.
	while (read(f.parent, bytes, sizeof(bytes)) > 0) {}
	close_fixture(f);
	puts("PASS blocked output does not hold the reader/interrupt lifecycle lock");
}

static void high_fd(void) {
	Fixture f = open_fixture();
	int high = fcntl(f.tty->fd, F_DUPFD, 1100);
	if (high < 0) {
		close_fixture(f);
		puts("SKIP high fd: host descriptor limit (unchanged)");
		return;
	}
	close(f.tty->fd);
	f.tty->fd = high;
	resize_fixture(f, 131);
	assert(!timed_read(f.tty, 0).error && columns == 131);
	close_fixture(f);
	puts("PASS poll accepts a TTY fd beyond FD_SETSIZE");
}

#if defined(__linux__)
static void kernel_pty_resize(void) {
	int parent = posix_openpt(O_RDWR | O_NOCTTY);
	assert(parent >= 0 && !grantpt(parent) && !unlockpt(parent));
	int ready[2];
	assert(pipe(ready) == 0);
	pid_t child = fork();
	assert(child >= 0);
	if (child == 0) {
		close(ready[0]);
		assert(setsid() >= 0);
		int fd = open(ptsname(parent), O_RDWR);
		assert(fd >= 0 && ioctl(fd, TIOCSCTTY, 0) == 0);
		MosaicTtyInitResult init = mosaic_tty_init_with_fd(fd);
		assert(init.tty && !init.error);
		assert(!mosaic_tty_enable_raw_mode(init.tty));
		assert(!mosaic_tty_enable_window_resize_events(init.tty));
		callback.opaque = init.tty;
		mosaic_tty_set_callback(init.tty, &callback);
		atomic_store(&callbacks, 0);
		atomic_store(&callbackMode, 0);
		assert(write(ready[1], "r", 1) == 1);
		close(ready[1]);
		uint8_t byte;
		MosaicIoResult result = mosaic_tty_read_with_timeout(init.tty, &byte, 1, 2000);
		assert(!result.error && result.count == 1 && byte == 'q');
		assert(atomic_load(&callbacks) > 0 && columns == 399);
		assert(!mosaic_tty_free(init.tty));
		close(parent);
		_exit(0);
	}
	close(ready[1]);
	char byte;
	assert(read(ready[0], &byte, 1) == 1);
	close(ready[0]);
	for (int i = 0; i < 200; i++) {
		struct winsize size = { .ws_col = 200 + i, .ws_row = 40 + i % 2 };
		assert(ioctl(parent, TIOCSWINSZ, &size) == 0);
		usleep(1000);
	}
	assert(write(parent, "q", 1) == 1);
	int status;
	assert(waitpid(child, &status, 0) == child);
	assert(WIFEXITED(status) && !WEXITSTATUS(status));
	close(parent);
	puts("PASS Linux kernel PTY SIGWINCH: 200 resizes, latest size, read remains live");
}
#endif

static void reentrant_callback(void) {
	for (int mode = 2; mode <= 4; mode++) {
		Fixture f = open_fixture();
		atomic_store(&callbackMode, mode);
		resize_fixture(f, 128);
		if (mode == 4) assert(write(f.parent, "x", 1) == 1);
		MosaicIoResult result = timed_read(f.tty, 0);
		assert(!result.error && !result.count && atomic_load(&callbacks) == 1);
		if (mode != 3) close_fixture(f);
		else close(f.parent);
	}
	puts("PASS callback clear/close/reset; reset-flushed input cannot cause a stale-readiness hang");
}

static int fd_count(void) {
	int count = 0;
	for (int fd = 0; fd < 512; fd++) if (fcntl(fd, F_GETFD) != -1) count++;
	return count;
}

static void reset_rebind(void) {
	struct sigaction saved = { .sa_handler = ignore_signal }, old, restored;
	sigemptyset(&saved.sa_mask);
	assert(sigaction(SIGWINCH, &saved, &old) == 0);
	int before = fd_count();
	for (int i = 0; i < 100; i++) {
		Fixture f = open_fixture();
		resize_fixture(f, 129);
		assert(mosaic_tty_reset(f.tty) == 0);
		assert(sigaction(SIGWINCH, NULL, &restored) == 0);
		assert(restored.sa_handler == ignore_signal);
		assert(mosaic_tty_enable_window_resize_events(f.tty) == 0);
		assert(!timed_read(f.tty, 0).error);
		assert(atomic_load(&callbacks) == 0);
		resize_fixture(f, 130);
		assert(!timed_read(f.tty, 0).error && atomic_load(&callbacks) == 1);
		close_fixture(f);
	}
	assert(fd_count() == before);
	assert(sigaction(SIGWINCH, &old, NULL) == 0);
	puts("PASS 100 reset/re-enable/rebind cycles restore disposition and release all fds");
}

int main(void) {
	setvbuf(stdout, NULL, _IONBF, 0);
	alarm(20);
	deferred_and_input();
	resize_does_not_cancel();
	full_pipes_and_bind();
	timeout_and_eintr();
	timeout_with_always_ready_resize();
	callback_barrier();
	close_admitted_handler();
	close_blocked_reader();
	blocked_output_does_not_block_interrupt();
	reentrant_callback();
	high_fd();
	reset_rebind();
#if defined(__linux__)
	kernel_pty_resize();
#endif
	puts("PASS all standalone C signal regressions (not Kotlin/Native GC validation)");
	return 0;
}
