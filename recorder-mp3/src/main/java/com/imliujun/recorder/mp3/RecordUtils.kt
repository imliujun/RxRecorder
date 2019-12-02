package com.imliujun.recorder.mp3

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioManager
import android.text.TextUtils
import android.util.Pair
import com.imliujun.recorder.ObjectPools
import com.imliujun.recorder.RxRecorder
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.processors.FlowableProcessor
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import okio.BufferedSink
import okio.Okio
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 项目名称：RxAudioRecorder
 * 类描述：
 * 创建人：liujun
 * 创建时间：2019-11-28 16:42
 * 修改人：liujun
 * 修改时间：2019-11-28 16:42
 * 修改备注：
 * @version
 */
object RecordUtils {

    lateinit var context: Application

    lateinit var recordDir: File

    private const val SAMPLE_RATE = 44100 //录音采样率

    private var flowable: FlowableProcessor<RecordData>? = null

    private var recorder: RxRecorder? = null

    private var mp3File: File? = null

    private var androidLame: AndroidLame? = null

    private var mp3Buffer: ByteArray? = null

    private var mp3Stream: BufferedSink? = null

    private var isComplete = false

    private var playDisposable: Disposable? = null

    private var focusChangeListener: AudioManager.OnAudioFocusChangeListener? = null


    /**
     * 录音
     * @param audioDuration 本次录音的最大时长，到时候会自动结束
     */
    @Synchronized
    fun recording(audioDuration: Long): Flowable<RecordData> {
        stopPlay()
        stopRecording()
        AudioManagerUtils.requestFocus(getAudioFocusChangeListener())
        val sampleRate = SAMPLE_RATE
        val bufferSize = (sampleRate * 16 / 8 * 1) / 10
        recorder = RxRecorder.getRecorder(
            maxDuration = audioDuration,
            sampleRateInHz = sampleRate,
            bufferSizeInBytes = bufferSize
        )
        val processor = PublishProcessor.create<RecordData>().toSerialized()
        this.flowable = processor
        return processor.doOnSubscribe {
            start()
        }.onBackpressureBuffer()
    }


    /**
     * 当前是否正在录音
     */
    fun isRecording(): Boolean {
        return RxRecorder.isRecording()
    }

    @Synchronized
    fun pauseRecording() {
        Timber.i("pauseRecording")
        recorder?.pause()
    }

    @Synchronized
    fun resumeRecording() {
        stopPlay()
        AudioManagerUtils.requestFocus(getAudioFocusChangeListener())
        //        if (!requestFocus(getAudioFocusChangeListener())) return
        recorder?.resume()
    }

    /**
     * 结束释放资源
     */
    @Synchronized
    fun stopRecording() {
        recorder?.stop()
    }

