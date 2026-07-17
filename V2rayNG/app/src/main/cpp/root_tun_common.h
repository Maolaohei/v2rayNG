#pragma once
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <net/if.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stddef.h>

#ifndef IFF_TUN
#define IFF_TUN 0x0001
#endif
#ifndef IFF_NO_PI
#define IFF_NO_PI 0x1000
#endif
#ifndef TUNSETIFF
#define TUNSETIFF _IOW('T', 202, int)
#endif
#ifndef TUNGETIFF
#define TUNGETIFF _IOR('T', 210, int)
#endif

static inline int root_tun_open_named(const char *name) {
    int fd = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        // Some devices need the node created first.
        return -errno;
    }
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    if (name && name[0]) {
        snprintf(ifr.ifr_name, IFNAMSIZ, "%s", name);
    }
    if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
    return fd;
}

static inline int root_tun_send_fd(int sock, int fd) {
    struct msghdr msg;
    struct iovec iov;
    char buf[CMSG_SPACE(sizeof(int))];
    char dummy = 0;
    memset(&msg, 0, sizeof(msg));
    memset(buf, 0, sizeof(buf));
    iov.iov_base = &dummy;
    iov.iov_len = 1;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = buf;
    msg.msg_controllen = sizeof(buf);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;
    return sendmsg(sock, &msg, 0) < 0 ? -errno : 0;
}

static inline int root_tun_recv_fd(int sock) {
    struct msghdr msg;
    struct iovec iov;
    char buf[CMSG_SPACE(sizeof(int))];
    char dummy = 0;
    memset(&msg, 0, sizeof(msg));
    memset(buf, 0, sizeof(buf));
    iov.iov_base = &dummy;
    iov.iov_len = 1;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = buf;
    msg.msg_controllen = sizeof(buf);
    if (recvmsg(sock, &msg, 0) < 0) {
        return -errno;
    }
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
        return -EINVAL;
    }
    int fd = -1;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
    return fd;
}

static inline int root_tun_connect_abstract(const char *abs_name) {
    int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sock < 0) return -errno;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // abstract namespace: sun_path[0]=0
    size_t n = strlen(abs_name);
    if (n > sizeof(addr.sun_path) - 2) {
        close(sock);
        return -ENAMETOOLONG;
    }
    addr.sun_path[0] = 0;
    memcpy(addr.sun_path + 1, abs_name, n);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(sock, (struct sockaddr *)&addr, len) < 0) {
        int e = errno;
        close(sock);
        return -e;
    }
    return sock;
}

static inline int root_tun_bind_abstract(const char *abs_name) {
    int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sock < 0) return -errno;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t n = strlen(abs_name);
    if (n > sizeof(addr.sun_path) - 2) {
        close(sock);
        return -ENAMETOOLONG;
    }
    addr.sun_path[0] = 0;
    memcpy(addr.sun_path + 1, abs_name, n);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (bind(sock, (struct sockaddr *)&addr, len) < 0) {
        int e = errno;
        close(sock);
        return -e;
    }
    if (listen(sock, 1) < 0) {
        int e = errno;
        close(sock);
        return -e;
    }
    return sock;
}
