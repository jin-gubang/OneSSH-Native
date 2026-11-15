/*
 * pty_bridge.c — 最小的 pty + 子进程桥，供 Mosh 会话用。
 *
 * 做法（SSHerpa / mosh 官方 wrapper 的同款模式）：
 *   1. openpty() 分配一对 pty，建的时候就按真实行列数设好 winsize
 *      （mosh-client 启动时读 winsize，0x0 会断言崩溃）；
 *   2. fork + setsid + TIOCSCTTY，让子进程拿到 slave 当控制终端，
 *      再把 slave dup 到 0/1/2 后 execve() 跑 mosh-client；
 *   3. Java 侧只拿 master fd：读到的字节直接喂 TerminalBuffer，
 *      用户输入直接写回 master。
 *
 * 只有这一个会话在跑：同一时间最多一个 TerminalActivity 用它。
 */

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <pty.h>

extern char **environ;

/* 把 "KEY=VALUE" 形式的覆盖项合并进当前环境，返回新 envp（调用方 free）。 */
static char **merge_env(const char **overrides, int count) {
    int n = 0;
    while (environ[n] != NULL) n++;

    /* 最坏情况：全部是新增项 */
    char **envp = (char **)malloc(sizeof(char *) * (n + count + 1));
    if (envp == NULL) return NULL;
    for (int i = 0; i < n; i++) envp[i] = environ[i];
    int m = n;

    for (int i = 0; i < count; i++) {
        const char *kv = overrides[i];
        const char *eq = strchr(kv, '=');
        size_t klen = eq != NULL ? (size_t)(eq - kv) : strlen(kv);
        int replaced = 0;
        for (int j = 0; j < m; j++) {
            if (strncmp(envp[j], kv, klen) == 0 && envp[j][klen] == '=') {
                envp[j] = (char *)kv;
                replaced = 1;
                break;
            }
        }
        if (!replaced) envp[m++] = (char *)kv;
    }
    envp[m] = NULL;
    return envp;
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeStart
 * signature: (Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;II)[I
 *
 * 成功返回 int[]{masterFd, pid}，失败返回 null。
 */
JNIEXPORT jintArray JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeStart(JNIEnv *env, jclass clazz,
                                              jstring binary, jobjectArray argv,
                                              jobjectArray envOverrides,
                                              jint cols, jint rows) {
    (void)clazz;
    if (cols <= 0) cols = 80;
    if (rows <= 0) rows = 24;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ws.ws_xpixel = (unsigned short)(cols * 8);
    ws.ws_ypixel = (unsigned short)(rows * 16);

    int master = -1, slave = -1;
    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) return NULL;
    /* 再显式设一次，确保 slave 侧的 winsize 一定是真实值 */
    ioctl(master, TIOCSWINSZ, &ws);

    /* ---- 拼 argv：{binary, args..., NULL} ---- */
    jsize argc = argv != NULL ? (*env)->GetArrayLength(env, argv) : 0;
    char **c_argv = (char **)malloc(sizeof(char *) * (argc + 2));
    if (c_argv == NULL) {
        close(master);
        close(slave);
        return NULL;
    }
    const char *c_binary = (*env)->GetStringUTFChars(env, binary, NULL);
    if (c_binary == NULL) {
        free(c_argv);
        close(master);
        close(slave);
        return NULL;
    }
    c_argv[0] = (char *)c_binary;
    jstring *arg_strs = NULL;
    if (argc > 0) {
        arg_strs = (jstring *)malloc(sizeof(jstring) * argc);
        if (arg_strs == NULL) {
            (*env)->ReleaseStringUTFChars(env, binary, c_binary);
            free(c_argv);
            close(master);
            close(slave);
            return NULL;
        }
        for (jsize i = 0; i < argc; i++) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, argv, i);
            arg_strs[i] = s;
            const char *c = s != NULL
                    ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
            c_argv[i + 1] = (char *)c;
        }
    }
    c_argv[argc + 1] = NULL;

    /* ---- 拼 envp（在 fork 之前做，子进程里不再 malloc） ---- */
    jsize envc = envOverrides != NULL ? (*env)->GetArrayLength(env, envOverrides) : 0;
    const char **c_env = NULL;
    jstring *env_strs = NULL;
    char **envp = NULL;
    if (envc > 0) {
        c_env = (const char **)malloc(sizeof(char *) * envc);
        env_strs = (jstring *)malloc(sizeof(jstring) * envc);
        if (c_env == NULL || env_strs == NULL) goto fail_args;
        for (jsize i = 0; i < envc; i++) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, envOverrides, i);
            env_strs[i] = s;
            c_env[i] = s != NULL ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        }
        envp = merge_env(c_env, envc);
        if (envp == NULL) goto fail_args;
    }

    pid_t pid = fork();
    if (pid < 0) {
        goto fail_fork;
    }

    if (pid == 0) {
        /* ---- 子进程：拿 slave 当控制终端，exec mosh-client ---- */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        close(master);
        close(slave);
        /* 关掉从 App 继承的其他 fd（JSch 的 socket 等），别让子进程攥着 */
        for (int fd = 3; fd < 1024; fd++) close(fd);
        if (envp != NULL) {
            execve(c_binary, c_argv, envp);
        } else {
            execve(c_binary, c_argv, environ);
        }
        _exit(127);
    }

    /* ---- 父进程 ---- */
    close(slave);
    if (envp != NULL) free(envp);

    jintArray out = (*env)->NewIntArray(env, 2);
    if (out != NULL) {
        jint vals[2] = {master, (jint)pid};
        (*env)->SetIntArrayRegion(env, out, 0, 2, vals);
    } else {
        close(master);
    }

    /* 释放 JNI 字符串 */
    (*env)->ReleaseStringUTFChars(env, binary, c_binary);
    if (arg_strs != NULL) {
        for (jsize i = 0; i < argc; i++) {
            if (c_argv[i + 1] != NULL)
                (*env)->ReleaseStringUTFChars(env, arg_strs[i], c_argv[i + 1]);
            if (arg_strs[i] != NULL)
                (*env)->DeleteLocalRef(env, arg_strs[i]);
        }
        free(arg_strs);
    }
    if (env_strs != NULL) {
        for (jsize i = 0; i < envc; i++) {
            if (c_env[i] != NULL)
                (*env)->ReleaseStringUTFChars(env, env_strs[i], c_env[i]);
            if (env_strs[i] != NULL)
                (*env)->DeleteLocalRef(env, env_strs[i]);
        }
        free(env_strs);
        free(c_env);
    }
    free(c_argv);
    return out;

