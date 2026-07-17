#include <jni.h>
#include <android/log.h>
#include <string>
#include <stdlib.h>
#include <sys/wait.h>
#include <poll.h>
#include "root_tun_common.h"

#define LOG_TAG "RootTunNative"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string jstringToStd(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_v2ray_ang_root_RootTunNative_nativeOpenTun(
        JNIEnv *env, jclass,
        jstring jIfname,
        jstring jHelperPath,
        jstring jSuBinary) {
    std::string ifname = jstringToStd(env, jIfname);
    std::string helper = jstringToStd(env, jHelperPath);
    std::string su = jstringToStd(env, jSuBinary);
    if (ifname.empty()) ifname = "v2raytun0";
    if (su.empty()) su = "su";

    // Fast path: process already has CAP_NET_ADMIN / global root.
    int direct = root_tun_open_named(ifname.c_str());
    if (direct >= 0) {
        ALOGI("opened tun directly fd=%d name=%s", direct, ifname.c_str());
        return direct;
    }
    ALOGI("direct tun open failed (%d), trying su helper", direct);

    if (helper.empty()) {
        ALOGE("helper path empty");
        return -ENOENT;
    }

    char abs_name[64];
    snprintf(abs_name, sizeof(abs_name), "v2rayng-root-tun-%d", getpid());

    int server = root_tun_bind_abstract(abs_name);
    if (server < 0) {
        ALOGE("bind abstract failed: %d", server);
        return server;
    }

    // Ensure /dev/net/tun exists under root before helper runs.
    std::string prep = su +
        " -c 'if [ ! -e /dev/net/tun ]; then mkdir -p /dev/net; mknod /dev/net/tun c 10 200; chmod 666 /dev/net/tun; fi'";
    system(prep.c_str());

    // su -c 'exec "/path/libroot_tun_helper.so" "ifname" "abs"'
    std::string cmd = su + " -c 'exec \"" + helper + "\" \"" + ifname + "\" \"" + abs_name + "\"'";
    ALOGI("spawn: %s", cmd.c_str());
    pid_t pid = fork();
    if (pid == 0) {
        execlp("sh", "sh", "-c", cmd.c_str(), (char *) nullptr);
        _exit(127);
    }
    if (pid < 0) {
        close(server);
        return -errno;
    }

    struct pollfd pfd{server, POLLIN, 0};
    int pr = poll(&pfd, 1, 8000);
    if (pr <= 0) {
        ALOGE("accept timeout/poll=%d", pr);
        close(server);
        int st = 0;
        waitpid(pid, &st, WNOHANG);
        return -ETIMEDOUT;
    }
    int client = accept(server, nullptr, nullptr);
    close(server);
    if (client < 0) {
        int e = errno;
        waitpid(pid, nullptr, 0);
        return -e;
    }
    int fd = root_tun_recv_fd(client);
    close(client);
    int st = 0;
    waitpid(pid, &st, 0);
    if (fd < 0) {
        ALOGE("recv_fd failed: %d helper_status=%d", fd, st);
        return fd;
    }
    ALOGI("received tun fd=%d from helper status=%d", fd, st);
    return fd;
}

extern "C" JNIEXPORT void JNICALL
Java_com_v2ray_ang_root_RootTunNative_nativeCloseTun(JNIEnv *, jclass, jint fd) {
    if (fd >= 0) {
        close(fd);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_v2ray_ang_root_RootTunNative_nativeIsTunFd(JNIEnv *, jclass, jint fd) {
    if (fd < 0) return JNI_FALSE;
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    if (ioctl(fd, TUNGETIFF, &ifr) < 0) {
        return JNI_TRUE;
    }
    return JNI_TRUE;
}
