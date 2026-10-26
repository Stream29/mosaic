#if defined(__APPLE__) || defined(__linux__)

#include "mosaic-tty-posix.h"
#include "mosaic-utils-posix.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

// Only the handler touches these without ttyMutex. The low bit admits handlers; the remaining
// bits pin signalWriter until reset has disarmed admission and all admitted handlers have left.
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "SIGWINCH requires lock-free integer atomics");
static atomic_uint signalGate;
static atomic_int signalWriter;
static atomic_int resizePending;

static MosaicTty *globalTty;
static pthread_mutex_t ttyMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t readFinished = PTHREAD_COND_INITIALIZER;

static void lock_tty(void) {
	pthread_mutex_lock(&ttyMutex);
}

static bool is_open(MosaicTty *tty) {
	return tty && globalTty == tty && !tty->closing;
}

static int create_pipe(int fds[2]) {
	if (pipe(fds) != 0) return errno;
	for (int i = 0; i < 2; i++) {
		if (fcntl(fds[i], F_SETFL, O_NONBLOCK) == -1 ||
			fcntl(fds[i], F_SETFD, FD_CLOEXEC) == -1) {
			int error = errno;
			close(fds[0]);
			close(fds[1]);
			return error;
		}
	}
	return 0;
}

static uint32_t notify(int fd) {
	uint8_t byte = 1;
	ssize_t written;
	do {
		written = write(fd, &byte, 1);
	} while (written == -1 && errno == EINTR);
	// A full pipe already represents a pending wakeup. Never block the signalling thread.
	return written == 1 || errno == EAGAIN || errno == EWOULDBLOCK ? 0 : errno;
}

static void mosaic_tty_sigwinch_handler(int value UNUSED) {
	int savedErrno = errno;
	unsigned state = atomic_load(&signalGate);
	while (state & 1U) {
		if (atomic_compare_exchange_weak(&signalGate, &state, state + 2U)) {
			if (!atomic_exchange(&resizePending, 1)) {
				notify(atomic_load(&signalWriter));
			}
			atomic_fetch_sub(&signalGate, 2U);
			break;
		}
	}
	errno = savedErrno;
}

static void disarm_signal(void) {
	atomic_fetch_and(&signalGate, ~1U);
	// Waiting is confined to normal context, never the signal handler.
	while (atomic_load(&signalGate) != 0) sched_yield();
}

MosaicTtyInitResult mosaic_tty_init_with_fd(int fd) {
	MosaicTtyInitResult result = {};
	lock_tty();
	if (globalTty) {
		result.already_bound = true;
		goto ret;
	}

	MosaicTtyImpl *tty = calloc(1, sizeof(MosaicTtyImpl));
	if (unlikely(tty == NULL)) {
		// result.tty is set to 0 which will trigger OOM.
		goto ret;
	}

	int interruptPipe[2];
	result.error = create_pipe(interruptPipe);
	if (result.error) {
		goto err;
	}
	int resizePipe[2];
	result.error = create_pipe(resizePipe);
	if (result.error) {
		close(interruptPipe[0]);
		close(interruptPipe[1]);
		goto err;
	}

	tty->fd = fd;
	tty->interrupt_fd_reader = interruptPipe[0];
	tty->interrupt_fd_writer = interruptPipe[1];
	tty->resize_fd_reader = resizePipe[0];
	tty->resize_fd_writer = resizePipe[1];

	result.tty = tty;
	globalTty = tty;

	ret:
	pthread_mutex_unlock(&ttyMutex);
	return result;

	err:
	free(tty);
	goto ret;
}

MosaicTtyInitResult mosaic_tty_init(void) {
	int fd = open("/dev/tty", O_RDWR);
	if (likely(fd != -1)) {
		MosaicTtyInitResult result = mosaic_tty_init_with_fd(fd);
		if (!result.tty) close(fd);
		return result;
	}

	MosaicTtyInitResult result = {};
	result.error = errno;
	result.no_tty = true;
	return result;
}

