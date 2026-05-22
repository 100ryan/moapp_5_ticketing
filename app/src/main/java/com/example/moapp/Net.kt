package com.example.moapp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

object Net {
    // 에뮬레이터: "10.0.2.2"
    // 실기기 (같은 Wi-Fi): Windows PC 의 LAN IP (예: "192.168.0.83")
    //   ※ 실기기 사용 시 Windows 에서 portproxy + 방화벽 설정도 필요
    const val DAEMON_HOST = "192.168.0.83"
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
