package com.onessh.app.ssh;

/**
 * pty 桥的 Java 封装：分配 pty、起子进程、读写 master 端、改窗口大小。
 *
 * 只给 MoshSession 用。native 库由 Gradle 的 externalNativeBuild（CMake）
 * 编出来，名字是 libptybridge.so。
 */
public final class PtyBridge {

    public static final int SIGTERM = 15;
    public static final int SIGKILL = 9;

    static {
        System.loadLibrary("ptybridge");
    }

    private PtyBridge() {
    }

    /** nativeStart 的返回值：master fd + 子进程 pid。 */
    public static final class Started {
        public final int fd;
        public final int pid;

        Started(int fd, int pid) {
            this.fd = fd;
            this.pid = pid;
        }
    }

    /**
     * 在一个已设好 winsize 的 pty 上启动子进程。
     *
     * @param binary 二进制全路径（如 nativeLibraryDir 下的 libmoshclientbin.so）
     * @param argv   参数（不含 argv[0]，如 {"10.0.0.1", "60001"}）
     * @param env    "KEY=VALUE" 形式的附加环境变量，会覆盖继承来的同名项
     * @param cols   启动时的列数（mosh-client 启动时读 winsize，0 会崩）
     * @param rows   启动时的行数
     * @return 成功返回 Started，失败返回 null
     */
    public static Started start(String binary, String[] argv, String[] env,
                                int cols, int rows) {
        int[] r = nativeStart(binary, argv, env, cols, rows);
        if (r == null || r.length < 2) return null;
        return new Started(r[0], r[1]);
    }

    /**
     * 读 master 端。返回 &gt;0 是字节数，0 是 250ms 内没数据，-1 是 EOF 或出错。
     */
    public static int read(int fd, byte[] buf, int off, int len) {
        return nativeRead(fd, buf, off, len);
    }

    /** 写 master 端，返回实际写出的字节数，-1 表示出错。 */
    public static int write(int fd, byte[] buf, int off, int len) {
        return nativeWrite(fd, buf, off, len);
    }

    /** 改窗口大小：写 master 侧，内核同步到 slave 并给子进程发 SIGWINCH。 */
    public static boolean setWinsize(int fd, int cols, int rows) {
        return nativeSetWinsize(fd, cols, rows) == 0;
    }

    public static void close(int fd) {
        nativeClose(fd);
    }

    public static void kill(int pid, int sig) {
        nativeKill(pid, sig);
    }

    /**
     * 非阻塞查子进程：-1 还在跑，-2 查不到（已收过尸等），&gt;=0 是退出码
     * （被信号杀死返回 128+信号号）。
     */
    public static int waitPid(int pid) {
        return nativeWaitPid(pid);
    }

    private static native int[] nativeStart(String binary, String[] argv,
                                            String[] env, int cols, int rows);

    private static native int nativeSetWinsize(int fd, int cols, int rows);

    private static native int nativeRead(int fd, byte[] buf, int off, int len);

    private static native int nativeWrite(int fd, byte[] buf, int off, int len);

    private static native int nativeClose(int fd);

    private static native void nativeKill(int pid, int sig);

    private static native int nativeWaitPid(int pid);
}