    /**
     * 试听录制的音频
     * @param period 多长时间返回一次播放进度 单位毫秒
     * @return Pair.first 为当前播放进度，单位毫秒   Pair.second 为音频总时长，单位毫秒
     */
    @Synchronized
    fun play(period: Long): Flowable<Pair<Int, Int>> {
        pauseRecording()
        AudioManagerUtils.requestFocus(getAudioFocusChangeListener())
        //        if (!requestFocus(getAudioFocusChangeListener())) return Flowable.error(RuntimeException("获取音频焦点失败"))

        return Flowable.create<Pair<Int, Int>>({ emitter ->
            if (mp3File == null) {
                if (!emitter.isCancelled) {
                    emitter.onError(RuntimeException("AudioFile is null"))
                }
                return@create
            }
            try {
                mp3Stream?.flush()
            } catch (e: Exception) {
                Timber.e(e)
            }
            RxAudioPlayer.play(
                PlayConfig.file(mp3File)
                    .streamType(AudioManager.STREAM_MUSIC)
                    .build()
            ).subscribe({
                playResult(it, period, emitter)
            }, {
                Timber.e(it)
                playDisposable?.dispose()
                if (!emitter.isCancelled) {
                    emitter.onError(it)
                }
            }, {
                playDisposable?.dispose()
                if (!emitter.isCancelled) {
                    emitter.onComplete()
                }
            })
        }, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    private fun playResult(it: Boolean, period: Long, emitter: FlowableEmitter<Pair<Int, Int>>) {
        if (!it) {
            if (!emitter.isCancelled) {
                emitter.onError(RuntimeException("File:${mp3File?.absolutePath} is Play failed"))
            }
            return
        }
        var duration = 0
        playDisposable?.dispose()
        playDisposable = Observable.interval(300, period, TimeUnit.MILLISECONDS).subscribe({
            if (emitter.isCancelled) {
                playDisposable?.dispose()
                return@subscribe
            }
            try {
                val mediaPlayer = RxAudioPlayer.mediaPlayer
                when {
                    mediaPlayer == null -> {
                        emitter.onComplete()
                        playDisposable?.dispose()
                    }
                    mediaPlayer.isPlaying -> {
                        val position = mediaPlayer.currentPosition
                        if (duration <= 0) {
                            duration = mediaPlayer.duration
                        }
                        emitter.onNext(Pair(position, duration))
                    }
                    else -> {
                        if (duration <= 0) {
                            duration = mediaPlayer.duration
                        }
                        emitter.onNext(Pair(-2, duration))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e)
                emitter.onComplete()
                playDisposable?.dispose()
            }

        }, {
            Timber.e(it)
            if (!emitter.isCancelled) {
                emitter.onComplete()
            }
        })
    }

    fun isPlaying(): Boolean {
        return RxAudioPlayer.mediaPlayer?.isPlaying == true
    }

    @Synchronized
    fun pausePlay() {
        RxAudioPlayer.pause()
    }

    @Synchronized
    fun resumePlay() {
        pauseRecording()
        AudioManagerUtils.requestFocus(getAudioFocusChangeListener())
        //        if (!requestFocus(getAudioFocusChangeListener())) return
        RxAudioPlayer.resume()
    }

    @Synchronized
    fun stopPlay() {
        RxAudioPlayer.stopPlay()
    }

    /**
     * 界面销毁时调用
     */
    @Synchronized
    fun destroy() {
        stopPlay()
        stopRecording()
        AudioManagerUtils.removeListeners(focusChangeListener)
        focusChangeListener = null
        mp3Buffer = null
        mp3File = null
    }

    @SuppressLint("CheckResult")
    private fun start() {
        recorder?.start()?.observeOn(Schedulers.io())?.subscribe({
            when (it.status) {
                RxRecorder.AudioStatus.START -> {
                    flowable?.onNext(RecordData(RecordStatus.START))
                    initData()
                }
                RxRecorder.AudioStatus.PAUSE -> {
                    flowable?.onNext(RecordData(RecordStatus.PAUSE))
                }
                RxRecorder.AudioStatus.RESUME -> {
                    flowable?.onNext(RecordData(RecordStatus.RESUME))
                }
                RxRecorder.AudioStatus.RECORDING -> {
                    flowable?.onNext(RecordData(RecordStatus.RECORDING, it.duration))
                    dataProcessing(it)
                }
                RxRecorder.AudioStatus.STOP -> {
                    flowable?.onNext(RecordData(RecordStatus.STOP, audioFile = mp3File))
                }
                RxRecorder.AudioStatus.MAX_DURATION_REACHED -> {
                    flowable?.onNext(
                        RecordData(
                            RecordStatus.MAX_DURATION_REACHED,
                            audioFile = mp3File
                        )
                    )
                }
            }
        }, {
            Timber.e(it)
            flushAndClose()
            flowable?.onError(it)
            recorder?.stop()
        }, {
            Timber.i("语音录制完成")
            flushAndClose()
            isComplete = true
            flowable?.onComplete()
        })
    }

    private fun initData() {
        isComplete = false
        val recorder = this.recorder ?: return
        val currentTimeMillis = System.currentTimeMillis()
        if (!this::recordDir.isInitialized) {
            recordDir = File(context.externalCacheDir, "recordDir")
        }
        val file = File(recordDir, "$currentTimeMillis.mp3")
        mp3File = file
        if (file.parentFile?.exists() != true){
            file.parentFile?.mkdirs()
        }
        mp3Stream = Okio.buffer(Okio.sink(file))

        val channelCount = if (recorder.channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val sampleRate = recorder.sampleRateInHz


        androidLame = LameBuilder().setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutChannels(channelCount)
            .setOutBitrate(if (sampleRate == 16000) 32 else 128)
            .setQuality(2)
            .build()
    }

    private fun flushAndClose() {
        try {
            mp3Buffer?.let {
                val outputMp3buf = androidLame?.flush(it)
                if (outputMp3buf != null && outputMp3buf > 0) {
                    mp3Stream?.write(it, 0, outputMp3buf)
                }
            }
            mp3Stream?.close()
            mp3Stream = null
            androidLame?.close()
            androidLame = null
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun dataProcessing(audioData: RxRecorder.AudioData) {
        Timber.i("dataProcessing duration:%s readSize:%s ", audioData.duration, audioData.readSize)
        if (androidLame == null || mp3Stream == null) {
            return
        }
        audioData.rawData?.let {
            val pcmData: ShortArray = ObjectPools.getShortArray(it.capacity() / 2)
            ByteConverts.byteToShorts(it, audioData.readSize, pcmData)
            val pcmSize = audioData.readSize / 2

            val volume = calcDecibelLevel(pcmData, pcmSize)
            Timber.i("音波大小 volume:%s", volume)
            flowable?.onNext(RecordData(RecordStatus.VOLUME, volume = volume))

            val encode = mp3Encode(pcmData, pcmSize, audioData.channelCount)
            val buffer = mp3Buffer
            if (buffer != null && encode != null && encode > 0) {
                mp3Stream?.write(buffer, 0, encode)
            }

            ObjectPools.release(pcmData)
            ObjectPools.release(it)
            Timber.i(
                "dataProcessing duration:%s readSize:%s mp3encode:%s",
                audioData.duration,
                audioData.readSize,
                encode
            )
        }
    }

    private fun mp3Encode(rawData: ShortArray, readSize: Int, channelCount: Int): Int? {
        if (mp3Buffer == null) {
            mp3Buffer = ByteArray(ceil(7200 + rawData.size * 2.0 * 1.25).toInt())
        }
        return when (channelCount) {
            1 -> androidLame?.encode(rawData, rawData, readSize, mp3Buffer)
            2 -> {
                val leftData = ShortArray(readSize / 2)
                val rightData = ShortArray(readSize / 2)
                var i = 0
                while (i < readSize / 2) {
                    leftData[i] = rawData[2 * i]
                    if (2 * i + 1 < readSize) {
                        leftData[i + 1] = rawData[2 * i + 1]
                    }
                    if (2 * i + 2 < readSize) {
                        rightData[i] = rawData[2 * i + 2]
                    }
                    if (2 * i + 3 < readSize) {
                        rightData[i + 1] = rawData[2 * i + 3]
                    }
                    i += 2
                }
                androidLame?.encode(leftData, rightData, readSize / 2, mp3Buffer)
            }
            else -> 0
        }
    }


    private fun calcDecibelLevel(buffer: ShortArray?, readSize: Int): Double {
        var sum = 0.0
        if (buffer == null) {
            return sum
        }
        for (rawSample in buffer) {
            val sample = rawSample / 32768.0
            sum += sample * sample
        }

        val rms = sqrt(sum / readSize)

        var db = 20 * log10(rms)
        val dbStr = db.toString()
        if (TextUtils.equals(dbStr, "NaN") || TextUtils.equals(dbStr, "nan")) {
            db = 0.0
        }
        return volumeProcess(db)
    }

    private fun volumeProcess(volume: Double): Double {
        return if (volume == Double.MAX_VALUE || volume == Double.MIN_VALUE) {
            0.5
        } else {
            var floatValue = getDoubleValue(volume)
            if (floatValue < 0) {
                floatValue += 1.0
            }
            getDealBack(floatValue)
        }
    }

    private fun getDealBack(vom: Double): Double {
        val voms = (vom * 100).roundToLong() / 100.0
        return when {
            voms < 0.1 -> 0.1
            voms < 0.32 -> voms / 2
            else -> voms
        }
    }

    /**
     * 新数据=（原数据-极小值）/（极大值-极小值）
     * 归一算法 分贝值正常值域为0 dB 到90.3 dB。
     * 0-30 基本无法区分，
     *
     * @param value
     * @return
     */
    private fun getDoubleValue(value: Double): Double {
        return value / 90.3 //32768.0
    }

    data class RecordData(
        @JvmField
        val status: RecordStatus,
        @JvmField
        val duration: Long = 0, //毫秒
        @JvmField
        val volume: Double = 0.0, //音量大小
        @JvmField
        val audioFile: File? = null //音频文件
    )

    enum class RecordStatus {
        START,
        PAUSE,
        RESUME,
        STOP,
        RECORDING,
        MAX_DURATION_REACHED,
        VOLUME
    }


    private fun getAudioFocusChangeListener(): AudioManager.OnAudioFocusChangeListener? {
        //        if (focusChangeListener == null) {
        //            focusChangeListener = AudioManager.OnAudioFocusChangeListener {
        //                if (it == AudioManager.AUDIOFOCUS_LOSS) {
        //                    AudioManagerUtils.removeListeners(focusChangeListener)
        //                    pausePlay()
        //                    pauseRecording()
        //                }
        //            }
        //        }
        return focusChangeListener
    }


}