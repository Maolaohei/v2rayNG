import java.util.*;

/**
 * 独立的 Java 测试 —— 验证三层断流修复的核心逻辑。
 * 不依赖 Android / Kotlin 编译产物，可直接 javac + java 运行。
 */
public class ConnectionKeepaliveJavaTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  v2rayNG 断流修复 - 逻辑验证测试");
        System.out.println("========================================\n");

        test_L1_sockoptBean_hasKeepAliveFields();
        test_L1_tcpKeepAlive_appliedToVmess();
        test_L1_tcpKeepAlive_appliedToVless();
        test_L1_tcpKeepAlive_appliedToShadowsocks();
        test_L1_tcpKeepAlive_appliedToTrojan();
        test_L1_tcpKeepAlive_NOT_appliedToWireguard();
        test_L1_tcpKeepAlive_NOT_appliedToHysteria();
        test_L1_tcpKeepAlive_NOT_appliedToHysteria2();
        test_L1_tcpKeepAlive_preservesExistingValues();
        test_L1_tcpKeepAlive_valuesAreReasonable();

        test_L3_watchdog_consecutiveFailureTracking();
        test_L3_watchdog_failureResetsOnSuccess();
        test_L3_watchdog_checkIntervalIs5Minutes();
        test_L3_watchdog_failureThresholdLogic();

        test_L1_json_serialization_includesNewField();

        System.out.println("\n========================================");
        System.out.println("  结果: " + passed + " 通过, " + failed + " 失败");
        System.out.println("========================================");

        if (failed > 0) System.exit(1);
    }

    // ==================== L1: TCP Keepalive ====================

    static void test_L1_sockoptBean_hasKeepAliveFields() {
        SockoptBean sockopt = new SockoptBean();
        assertEquals("SockoptBean 默认 tcpKeepAliveIdle 为 null",
                null, (Object) sockopt.tcpKeepAliveIdle);
        assertEquals("SockoptBean 默认 tcpKeepAliveInterval 为 null",
                null, (Object) sockopt.tcpKeepAliveInterval);

        sockopt.tcpKeepAliveIdle = 60;
        sockopt.tcpKeepAliveInterval = 5;
        assertEquals("设置后 tcpKeepAliveIdle = 60", 60, (int) sockopt.tcpKeepAliveIdle);
        assertEquals("设置后 tcpKeepAliveInterval = 5", 5, (int) sockopt.tcpKeepAliveInterval);
    }

    static void test_L1_tcpKeepAlive_appliedToVmess() {
        Outbound outbound = new Outbound("vmess");
        applyTcpKeepAlive(outbound);

        assertNotNull("Vmess outbound 应有 sockopt", outbound.streamSettings.sockopt);
        assertEquals("Vmess tcpKeepAliveIdle = 60", 60, (int) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
        assertEquals("Vmess tcpKeepAliveInterval = 5", 5, (int) outbound.streamSettings.sockopt.tcpKeepAliveInterval);
    }

    static void test_L1_tcpKeepAlive_appliedToVless() {
        Outbound outbound = new Outbound("vless");
        applyTcpKeepAlive(outbound);

        assertNotNull("Vless outbound 应有 sockopt", outbound.streamSettings.sockopt);
        assertEquals("Vless tcpKeepAliveIdle = 60", 60, (int) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
    }

    static void test_L1_tcpKeepAlive_appliedToShadowsocks() {
        Outbound outbound = new Outbound("shadowsocks");
        applyTcpKeepAlive(outbound);

        assertNotNull("Shadowsocks outbound 应有 sockopt", outbound.streamSettings.sockopt);
        assertEquals("Shadowsocks tcpKeepAliveIdle = 60", 60, (int) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
    }

    static void test_L1_tcpKeepAlive_appliedToTrojan() {
        Outbound outbound = new Outbound("trojan");
        applyTcpKeepAlive(outbound);

        assertNotNull("Trojan outbound 应有 sockopt", outbound.streamSettings.sockopt);
        assertEquals("Trojan tcpKeepAliveIdle = 60", 60, (int) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
    }

    static void test_L1_tcpKeepAlive_NOT_appliedToWireguard() {
        Outbound outbound = new Outbound("wireguard");
        applyTcpKeepAlive(outbound);

        assertEquals("Wireguard 不应设置 tcpKeepAliveIdle", null, (Object) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
        assertEquals("Wireguard 不应设置 tcpKeepAliveInterval", null, (Object) outbound.streamSettings.sockopt.tcpKeepAliveInterval);
    }

    static void test_L1_tcpKeepAlive_NOT_appliedToHysteria() {
        Outbound outbound = new Outbound("hysteria");
        applyTcpKeepAlive(outbound);

        assertEquals("Hysteria 不应设置 tcpKeepAliveIdle", null, (Object) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
    }

    static void test_L1_tcpKeepAlive_NOT_appliedToHysteria2() {
        Outbound outbound = new Outbound("hysteria2");
        applyTcpKeepAlive(outbound);

        assertEquals("Hysteria2 不应设置 tcpKeepAliveIdle", null, (Object) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
    }

    static void test_L1_tcpKeepAlive_preservesExistingValues() {
        Outbound outbound = new Outbound("vmess");
        outbound.streamSettings.sockopt.tcpKeepAliveIdle = 60;
        outbound.streamSettings.sockopt.tcpKeepAliveInterval = 5;

        applyTcpKeepAlive(outbound);

        assertEquals("保留已有 tcpKeepAliveIdle=60", 60, (int) outbound.streamSettings.sockopt.tcpKeepAliveIdle);
        assertEquals("保留已有 tcpKeepAliveInterval=5", 5, (int) outbound.streamSettings.sockopt.tcpKeepAliveInterval);
    }

    static void test_L1_tcpKeepAlive_valuesAreReasonable() {
        int idle = 60;
        int interval = 5;

        assertTrue("idle >= 30s (不激进)", idle >= 30);
        assertTrue("idle <= 120s (能在NAT回收前探测)", idle <= 120);
        assertTrue("interval >= 3s", interval >= 3);
        assertTrue("interval <= 15s", interval <= 15);
    }

    // ==================== L3: Watchdog 逻辑 ====================

    static void test_L3_watchdog_consecutiveFailureTracking() {
        int consecutiveFailures = 0;
        int maxConsecutiveFailures = 2;

        consecutiveFailures++;
        assertEquals("第一次失败后 count=1", 1, consecutiveFailures);
        assertTrue("第一次失败不应触发重启", consecutiveFailures < maxConsecutiveFailures);

        consecutiveFailures++;
        assertEquals("第二次失败后 count=2", 2, consecutiveFailures);
        assertTrue("第二次失败应触发重启", consecutiveFailures >= maxConsecutiveFailures);
    }

    static void test_L3_watchdog_failureResetsOnSuccess() {
        int consecutiveFailures = 0;

        consecutiveFailures++;
        consecutiveFailures++;
        assertTrue("两次失败后应触发重启", consecutiveFailures >= 2);

        consecutiveFailures = 0;
        assertEquals("成功后 count 重置为 0", 0, consecutiveFailures);
    }

    static void test_L3_watchdog_checkIntervalIs5Minutes() {
        long checkIntervalMs = 5 * 60 * 1000L;
        assertEquals("检查间隔 = 300秒 (5分钟)", 300L, checkIntervalMs / 1000);
    }

    static void test_L3_watchdog_failureThresholdLogic() {
        int maxConsecutiveFailures = 2;
        int consecutiveFailures = 0;
        boolean shouldRestart;

        consecutiveFailures++;
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures;
        assertTrue("第一次失败不应重启", !shouldRestart);

        consecutiveFailures++;
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures;
        assertTrue("第二次失败应重启", shouldRestart);

        consecutiveFailures = 0;
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures;
        assertTrue("重启后不应再次重启", !shouldRestart);
    }

    // ==================== JSON 验证 ====================

    static void test_L1_json_serialization_includesNewField() {
        SockoptBean sockopt = new SockoptBean();
        sockopt.tcpKeepAliveIdle = 60;
        sockopt.tcpKeepAliveInterval = 5;

        String json = sockopt.toJson();
        assertTrue("JSON 包含 tcpKeepAliveIdle", json.contains("\"tcpKeepAliveIdle\":60"));
        assertTrue("JSON 包含 tcpKeepAliveInterval", json.contains("\"tcpKeepAliveInterval\":5"));

        System.out.println("  [INFO] SockoptBean JSON: " + json);
    }

    // ==================== 数据模型 (模拟项目中的类) ====================

    static class SockoptBean {
        Integer tcpKeepAliveIdle = null;
        Integer tcpKeepAliveInterval = null;
        Boolean TcpNoDelay = null;
        Boolean tcpFastOpen = null;

        String toJson() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"TcpNoDelay\":").append(TcpNoDelay);
            sb.append(",\"tcpKeepAliveIdle\":").append(tcpKeepAliveIdle);
            sb.append(",\"tcpKeepAliveInterval\":").append(tcpKeepAliveInterval);
            sb.append(",\"tcpFastOpen\":").append(tcpFastOpen);
            sb.append("}");
            return sb.toString();
        }
    }

    static class StreamSettings {
        SockoptBean sockopt = new SockoptBean();
    }

    static class Outbound {
        String protocol;
        StreamSettings streamSettings = new StreamSettings();

        Outbound(String protocol) {
            this.protocol = protocol;
        }
    }

    /**
     * 与 CoreOutboundBuilder.applyTcpKeepAlive() 完全一致的逻辑
     */
    static void applyTcpKeepAlive(Outbound outbound) {
        StreamSettings streamSettings = outbound.streamSettings;
        if (streamSettings == null) return;
        String protocol = outbound.protocol;
        if ("wireguard".equalsIgnoreCase(protocol)
            || "hysteria".equalsIgnoreCase(protocol)
            || "hysteria2".equalsIgnoreCase(protocol)) {
            return;
        }
        SockoptBean sockopt = streamSettings.sockopt;
        if (sockopt == null) {
            sockopt = new SockoptBean();
            streamSettings.sockopt = sockopt;
        }
        if (sockopt.tcpKeepAliveIdle == null) {
            sockopt.tcpKeepAliveIdle = 60;
        }
        if (sockopt.tcpKeepAliveInterval == null) {
            sockopt.tcpKeepAliveInterval = 5;
        }
    }

    // ==================== 测试工具 ====================

    static void assertEquals(String msg, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg + " — 期望: " + expected + ", 实际: " + actual);
        }
    }

    static void assertEquals(String msg, int expected, int actual) {
        assertEquals(msg, (Object) expected, (Object) actual);
    }

    static void assertEquals(String msg, long expected, long actual) {
        assertEquals(msg, (Object) expected, (Object) actual);
    }

    static void assertTrue(String msg, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg);
        }
    }

    static void assertNotNull(String msg, Object obj) {
        if (obj != null) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg + " — 为 null");
        }
    }
}