fail_fork:
    if (envp != NULL) free(envp);
fail_args:
    if (env_strs != NULL) {
        for (jsize i = 0; i < envc; i++) {
            if (c_env != NULL && c_env[i] != NULL)
                (*env)->ReleaseStringUTFChars(env, env_strs[i], c_env[i]);
            if (env_strs[i] != NULL)
                (*env)->DeleteLocalRef(env, env_strs[i]);
        }
        free(env_strs);
        free((void *)c_env);
    }
    (*env)->ReleaseStringUTFChars(env, binary, c_binary);
    if (arg_strs != NULL) {
        for (jsize i = 0; i < argc; i++) {
            if (c_argv[i + 1] != NULL)
                (*env)->ReleaseStringUTFChars(env, arg_strs[i], c_argv[i + 1]);
            if (arg_strs[i] != NULL)
                (*env)->DeleteLocalRef(env, arg_strs[i]);
        }
        free(arg_strs);
    }
    free(c_argv);
    close(master);
    close(slave);
    return NULL;
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeSetWinsize
 * signature: (III)I
 */
JNIEXPORT jint JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeSetWinsize(JNIEnv *env, jclass clazz,
                                                   jint fd, jint cols, jint rows) {
    (void)env;
    (void)clazz;
    if (fd < 0 || cols <= 0 || rows <= 0) return -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ws.ws_xpixel = (unsigned short)(cols * 8);
    ws.ws_ypixel = (unsigned short)(rows * 16);
    /* 写 master 侧：内核会把它同步到 slave，并给前台进程组发 SIGWINCH */
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeRead
 * signature: (I[BII)I
 *
 * 返回：>0 读到的字节数；0 超时（250ms 内无数据）；-1 EOF 或出错。
 */
JNIEXPORT jint JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeRead(JNIEnv *env, jclass clazz,
                                            jint fd, jbyteArray buf,
                                            jint off, jint len) {
    (void)clazz;
    if (fd < 0 || buf == NULL || len <= 0) return -1;
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int r = poll(&pfd, 1, 250);
    if (r == 0) return 0;
    if (r < 0) return -1;
    if ((pfd.revents & (POLLIN | POLLPRI)) == 0) return -1;

    jbyte *cbuf = (*env)->GetByteArrayElements(env, buf, NULL);
    if (cbuf == NULL) return -1;
    ssize_t n = read(fd, cbuf + off, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, cbuf, 0);
    if (n < 0) return -1;
    return (jint)n;
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeWrite
 * signature: (I[BII)I
 */
JNIEXPORT jint JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeWrite(JNIEnv *env, jclass clazz,
                                             jint fd, jbyteArray buf,
                                             jint off, jint len) {
    (void)clazz;
    if (fd < 0 || buf == NULL || len <= 0) return -1;
    jbyte *cbuf = (*env)->GetByteArrayElements(env, buf, NULL);
    if (cbuf == NULL) return -1;
    ssize_t total = 0;
    while (total < len) {
        ssize_t n = write(fd, cbuf + off + total, (size_t)(len - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        total += n;
    }
    (*env)->ReleaseByteArrayElements(env, buf, cbuf, JNI_ABORT);
    return total > 0 ? (jint)total : -1;
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeClose
 * signature: (I)I
 */
JNIEXPORT jint JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (fd < 0) return -1;
    return close(fd) == 0 ? 0 : -1;
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeKill
 * signature: (II)V
 */
JNIEXPORT void JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeKill(JNIEnv *env, jclass clazz,
                                            jint pid, jint sig) {
    (void)env;
    (void)clazz;
    if (pid > 0) kill((pid_t)pid, sig);
}

/*
 * class:     com_onessh_app_ssh_PtyBridge
 * method:    nativeWaitPid
 * signature: (I)I
 *
 * 返回：-1 还在跑；-2 出错（已收过尸等）；>=0 子进程退出码
 * （被信号杀死返回 128+信号号）。
 */
JNIEXPORT jint JNICALL
Java_com_onessh_app_ssh_PtyBridge_nativeWaitPid(JNIEnv *env, jclass clazz, jint pid) {
    (void)env;
    (void)clazz;
    if (pid <= 0) return -2;
    int status = 0;
    pid_t r = waitpid((pid_t)pid, &status, WNOHANG);
    if (r == 0) return -1;
    if (r < 0) return -2;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -2;
}
