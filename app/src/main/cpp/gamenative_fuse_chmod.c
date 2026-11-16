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
 * Preloaded for bionic launches next to the other gamenative shims.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/vfs.h>

#define FUSE_SUPER_MAGIC 0x65735546

static int (*real_fchmod)(int, mode_t);

int fchmod(int fd, mode_t mode)
{
    int saved_errno;
    struct statfs fs;

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
        return 0;

    errno = saved_errno;
    return -1;
}
