/*
 * Uni Tool native: PTY session + USBDEVFS bulk/control on Host-API FDs.
 * The Java side keeps UsbDeviceConnection alive; we only use its usbfs FD.
 */
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <poll.h>

#define LOG_TAG "UniToolNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---- USBDEVFS (linux/usbdevice_fs.h subset) ---- */
struct usbdevfs_bulktransfer {
    unsigned int ep;
    unsigned int len;
    unsigned int timeout; /* ms */
    void *data;
};

struct usbdevfs_ctrltransfer {
    uint8_t bRequestType;
    uint8_t bRequest;
    uint16_t wValue;
    uint16_t wIndex;
    uint16_t wLength;
    uint32_t timeout;
    void *data;
};

struct usbdevfs_setinterface {
    unsigned int interface;
    unsigned int altsetting;
};

#define USBDEVFS_BULK         _IOWR('U', 2, struct usbdevfs_bulktransfer)
#define USBDEVFS_CONTROL      _IOWR('U', 0, struct usbdevfs_ctrltransfer)
#define USBDEVFS_CLAIMINTERFACE    _IOR('U', 15, unsigned int)
#define USBDEVFS_RELEASEINTERFACE  _IOR('U', 16, unsigned int)
#define USBDEVFS_SETINTERFACE      _IOR('U', 4, struct usbdevfs_setinterface)
#define USBDEVFS_CLEAR_HALT        _IOR('U', 21, unsigned int)
#define USBDEVFS_RESET             _IO('U', 20)

/* ---- PTY helpers ---- */
static int open_pty_pair(int *master_fd, int *slave_fd) {
    int m = posix_openpt(O_RDWR | O_CLOEXEC | O_NOCTTY);
    if (m < 0) {
        LOGE("posix_openpt: %s", strerror(errno));
        return -1;
    }
    if (grantpt(m) != 0 || unlockpt(m) != 0) {
        LOGE("grant/unlock: %s", strerror(errno));
        close(m);
        return -1;
    }
    char *name = ptsname(m);
    if (!name) {
        close(m);
        return -1;
    }
    int s = open(name, O_RDWR | O_NOCTTY);
    if (s < 0) {
        LOGE("open slave %s: %s", name, strerror(errno));
        close(m);
        return -1;
    }
    *master_fd = m;
    *slave_fd = s;
    return 0;
}

/*
 * Start a process attached to a new PTY.
 * Returns master FD (>=0) or -1. Child PID written to *out_pid.
 * argv: NULL-terminated, envp: NULL-terminated "KEY=VAL".
 */
static int pty_spawn(char *const argv[], char *const envp[], const char *cwd, pid_t *out_pid) {
    int master = -1, slave = -1;
    if (open_pty_pair(&master, &slave) != 0) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(master);
        close(slave);
        return -1;
    }
    if (pid == 0) {
        /* child */
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > 2) close(slave);
        if (cwd && cwd[0]) {
            if (chdir(cwd) != 0) { /* ignore */ }
        }
        if (envp) {
            execve(argv[0], argv, envp);
        } else {
            execv(argv[0], argv);
        }
        _exit(127);
    }
    close(slave);
    *out_pid = pid;
    /* non-blocking master for Java poll */
    int flags = fcntl(master, F_GETFL, 0);
    fcntl(master, F_SETFL, flags | O_NONBLOCK);
    LOGI("pty master=%d pid=%d cmd=%s", master, (int)pid, argv[0]);
    return master;
}

