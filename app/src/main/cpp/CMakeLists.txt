# pty 桥：给 Mosh 子进程用的最小 JNI 库。
# 只有这一个 .c 文件，没有第三方依赖；openpty() 在 Bionic libc 里（minSdk 26）。
cmake_minimum_required(VERSION 3.22.1)

project(ptybridge)

add_library(ptybridge SHARED pty_bridge.c)

target_link_libraries(ptybridge log)
