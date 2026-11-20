// 소리 받기(마이크)와 내보내기(스피커).
// 통화용 경로(VOICE_COMMUNICATION)를 써서, 스피커에서 나온 AI 목소리가 마이크로 되돌아오는 것을 기기가 지워 주게 한다.
package com.fidenit.kite.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlin.math.sqrt

const val MIC_RATE = 16000
const val TTS_RATE = 24000

/** 대화하는 동안 소리가 통화 경로로 스피커에서 나오게 한다. */
fun talkMode(context: Context, on: Boolean) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    try {
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } else {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
    } catch (_: Exception) {
    }
}

/**
 * 마이크. 만들면 바로 듣기 시작하고, 들은 소리를 쌓아 둔다 (16kHz, 16비트, 한 채널).
 * [onFrame] 을 주면 (Pro 실시간 듣기) 20ms 마다 읽은 조각을 그 자리에서 넘기고 쌓아 두지 않는다 (조각 배열은 다음 읽기에 다시 쓴다).
 * [onEffects] 를 주면 열 때 한 번, 에코 지우기·잡음 줄이기를 만들었는지·켰는지를 `aec=만듦/켬 ns=만듦/켬` (1/0) 으로 알린다.
 */
class Mic(private val onFrame: ((ShortArray, Int) -> Unit)? = null, private val onEffects: ((String) -> Unit)? = null) {
    @Volatile
    var level: Float = 0f
        private set

    /** 마이크를 쓸 수 없게 됐으면 그 까닭 (열지 못함 / 듣는 중에 멈춤). 화면의 글은 `Speaking.micNotice` 가 만든다. */
    @Volatile
    var error: MicProblem? = null
        private set

    @Volatile
    private var running = true
    private val lock = Any()
    private var pcm = ShortArray(MIC_RATE * 8)
    private var size = 0
    private val worker: Thread

    init {
        worker = Thread { record() }
        worker.start()
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        var rec: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var recording = false // 듣기 시작한 뒤의 오류인가
        try {
            val min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, MIC_RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                error = MicProblem.OPEN
                return
            }
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
            onEffects?.let { tell ->
                fun flag(made: Boolean, on: () -> Boolean) = (if (made) "1" else "0") + "/" + (if (made && try { on() } catch (_: Exception) { false }) "1" else "0")
                try {
                    tell("aec=${flag(aec != null) { aec!!.enabled }} ns=${flag(ns != null) { ns!!.enabled }}")
                } catch (_: Exception) {
                }
            }
            rec.startRecording()
            recording = true
            val buf = ShortArray(MIC_RATE / 50) // 20ms
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    error = MicProblem.STOPPED
                    return
                }
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                level = sqrt(sum / n).toFloat()
                if (onFrame != null) {
                    try {
                        onFrame.invoke(buf, n)
                    } catch (_: Exception) {
                    }
                    continue
                }
                synchronized(lock) {
                    if (size + n > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, size + n))
                    System.arraycopy(buf, 0, pcm, size, n)
                    size += n
                }
            }
        } catch (e: Exception) {
            error = if (recording) MicProblem.STOPPED else MicProblem.OPEN
        } finally {
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            aec?.release()
            ns?.release()
            rec?.release()
        }
    }

    /** 모은 소리 가운데 마지막 `secs` 초만 남긴다 (그 앞은 버린다). */
    fun keepLast(secs: Float) {
        val keep = (secs * MIC_RATE).toInt()
        synchronized(lock) {
            if (size > keep) {
                System.arraycopy(pcm, size - keep, pcm, 0, keep)
                size = keep
            }
        }
    }

    /** 듣기를 끝내고 모은 소리를 돌려준다. */
    fun finish(): ShortArray {
        running = false
        try {
            worker.join(500)
        } catch (_: Exception) {
        }
        return synchronized(lock) { pcm.copyOf(size) }
    }
}

/** 스피커. 받은 소리를 바로 튼다. [fastStart] (Pro 실시간 읽기, 안드로이드 12 이상): 0.5초 버퍼가 찰 때까지 기다리지 않고 약 100ms 가 쌓이면 틀기 시작한다. */
class Player(fastStart: Boolean = false) : Eleven.Sink {
    private val track: AudioTrack

    @Volatile
    private var written = 0L

    init {
        val min = AudioTrack.getMinBufferSize(TTS_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(TTS_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, TTS_RATE)) // 0.5초
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (fastStart && Build.VERSION.SDK_INT >= 31) {
            try {
                track.setStartThresholdInFrames(TTS_RATE / 10)
            } catch (_: Exception) {
            }
        }
        track.play()
    }

    /** 소리 조각을 튼다 (자리가 날 때까지 기다린다). */
    override fun push(pcm: ShortArray, n: Int) {
        val w = track.write(pcm, 0, n)
        if (w > 0) written += w
    }

    /** 아직 틀지 못하고 남은 소리의 길이 (초). */
    override fun pendingSecs(): Float = ((written - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0)) / TTS_RATE.toFloat()

    /** 틀던 소리를 바로 멈추고 버린다. 다음 소리를 받을 준비는 되어 있다. */
    override fun stop() {
        try {
            track.pause()
            track.flush()
            written = 0
            track.play()
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            track.pause()
            track.flush()
            track.release()
        } catch (_: Exception) {
        }
    }
}
