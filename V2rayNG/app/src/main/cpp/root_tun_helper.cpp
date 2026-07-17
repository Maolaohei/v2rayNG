#include "root_tun_common.h"
#include <stdlib.h>

// Packaged as libroot_tun_helper.so; invoked under su:
//   libroot_tun_helper.so <ifname> <abstract-socket-name>
int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: root_tun_helper <ifname> <abstract-sock>\n");
        return 2;
    }
    const char *ifname = argv[1];
    const char *abs_name = argv[2];

    int tun = root_tun_open_named(ifname);
    if (tun < 0) {
        fprintf(stderr, "open tun failed: %d\n", tun);
        return 3;
    }
    int sock = root_tun_connect_abstract(abs_name);
    if (sock < 0) {
        fprintf(stderr, "connect abstract failed: %d\n", sock);
        close(tun);
        return 4;
    }
    int rc = root_tun_send_fd(sock, tun);
    close(tun);
    close(sock);
    if (rc < 0) {
        fprintf(stderr, "send_fd failed: %d\n", rc);
        return 5;
    }
    return 0;
}
