#ifndef BUGSNAG_UTILS_STACK_UNWINDER_LIBCORKSCREW_H
#define BUGSNAG_UTILS_STACK_UNWINDER_LIBCORKSCREW_H

#include "../report.h"
#include <signal.h>

bool bsg_configure_libcorkscrew(void);

ssize_t
bsg_unwind_stack_libcorkscrew(bsg_stackframe stacktrace[BUGSNAG_FRAMES_MAX],
                              siginfo_t *info, void *user_context);
#endif