/* ---- JNI: USB ---- */

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeUsbBulk(
        JNIEnv *env, jclass clazz,
        jint fd, jint endpoint, jbyteArray data, jint length, jint timeoutMs) {
    if (fd < 0 || data == NULL || length < 0) return -EINVAL;
    jsize cap = (*env)->GetArrayLength(env, data);
    if (length > cap) length = cap;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return -ENOMEM;

    struct usbdevfs_bulktransfer bt;
    memset(&bt, 0, sizeof(bt));
    bt.ep = (unsigned int)endpoint;
    bt.len = (unsigned int)length;
    bt.timeout = (unsigned int)timeoutMs;
    bt.data = buf;

    int rc = ioctl(fd, USBDEVFS_BULK, &bt);
    int saved = errno;
    if (rc < 0) {
        LOGE("USBDEVFS_BULK fd=%d ep=0x%x len=%d: %s", fd, endpoint, length, strerror(saved));
        (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
        return -saved;
    }
    /* rc is often 0 on success; actual transferred may equal length */
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);
    return length; /* usbfs typically doesn't return short count via ioctl rc */
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeUsbControl(
        JNIEnv *env, jclass clazz,
        jint fd, jint requestType, jint request, jint value, jint index,
        jbyteArray data, jint length, jint timeoutMs) {
    if (fd < 0) return -EINVAL;
    jbyte *buf = NULL;
    if (data != NULL && length > 0) {
        buf = (*env)->GetByteArrayElements(env, data, NULL);
        if (!buf) return -ENOMEM;
    }
    struct usbdevfs_ctrltransfer ct;
    memset(&ct, 0, sizeof(ct));
    ct.bRequestType = (uint8_t)requestType;
    ct.bRequest = (uint8_t)request;
    ct.wValue = (uint16_t)value;
    ct.wIndex = (uint16_t)index;
    ct.wLength = (uint16_t)length;
    ct.timeout = (uint32_t)timeoutMs;
    ct.data = buf;

    int rc = ioctl(fd, USBDEVFS_CONTROL, &ct);
    int saved = errno;
    if (buf) {
        (*env)->ReleaseByteArrayElements(env, data, buf, rc < 0 ? JNI_ABORT : 0);
    }
    if (rc < 0) {
        LOGE("USBDEVFS_CONTROL: %s", strerror(saved));
        return -saved;
    }
    return length;
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeUsbClaim(
        JNIEnv *env, jclass clazz, jint fd, jint interfaceNum) {
    unsigned int iface = (unsigned int)interfaceNum;
    int rc = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface);
    if (rc < 0) {
        LOGE("CLAIMINTERFACE %d: %s", interfaceNum, strerror(errno));
        return -errno;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeUsbRelease(
        JNIEnv *env, jclass clazz, jint fd, jint interfaceNum) {
    unsigned int iface = (unsigned int)interfaceNum;
    int rc = ioctl(fd, USBDEVFS_RELEASEINTERFACE, &iface);
    return rc < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeUsbClearHalt(
        JNIEnv *env, jclass clazz, jint fd, jint endpoint) {
    unsigned int ep = (unsigned int)endpoint;
    int rc = ioctl(fd, USBDEVFS_CLEAR_HALT, &ep);
    return rc < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeUsbReset(
        JNIEnv *env, jclass clazz, jint fd) {
    int rc = ioctl(fd, USBDEVFS_RESET, NULL);
    return rc < 0 ? -errno : 0;
}

/** Self-test: open /dev/null style sanity + ioctl constants. */
JNIEXPORT jstring JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeSelfTest(
        JNIEnv *env, jclass clazz) {
    char msg[512];
    int master = -1, slave = -1;
    int pty_ok = open_pty_pair(&master, &slave);
    if (pty_ok == 0) {
        close(master);
        close(slave);
    }
    snprintf(msg, sizeof(msg),
             "unitool-native ok pty=%s bulk_ioctl=0x%lx claim=0x%lx",
             pty_ok == 0 ? "yes" : "no",
             (unsigned long)USBDEVFS_BULK,
             (unsigned long)USBDEVFS_CLAIMINTERFACE);
    return (*env)->NewStringUTF(env, msg);
}

/* ---- JNI: PTY ---- */

JNIEXPORT jintArray JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativePtyStart(
        JNIEnv *env, jclass clazz,
        jobjectArray jargv, jobjectArray jenv, jstring jcwd) {
    int argc = jargv ? (*env)->GetArrayLength(env, jargv) : 0;
    if (argc < 1) return NULL;

    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargv, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
    }

    int envc = jenv ? (*env)->GetArrayLength(env, jenv) : 0;
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jenv, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
    }

    const char *cwd = NULL;
    if (jcwd) cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);

    pid_t pid = -1;
    int master = pty_spawn(argv, envp, cwd, &pid);

    if (jcwd && cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < envc; i++) free(envp[i]);
    free(envp);

    if (master < 0) return NULL;
    jintArray out = (*env)->NewIntArray(env, 2);
    jint vals[2] = { master, (jint)pid };
    (*env)->SetIntArrayRegion(env, out, 0, 2, vals);
    return out;
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativePtyRead(
        JNIEnv *env, jclass clazz, jint masterFd, jbyteArray buf) {
    if (masterFd < 0 || buf == NULL) return -EINVAL;
    jsize cap = (*env)->GetArrayLength(env, buf);
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t n = read(masterFd, p, (size_t)cap);
    int saved = errno;
    if (n < 0) {
        (*env)->ReleaseByteArrayElements(env, buf, p, JNI_ABORT);
        if (saved == EAGAIN || saved == EWOULDBLOCK) return 0;
        return -saved;
    }
    (*env)->ReleaseByteArrayElements(env, buf, p, 0);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativePtyWrite(
        JNIEnv *env, jclass clazz, jint masterFd, jbyteArray buf, jint length) {
    if (masterFd < 0 || buf == NULL) return -EINVAL;
    jsize cap = (*env)->GetArrayLength(env, buf);
    if (length > cap) length = cap;
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t n = write(masterFd, p, (size_t)length);
    int saved = errno;
    (*env)->ReleaseByteArrayElements(env, buf, p, JNI_ABORT);
    if (n < 0) return -saved;
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativePtySetSize(
        JNIEnv *env, jclass clazz, jint masterFd, jint rows, jint cols) {
    if (masterFd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(masterFd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativePtyClose(
        JNIEnv *env, jclass clazz, jint masterFd, jint pid) {
    if (masterFd >= 0) close(masterFd);
    if (pid > 0) {
        kill(pid, SIGHUP);
        int status;
        waitpid(pid, &status, WNOHANG);
    }
}

/** Dup an FD so shell child can inherit USB FD (keep Java connection open). */
JNIEXPORT jint JNICALL
Java_ahk_unitool_gsm_native_NativeBridge_nativeDupFd(
        JNIEnv *env, jclass clazz, jint fd) {
    if (fd < 0) return -EINVAL;
    int n = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    if (n < 0) n = dup(fd);
    return n < 0 ? -errno : n;
}