void mosaic_tty_set_callback(MosaicTty *tty, MosaicTtyCallback *callback) {
	lock_tty();
	if (is_open(tty)) {
		tty->callback = callback;
		// No mutex is held across foreign code. Wait for the old callback to return before its
		// caller reclaims it. Reentrant FFM callers retain their stub until the read returns.
		if (tty->reading && !pthread_equal(tty->read_thread, pthread_self())) {
			while (tty->callback_running) pthread_cond_wait(&readFinished, &ttyMutex);
		}
	}
	pthread_mutex_unlock(&ttyMutex);
}

static int64_t monotonic_millis(void) {
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static uint32_t destroy_tty(MosaicTty *tty);

static MosaicIoResult read_internal(MosaicTty *tty, uint8_t *buffer, int count, int timeoutMillis) {
	MosaicIoResult result = {};
	int64_t deadline = timeoutMillis < 0 ? 0 : monotonic_millis() + timeoutMillis;
	lock_tty();
	if (!is_open(tty)) {
		result.error = EBADF;
		goto ret;
	}
	if (tty->reading) {
		result.error = EBUSY;
		goto ret;
	}
	if (count <= 0) goto ret;
	tty->reading = true;
	tty->read_thread = pthread_self();

	struct pollfd fds[] = {
		{ .fd = tty->fd, .events = POLLIN },
		{ .fd = tty->interrupt_fd_reader, .events = POLLIN },
		{ .fd = tty->resize_fd_reader, .events = POLLIN },
	};
	bool firstPoll = true;
	for (;;) {
		if (tty->closing) break;
		int64_t remaining = deadline - monotonic_millis();
		// Permit one nonblocking attempt, but never let ready resize/EINTR retries extend a deadline.
		if (!firstPoll && timeoutMillis >= 0 && remaining <= 0) break;
		firstPoll = false;
		int timeout = timeoutMillis < 0 ? -1 : remaining > 0 ? (int) remaining : 0;
		pthread_mutex_unlock(&ttyMutex);
		int ready = poll(fds, 3, timeout);
		int pollError = errno;
		lock_tty();
		if (tty->closing) break;
		if (ready < 0) {
			if (pollError == EINTR) continue;
			result.error = pollError;
			break;
		}
		if (ready == 0) break;
		if (fds[2].revents & POLLIN) {
			uint8_t bytes[64];
			// Only a bounded drain is needed: resizePending coalesces signals before the query.
			while (read(tty->resize_fd_reader, bytes, sizeof(bytes)) < 0 && errno == EINTR) {}
			bool pending = atomic_exchange(&resizePending, 0);
			if (pending && tty->sigwinch && tty->callback && tty->callback->onResize) {
				struct winsize size;
				if (ioctl(tty->fd, TIOCGWINSZ, &size) != -1) {
					MosaicTtyCallback *callback = tty->callback;
					tty->callback_running = true;
					pthread_mutex_unlock(&ttyMutex);
					callback->onResize(callback->opaque, size.ws_col, size.ws_row, size.ws_xpixel, size.ws_ypixel);
					lock_tty();
					tty->callback_running = false;
					pthread_cond_broadcast(&readFinished);
				}
			}
			if (tty->closing) break;
		}
		if (fds[1].revents & POLLIN) {
			uint8_t byte;
			while (read(tty->interrupt_fd_reader, &byte, 1) < 0 && errno == EINTR) {}
			break;
		}
		if (fds[0].revents & (POLLIN | POLLHUP)) {
			// Reset (including from the callback) can flush input after the original poll.
			// Recheck under the lifecycle lock rather than blocking on stale readiness.
			struct pollfd input = { .fd = tty->fd, .events = POLLIN };
			if (poll(&input, 1, 0) < 0) {
				if (errno == EINTR) continue;
				result.error = errno;
				break;
			}
			if (!(input.revents & (POLLIN | POLLHUP))) continue;
			ssize_t c = read(tty->fd, buffer, count);
			if (c < 0 && errno == EINTR) continue;
			if (c < 0) result.error = errno;
			else result.count = c == 0 ? -1 : (int) c;
			break;
		}
		if ((fds[0].revents | fds[1].revents | fds[2].revents) & (POLLERR | POLLNVAL | POLLHUP)) {
			result.error = EIO;
			break;
		}
		// A resize alone must not return 0: EventParser interprets that as cancellation.
		// The original deadline is retained across resize notifications and EINTR.
	}
	tty->reading = false;
	pthread_cond_broadcast(&readFinished);
	if (tty->deferred_free && !tty->writers) destroy_tty(tty);
	ret:
	pthread_mutex_unlock(&ttyMutex);
	return result;
}

MosaicIoResult mosaic_tty_read(MosaicTty *tty, uint8_t *buffer, int count) {
	return read_internal(tty, buffer, count, -1);
}

MosaicIoResult mosaic_tty_read_with_timeout(MosaicTty *tty, uint8_t *buffer, int count, int timeoutMillis) {
	return read_internal(tty, buffer, count, timeoutMillis);
}

uint32_t mosaic_tty_interrupt_read(MosaicTty *tty) {
	lock_tty();
	uint32_t result = is_open(tty) ? notify(tty->interrupt_fd_writer) : EBADF;
	pthread_mutex_unlock(&ttyMutex);
	return result;
}

MosaicIoResult mosaic_tty_write(MosaicTty *tty, uint8_t *buffer, int count) {
	lock_tty();
	MosaicIoResult result = { .error = EBADF };
	if (is_open(tty)) {
		tty->writers++;
		pthread_mutex_unlock(&ttyMutex);
		// A full output queue must not stop another thread from interrupting the reader.
		result = mosaic_utils_write(tty->fd, buffer, count);
		lock_tty();
		tty->writers--;
		pthread_cond_broadcast(&readFinished);
		if (tty->deferred_free && !tty->reading && !tty->writers) destroy_tty(tty);
	}
	pthread_mutex_unlock(&ttyMutex);
	return result;
}

uint32_t mosaic_tty_enable_raw_mode(MosaicTty *tty) {
	uint32_t result = 0;
	lock_tty();
	if (!is_open(tty)) {
		result = EBADF;
		goto ret;
	}

	if (unlikely(tty->saved)) {
		goto ret; // Already enabled!
	}

	struct termios *saved = calloc(1, sizeof(struct termios));
	if (unlikely(saved == NULL)) {
		result = ENOMEM;
		goto ret;
	}

	if (unlikely(tcgetattr(tty->fd, saved) != 0)) {
		result = errno;
		goto err;
	}

	struct termios current = (*saved);

	// Flags as defined by "Raw mode" section of https://linux.die.net/man/3/termios
	current.c_iflag &= ~(BRKINT | ICRNL | IGNBRK | IGNCR | INLCR | ISTRIP | IXON | PARMRK);
	current.c_oflag &= ~(OPOST);
	// Setting ECHONL should be useless here, but it is what is documented for cfmakeraw.
	current.c_lflag &= ~(ECHO | ECHONL | ICANON | IEXTEN | ISIG);
	current.c_cflag &= ~(CSIZE | PARENB);
	current.c_cflag |= (CS8);

	current.c_cc[VMIN] = 1;
	current.c_cc[VTIME] = 0;

	if (unlikely(tcsetattr(tty->fd, TCSAFLUSH, &current) != 0)) {
		result = errno;
		// Try to restore the saved config.
		tcsetattr(tty->fd, TCSAFLUSH, saved);
		goto err;
	}

	tty->saved = saved;

	ret:
	pthread_mutex_unlock(&ttyMutex);
	return result;

	err:
	free(saved);
	goto ret;
}

uint32_t mosaic_tty_enable_window_resize_events(MosaicTty *tty) {
	lock_tty();
	uint32_t result = 0;
	if (!is_open(tty)) {
		result = EBADF;
		goto ret;
	}
	if (tty->sigwinch) {
		goto ret;
	}

	struct sigaction action;
	action.sa_handler = mosaic_tty_sigwinch_handler;
	sigemptyset(&action.sa_mask);
	action.sa_flags = SA_RESTART;

	atomic_store(&signalWriter, tty->resize_fd_writer);
	atomic_store(&resizePending, 0);
	atomic_store(&signalGate, 1U);
	if (likely(sigaction(SIGWINCH, &action, &tty->saved_sigwinch) == 0)) {
		tty->sigwinch = true;
	} else {
		result = errno;
		disarm_signal();
	}
	ret:
	pthread_mutex_unlock(&ttyMutex);
	return result;
}

MosaicTtyTerminalSizeResult mosaic_tty_current_terminal_size(MosaicTty *tty) {
	MosaicTtyTerminalSizeResult result = {};
	lock_tty();
	if (!is_open(tty)) {
		result.error = EBADF;
		goto ret;
	}

	struct winsize size;
	if (ioctl(tty->fd, TIOCGWINSZ, &size) != -1) {
		result.columns = size.ws_col;
		result.rows = size.ws_row;
		result.width = size.ws_xpixel;
		result.height = size.ws_ypixel;
	} else {
		result.error = errno;
	}

	ret:
	pthread_mutex_unlock(&ttyMutex);
	return result;
}

static uint32_t reset_internal(MosaicTty *tty) {
	uint32_t result = 0;

	if (tty->sigwinch) {
		disarm_signal();
		if (unlikely(sigaction(SIGWINCH, &tty->saved_sigwinch, NULL) != 0)) {
			result = errno;
		}
		tty->sigwinch = false;
		atomic_store(&resizePending, 0);
		uint8_t bytes[64];
		while (read(tty->resize_fd_reader, bytes, sizeof(bytes)) > 0 || errno == EINTR) {}
	}

	if (tty->saved) {
		if (unlikely(tcsetattr(tty->fd, TCSAFLUSH, tty->saved) && result == 0)) {
			result = errno;
		}
		free(tty->saved);
		tty->saved = NULL;
	}

	return result;
}

uint32_t mosaic_tty_reset(MosaicTty *tty) {
	lock_tty();
	uint32_t result = is_open(tty) ? reset_internal(tty) : EBADF;
	pthread_mutex_unlock(&ttyMutex);
	return result;
}

static uint32_t destroy_tty(MosaicTty *tty) {
	uint32_t result = 0;

	if (unlikely(close(tty->fd) != 0 && result == 0)) {
		result = errno;
	}

	if (unlikely(close(tty->interrupt_fd_reader) != 0 && result == 0)) {
		result = errno;
	}
	if (unlikely(close(tty->interrupt_fd_writer) != 0 && result == 0)) {
		result = errno;
	}
	if (unlikely(close(tty->resize_fd_reader) != 0 && result == 0)) {
		result = errno;
	}
	if (unlikely(close(tty->resize_fd_writer) != 0 && result == 0)) {
		result = errno;
	}

	globalTty = NULL;
	free(tty);
	return result;
}

uint32_t mosaic_tty_free(MosaicTty *tty) {
	lock_tty();
	if (!is_open(tty)) {
		pthread_mutex_unlock(&ttyMutex);
		return EBADF;
	}
	tty->closing = true;
	tty->callback = NULL;
	uint32_t result = reset_internal(tty);
	if (tty->reading) {
		notify(tty->interrupt_fd_writer);
		if (pthread_equal(tty->read_thread, pthread_self())) {
			// A callback may close its own TTY. Its read frame retains the allocation until return.
			tty->deferred_free = true;
			pthread_mutex_unlock(&ttyMutex);
			return result;
		}
	}
	while (tty->reading || tty->writers) pthread_cond_wait(&readFinished, &ttyMutex);
	uint32_t closeResult = destroy_tty(tty);
	pthread_mutex_unlock(&ttyMutex);
	return result ? result : closeResult;
}

#endif
