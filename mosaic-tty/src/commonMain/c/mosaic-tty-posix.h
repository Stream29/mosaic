#ifndef MOSAIC_TTY_POSIX_H
#define MOSAIC_TTY_POSIX_H

#include "mosaic-tty.h"

#include <pthread.h>
#include <signal.h>

typedef struct MosaicTtyImpl {
	int fd;
	int interrupt_fd_reader;
	int interrupt_fd_writer;
	int resize_fd_reader;
	int resize_fd_writer;
	MosaicTtyCallback *callback;
	bool sigwinch;
	bool closing;
	bool reading;
	bool callback_running;
	bool deferred_free;
	unsigned writers;
	pthread_t read_thread;
	struct sigaction saved_sigwinch;
	struct termios *saved;
} MosaicTtyImpl;

MosaicTtyInitResult mosaic_tty_init_with_fd(int ttyFd);

#endif // MOSAIC_TTY_POSIX_H
