#include <dirent.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns, .ws_xpixel = (unsigned short) (columns * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height)};
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) exit(-1);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        if (chdir(cwd) != 0) {
            char* error_message;
            // No need to free asprintf()-allocated memory since doing execvp() or exit() below.
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
        execvp(cmd, argv);
        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) malloc((size + 1) * sizeof(char*));
        if (!argv) return throw_runtime_exception(env, "Couldn't allocate argv array");
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* arg_utf8 = (*env)->GetStringUTFChars(env, arg_java_string, NULL);
            if (!arg_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for argv");
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) malloc((size + 1) * sizeof(char *));
        if (!envp) return throw_runtime_exception(env, "malloc() for envp array failed");
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
            char const* env_utf8 = (*env)->GetStringUTFChars(env, env_java_string, 0);
            if (!env_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for env");
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    char const* cmd_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    char const* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId, rows, columns, cell_width, cell_height);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);

    if (argv) {
        for (char** tmp = argv; *tmp; ++tmp) free(*tmp);
        free(argv);
    }
    if (envp) {
        for (char** tmp = envp; *tmp; ++tmp) free(*tmp);
        free(envp);
    }

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed");

    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols, .ws_xpixel = (unsigned short) (cols * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height) };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}

/* Graphics media is resolved by the runtime, never by the Android renderer.
 * Open every component relative to pinned directory descriptors. This prevents
 * a guest racing a checked path into another runtime's files via symlinks. */
static void graphics_io_error(JNIEnv* env, const char* message) {
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception) (*env)->ThrowNew(env, exception, message);
}

static char* graphics_path(JNIEnv* env, jbyteArray bytes) {
    jsize size = (*env)->GetArrayLength(env, bytes);
    if (size <= 0 || size > 4096) { graphics_io_error(env, "EINVAL: media path"); return NULL; }
    char* path = malloc((size_t) size + 1);
    if (!path) { graphics_io_error(env, "ENOSPC: media path"); return NULL; }
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte*) path);
    if ((*env)->ExceptionCheck(env)) { free(path); return NULL; }
    path[size] = 0;
    if (memchr(path, 0, (size_t) size)) { free(path); graphics_io_error(env, "EINVAL: media path"); return NULL; }
    return path;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GraphicsFileReader_readBelow(JNIEnv* env, jclass TERMUX_UNUSED(clazz),
        jbyteArray root_bytes, jbyteArray relative_bytes, jlong offset, jlong size, jboolean unlink_file, jint maximum) {
    char* root = graphics_path(env, root_bytes);
    if (!root) return NULL;
    char* relative = graphics_path(env, relative_bytes);
    if (!relative) { free(root); return NULL; }
    int directory = -1, file = -1;
    unsigned char* data = NULL;
    jbyteArray result = NULL;
    const char* error = "EACCES: media path";
    if (root[0] != '/' || relative[0] == '/' || offset < 0 || size < 0 || maximum <= 0 || maximum > 6 * 1024 * 1024) goto done;
    directory = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) goto done;
    char* save = NULL;
    for (char* part = strtok_r(root, "/", &save); part; part = strtok_r(NULL, "/", &save)) {
        if (!strcmp(part, ".") || !strcmp(part, "..")) goto done;
        int next = openat(directory, part, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) goto done;
        close(directory); directory = next;
    }
    char* name = relative;
    while (1) {
        char* slash = strchr(name, '/');
        if (!slash) break;
        *slash = 0;
        if (!name[0] || !strcmp(name, ".") || !strcmp(name, "..")) goto done;
        int next = openat(directory, name, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) goto done;
        close(directory); directory = next; name = slash + 1;
    }
    if (!name[0] || !strcmp(name, ".") || !strcmp(name, "..")) goto done;
    /* O_NONBLOCK prevents a FIFO from blocking before fstat rejects it. */
    file = openat(directory, name, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    if (file < 0) { error = "ENOENT: media file"; goto done; }
    struct stat metadata;
    if (fstat(file, &metadata) < 0 || !S_ISREG(metadata.st_mode)) { error = "EACCES: regular files only"; goto done; }
    if (offset > metadata.st_size) { error = "EINVAL: media offset"; goto done; }
    jlong length = size ? size : metadata.st_size - offset;
    if (length > maximum) { error = "E2BIG: media size"; goto done; }
    if (length > metadata.st_size - offset) { error = "EINVAL: media size"; goto done; }
    data = malloc(length ? (size_t) length : 1);
    if (!data) { error = "ENOSPC: media data"; goto done; }
    for (jlong at = 0; at < length;) {
        ssize_t n = pread(file, data + at, (size_t) (length - at), (off_t) (offset + at));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { error = "EIO: media read"; goto done; }
        at += n;
    }
    if (unlink_file) {
        struct stat current;
        if (fstatat(directory, name, &current, AT_SYMLINK_NOFOLLOW) == 0 &&
                current.st_dev == metadata.st_dev && current.st_ino == metadata.st_ino &&
                unlinkat(directory, name, 0) != 0) { error = "EIO: media unlink"; goto done; }
    }
    result = (*env)->NewByteArray(env, (jsize) length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, (jsize) length, (jbyte*) data);
    error = NULL;
done:
    if (file >= 0) close(file);
    if (directory >= 0) close(directory);
    free(data); free(root); free(relative);
    if (error) graphics_io_error(env, error);
    return result;
}
