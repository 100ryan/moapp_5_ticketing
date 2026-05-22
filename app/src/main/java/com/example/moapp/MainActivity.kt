package com.example.moapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.moapp.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MAX_SEATS = 200
        const val COLOR_EMPTY    = "#2196F3"
        const val COLOR_TAKEN    = "#DDDDDD"
        const val COLOR_SELECTED = "#E91E63"
        const val SEAT_POLL_INTERVAL_MS = 1000L
    }

    private lateinit var binding: ActivityMainBinding

    // 슬라이딩 인증용
    private val touchPathX = mutableListOf<Float>()
    private val touchPathY = mutableListOf<Float>()
    private val touchPathT = mutableListOf<Long>()
    private var touchStartMs: Long = 0L

    // 좌석 상태
    private val seatViews = arrayOfNulls<View>(MAX_SEATS)
    private var seatStatus = CharArray(MAX_SEATS) { '0' }
    private var selectedSeatIndex: Int = -1

    // 인증 상태
    private val userId: String = "demo-user"
    private lateinit var deviceId: String
    private var nonce: String = ""
    private var token: String = ""
    private var currentConcert: String = ""

    private var queuePollJob: Job? = null
    private var seatPollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

        setupUI()

        // 1. 콘서트 선택 -> HELLO 로 nonce 받고 슬라이딩 화면
        val concertClickListener = View.OnClickListener { view ->
            currentConcert = when (view.id) {
                R.id.card_post   -> "포스트말론 내한공연"
                R.id.card_justin -> "저스틴 비버 월드투어"
                R.id.card_weeknd -> "위켄드 아시아투어"
                R.id.card_hero   -> "임영웅 전국투어(대구)"
                else -> ""
            }
            binding.tvSelectedConcert.text = "[$currentConcert] 예매 대기열 진입"
            requestNonceAndEnterSliding()
        }
        binding.cardPost.setOnClickListener(concertClickListener)
        binding.cardJustin.setOnClickListener(concertClickListener)
        binding.cardWeeknd.setOnClickListener(concertClickListener)
        binding.cardHero.setOnClickListener(concertClickListener)

        setupSlidingListener()

        // 3. 구역 선택 -> 좌석 화면 + 실시간 상태 가져오기
        val sectionClickListener = View.OnClickListener {
            binding.screenSections.visibility = View.GONE
            binding.screenSeats.visibility = View.VISIBLE
            binding.logText.text = "좌석을 선택한 뒤 결제 버튼을 눌러주세요"
            initSeats()
        }
        binding.btnSectionA.setOnClickListener(sectionClickListener)
        binding.btnSectionB.setOnClickListener(sectionClickListener)
        binding.btnSectionC.setOnClickListener(sectionClickListener)
        binding.btnSectionD.setOnClickListener(sectionClickListener)

        // 4. 최종 결제 (CAS 요청)
        binding.btnPurchase.setOnClickListener { reserveSeatWithCAS() }

        // 5. 대기 취소
        binding.btnCancelQueue.setOnClickListener {
            queuePollJob?.cancel()
            queuePollJob = null
            resetToConcerts()
        }

        // 6. 성공 화면 확인
        binding.btnSuccessOk.setOnClickListener { resetToConcerts() }
    }

    private fun setupUI() {
        val thumbShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#4CAF50"))
        }
        binding.slideThumb.background = thumbShape

        val trackShape = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(Color.parseColor("#EEEEEE"))
        }
        binding.slideTrack.background = trackShape
    }

    // ====== [Step 0] HELLO -> NONCE ==========================================
    private fun requestNonceAndEnterSliding() {
        binding.logText.text = "세션 준비 중..."
        CoroutineScope(Dispatchers.IO).launch {
            val resp = runCatching { Net.call("HELLO:$deviceId") }.getOrDefault("")
            withContext(Dispatchers.Main) {
                if (resp.startsWith("NONCE:")) {
                    nonce = resp.substringAfter("NONCE:")
                    binding.screenConcerts.visibility = View.GONE
                    binding.screenSliding.visibility = View.VISIBLE
                    binding.logText.text = "슬라이드 바를 끝까지 밀어주세요"
                    binding.slideThumb.translationX = 0f
                } else {
                    Toast.makeText(this@MainActivity, "데몬 연결 실패", Toast.LENGTH_SHORT).show()
                    binding.logText.text = "데몬 연결 실패 — 서버 상태 확인"
                }
            }
        }
    }

    // ====== [Step 1] 슬라이딩 인증 ============================================
    private fun setupSlidingListener() {
        var initialX = 0f
        var thumbStartX = 0f
        binding.slideThumb.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    thumbStartX = view.translationX
                    touchPathX.clear(); touchPathY.clear(); touchPathT.clear()
                    touchStartMs = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    var newX = thumbStartX + dx
                    val maxX = (binding.slideTrack.width - view.width).toFloat()
                    if (newX < 0f) newX = 0f
                    if (newX > maxX) newX = maxX
                    view.translationX = newX
                    touchPathX.add(event.rawX)
                    touchPathY.add(event.rawY)
                    touchPathT.add(System.currentTimeMillis() - touchStartMs)
                    binding.logText.text = "궤적 분석 중... (${touchPathX.size} pts)"
                }
                MotionEvent.ACTION_UP -> {
                    val maxX = (binding.slideTrack.width - view.width).toFloat()
                    if (view.translationX >= maxX * 0.95f) {
                        verifyAndGetToken(view)
                    } else {
                        view.translationX = 0f
                        binding.logText.text = "끝까지 밀어주세요!"
                    }
                }
            }
            true
        }
    }

    // ====== [Step 2] AUTH -> TOKEN ===========================================
    private fun verifyAndGetToken(slideView: View) {
        val slidingLog = buildSlidingLog()
        val canonical = "$userId|$deviceId|$nonce|$slidingLog"
        val clientHash = Crypto.sha256Hex(canonical)

        CoroutineScope(Dispatchers.IO).launch {
            val resp = runCatching {
                Net.call("AUTH:$userId:$deviceId:$nonce:$slidingLog:$clientHash")
            }.getOrDefault("")

            withContext(Dispatchers.Main) {
                when {
                    resp.startsWith("TOKEN:") -> {
                        token = resp.substringAfter("TOKEN:")
                        Toast.makeText(this@MainActivity, "인증 완료, 토큰 발급", Toast.LENGTH_SHORT).show()
                        binding.screenSliding.visibility = View.GONE
                        binding.screenSections.visibility = View.VISIBLE
                    }
                    resp.startsWith("QUEUED:") -> {
                        val parts = resp.removePrefix("QUEUED:").split(":")
                        val pos = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val eta = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        enterWaitingQueue(pos, eta)
                    }
                    else -> {
                        val reason = resp.substringAfter("AUTH_FAIL:", "unknown")
                        binding.logText.text = "인증 실패: $reason"
                        slideView.translationX = 0f
                    }
                }
            }
        }
    }

    // ====== [Step 2b] 대기열 진입 + 1초마다 POLL ===============================
    private fun enterWaitingQueue(pos: Int, eta: Int) {
        binding.screenSliding.visibility = View.GONE
        binding.screenWaiting.visibility = View.VISIBLE
        updateQueueText(pos, eta)

        queuePollJob?.cancel()
        queuePollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(1000)
                val resp = runCatching { Net.call("POLL:$deviceId") }.getOrDefault("")
                withContext(Dispatchers.Main) {
                    when {
                        resp.startsWith("TOKEN:") -> {
                            token = resp.substringAfter("TOKEN:")
                            binding.screenWaiting.visibility = View.GONE
                            binding.screenSections.visibility = View.VISIBLE
                            Toast.makeText(this@MainActivity, "대기 종료 — 토큰 발급", Toast.LENGTH_SHORT).show()
                            queuePollJob?.cancel(); queuePollJob = null
                        }
                        resp.startsWith("QUEUED:") -> {
                            val parts = resp.removePrefix("QUEUED:").split(":")
                            updateQueueText(
                                parts.getOrNull(0)?.toIntOrNull() ?: 0,
                                parts.getOrNull(1)?.toIntOrNull() ?: 0
                            )
                        }
                        resp.startsWith("POLL_FAIL:") -> {
                            Toast.makeText(this@MainActivity,
                                "대기열 만료 — 다시 인증해주세요", Toast.LENGTH_LONG).show()
                            queuePollJob?.cancel(); queuePollJob = null
                            resetToConcerts()
                        }
                    }
                }
            }
        }
    }

    private fun updateQueueText(pos: Int, eta: Int) {
        binding.tvQueueStatus.text = "대기 순번: ${pos + 1}"
        binding.tvQueueEta.text = "예상 대기 시간: 약 ${eta}초"
    }

    private fun buildSlidingLog(): String {
        val sb = StringBuilder()
        val n = touchPathX.size
        for (i in 0 until n) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(touchPathX[i].toInt()).append(',')
              .append(touchPathY[i].toInt()).append(',')
              .append(touchPathT[i])
        }
        return sb.toString()
    }

    // ====== [Step 3] 좌석 그리기 + 실시간 폴링 ================================
    private fun initSeats() {
        binding.gridSeats.removeAllViews()
        binding.btnPurchase.visibility = View.GONE
        selectedSeatIndex = -1

        CoroutineScope(Dispatchers.IO).launch {
            val resp = runCatching { Net.call("FETCH_STATUS") }.getOrDefault("")
            val statusData = if (resp.startsWith("STATUS:")) resp.substringAfter("STATUS:") else null

            withContext(Dispatchers.Main) {
                if (statusData == null || statusData.length < MAX_SEATS) {
                    Toast.makeText(this@MainActivity, "좌석 정보 로딩 실패", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val dpToPx = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }
                binding.gridSeats.columnCount = 10

                for (i in 0 until MAX_SEATS) {
                    val status = statusData[i]
                    seatStatus[i] = status
                    val isSold = (status == '1')
                    val seat = View(this@MainActivity).apply {
                        layoutParams = android.widget.GridLayout.LayoutParams().apply {
                            width = dpToPx(28); height = dpToPx(28)
                            setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                        }
                        background = GradientDrawable().apply {
                            cornerRadius = 8f
                            setColor(Color.parseColor(if (isSold) COLOR_TAKEN else COLOR_EMPTY))
                        }
                        if (!isSold) {
                            setOnClickListener { onSeatClicked(i) }
                        } else {
                            isEnabled = false
                        }
                    }
                    seatViews[i] = seat
                    binding.gridSeats.addView(seat)
                }

                startSeatPolling()
            }
        }
    }

    private fun onSeatClicked(index: Int) {
        if (seatStatus[index] == '1') return
        // 기존 선택 해제
        if (selectedSeatIndex >= 0) {
            (seatViews[selectedSeatIndex]?.background as? GradientDrawable)?.setColor(Color.parseColor(COLOR_EMPTY))
        }
        // 신규 선택
        selectedSeatIndex = index
        (seatViews[index]?.background as? GradientDrawable)?.setColor(Color.parseColor(COLOR_SELECTED))
        binding.btnPurchase.visibility = View.VISIBLE
    }

    private fun startSeatPolling() {
        seatPollJob?.cancel()
        seatPollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(SEAT_POLL_INTERVAL_MS)
                val resp = runCatching { Net.call("FETCH_STATUS") }.getOrDefault("")
                val s = if (resp.startsWith("STATUS:")) resp.substringAfter("STATUS:") else null
                if (s != null && s.length >= MAX_SEATS) {
                    withContext(Dispatchers.Main) { applySeatDiff(s) }
                }
            }
        }
    }

    private fun stopSeatPolling() {
        seatPollJob?.cancel()
        seatPollJob = null
    }

    // 새로 점유된 좌석만 회색 + 비활성화. (CAS 는 비가역이라 0->1 방향만)
    private fun applySeatDiff(newStatus: String) {
        var stolen = false
        for (i in 0 until MAX_SEATS) {
            val before = seatStatus[i]
            val after = newStatus[i]
            if (before == '0' && after == '1') {
                seatStatus[i] = '1'
                val v = seatViews[i] ?: continue
                (v.background as? GradientDrawable)?.setColor(Color.parseColor(COLOR_TAKEN))
                v.setOnClickListener(null)
                v.isEnabled = false
                if (i == selectedSeatIndex) stolen = true
            }
        }
        if (stolen) {
            Toast.makeText(this, "선택한 좌석을 다른 사람이 잡았어요", Toast.LENGTH_SHORT).show()
            selectedSeatIndex = -1
            binding.btnPurchase.visibility = View.GONE
        }
    }

    // ====== [Step 4] RESERVE (CAS) ===========================================
    private fun reserveSeatWithCAS() {
        if (token.isEmpty() || selectedSeatIndex < 0) return
        val seatIdx = selectedSeatIndex
        stopSeatPolling() // 결제 중에는 폴링 정지
        binding.screenSeats.visibility = View.GONE
        binding.screenPayment.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val resp = runCatching {
                Net.call("RESERVE:$token:$seatIdx")
            }.getOrDefault("")

            withContext(Dispatchers.Main) {
                binding.screenPayment.visibility = View.GONE
                when {
                    resp == "RESULT:SUCCESS" -> showSuccessScreen(seatIdx)
                    resp == "RESULT:FAIL_TAKEN" -> {
                        Toast.makeText(this@MainActivity,
                            "이선좌! 그새 누가 결제했습니다.", Toast.LENGTH_LONG).show()
                        binding.screenSeats.visibility = View.VISIBLE
                        initSeats()
                    }
                    resp == "RESULT:BAD_TOKEN" -> {
                        Toast.makeText(this@MainActivity,
                            "토큰 만료/위조 — 다시 인증해주세요", Toast.LENGTH_LONG).show()
                        resetToConcerts()
                    }
                    else -> {
                        Toast.makeText(this@MainActivity,
                            "결제 서버 통신 에러", Toast.LENGTH_SHORT).show()
                        binding.screenSeats.visibility = View.VISIBLE
                        startSeatPolling()
                    }
                }
            }
        }
    }

    private fun showSuccessScreen(seatIdx: Int) {
        binding.tvSuccessConcert.text = currentConcert
        binding.tvSuccessSeat.text = "좌석 ${seatIdx + 1}번"
        binding.screenSeats.visibility = View.GONE
        binding.screenPayment.visibility = View.GONE
        binding.screenSuccess.visibility = View.VISIBLE
    }

    private fun resetToConcerts() {
        queuePollJob?.cancel(); queuePollJob = null
        stopSeatPolling()
        token = ""
        nonce = ""
        selectedSeatIndex = -1
        for (i in 0 until MAX_SEATS) seatViews[i] = null
        seatStatus = CharArray(MAX_SEATS) { '0' }

        binding.screenSections.visibility = View.GONE
        binding.screenSeats.visibility = View.GONE
        binding.screenSliding.visibility = View.GONE
        binding.screenWaiting.visibility = View.GONE
        binding.screenPayment.visibility = View.GONE
        binding.screenSuccess.visibility = View.GONE
        binding.screenConcerts.visibility = View.VISIBLE
        binding.slideThumb.translationX = 0f
        binding.logText.text = "원하는 콘서트를 선택하세요."
    }

    override fun onDestroy() {
        super.onDestroy()
        queuePollJob?.cancel()
        seatPollJob?.cancel()
    }
}
