/*
 * Wine keeps a file's Windows permissions (DACL) in its Unix mode bits: SetSecurityInfo on a file ends in
 * fchmod() in wineserver (server/file.c, file_set_sd), and a failed fchmod fails the whole call. Android's shared
 * storage (/storage/emulated/..., a FUSE file system) refuses chmod, so every program that sets permissions on a
 * folder there gets an error. Blizzard's Battle.net Agent does exactly that on the game folder ("fix the folder
 * permissions") and marks the game non-playable ("Permissions check failure", 2123).
 *
 * On FUSE, mode bits are fixed by Android and don't control access anyway, so when fchmod() fails there with a
 * "not supported / not permitted" error, report success. Every other fchmod() is passed through unchanged, and
 * real access to the files is still decided by Android.
 *
 * Each fchmod that fails, and each one let through, is noted in $WINEPREFIX/fuse_chmod.log (plus one line when
 * wineserver starts with the shim), so a debug report shows whether it was needed at all.
 *
 * Preloaded for bionic launches next to the other gamenative shims.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>

#define FUSE_SUPER_MAGIC 0x65735546

static int (*real_fchmod)(int, mode_t);

static void note(const char *format, ...) __attribute__((format(printf, 1, 2)));

static void note(const char *format, ...)
{
    const char *prefix = getenv("WINEPREFIX");
    char path[4096], line[1024];
    va_list args;
    int fd, len;

    if (!prefix || snprintf(path, sizeof(path), "%s/fuse_chmod.log", prefix) >= (int)sizeof(path)) return;
    va_start(args, format);
    len = vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    if (len <= 0) return;
    if (len >= (int)sizeof(line)) len = sizeof(line) - 1;
    fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) return;
    (void)write(fd, line, len);
    close(fd);
}

static const char *fd_path(int fd, char *buf, size_t size)
{
    char link[64];
    ssize_t n;

    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    n = readlink(link, buf, size - 1);
    if (n < 0) return "?";
    buf[n] = 0;
    return buf;
}

__attribute__((constructor)) static void announce(void)
{
    char cmdline[256] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    ssize_t n;
    const char *base;

    if (fd < 0) return;
    n = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (n <= 0) return;
    base = strrchr(cmdline, '/');
    base = base ? base + 1 : cmdline;
    if (strcmp(base, "wineserver") == 0) note("pid %d: loaded in wineserver\n", getpid());
}

int fchmod(int fd, mode_t mode)
{
    int saved_errno;
    struct statfs fs;
    char path[512];

    if (!real_fchmod) real_fchmod = (int (*)(int, mode_t))dlsym(RTLD_NEXT, "fchmod");
    if (!real_fchmod)
    {
        errno = ENOSYS;
        return -1;
    }
    if (real_fchmod(fd, mode) == 0) return 0;

    saved_errno = errno;
    if ((saved_errno == EPERM || saved_errno == EACCES || saved_errno == ENOSYS || saved_errno == EOPNOTSUPP) &&
        fstatfs(fd, &fs) == 0 && (unsigned long)fs.f_type == FUSE_SUPER_MAGIC)
    {
        note("pid %d: fchmod(%s, %o) refused by FUSE (%s), reported as done\n", getpid(),
             fd_path(fd, path, sizeof(path)), (unsigned)mode, strerror(saved_errno));
        return 0;
    }

    note("pid %d: fchmod(%s, %o) failed: %s (fs 0x%lx)\n", getpid(), fd_path(fd, path, sizeof(path)),
         (unsigned)mode, strerror(saved_errno), fstatfs(fd, &fs) == 0 ? (unsigned long)fs.f_type : 0UL);
    errno = saved_errno;
    return -1;
}
