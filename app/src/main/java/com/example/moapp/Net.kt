package com.example.moapp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

object Net {
    // 에뮬레이터: "10.0.2.2"
    // 실기기 (같은 Wi-Fi): Windows PC 의 LAN IP (예: "192.168.0.31")
    // 실기기 + USB ADB Reverse: "127.0.0.1"  ← 현재 사용 중
    //   PowerShell: adb reverse tcp:9090 tcp:9090
    const val DAEMON_HOST = "127.0.0.1"
    const val DAEMON_PORT = 9090

    private const val TIMEOUT_MS = 3000

    fun call(line: String): String {
        Socket().use { s ->
            s.connect(java.net.InetSocketAddress(DAEMON_HOST, DAEMON_PORT), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            val out = PrintWriter(s.getOutputStream(), true)
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            out.println(line)
            return input.readLine() ?: ""
        }
    }
}
