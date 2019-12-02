package com.imliujun.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import io.reactivex.processors.FlowableProcessor
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 项目名称：rxAudioRecorder
 * 类描述：
 * 创建人：liujun
 * 创建时间：2019-11-25 18:41
 * 修改人：liujun
 * 修改时间：2019-11-25 18:41
 * 修改备注：
 * @version
 */
class RxRecorder private constructor(
    val maxDuration: Long,
    val audioSource: Int,
    val sampleRateInHz: Int,
    val channelConfig: Int,
    val audioFormat: Int,
    val bufferSize: Int
) {

    companion object {

        /**
         * 是否开启 噪音抑制器，回声消除器，自动增益
         */
        var enabledAudioEffect = true

        private var processor: FlowableProcessor<AudioStatus>? = null

        private val isRecording = AtomicBoolean()

        private val isPause = AtomicBoolean()

        private var currentDuration: Long = 0 //当前录音时长，单位 毫秒

        private var subscription: Subscription? = null

        /**
         * gets a RxRecorder object
         * [maxDuration] set the maximum recording duration，Unit: milliseconds
         * [audioSource] the recording source. See [MediaRecorder.AudioSource] for the recording source definitions.
         * [sampleRateInHz] the sample rate expressed in Hertz. 44100Hz is currently the only
         *   rate that is guaranteed to work on all devices, but other rates such as 22050,
         *   16000, and 11025 may work on some devices.
         * [channelConfig] describes the configuration of the audio channels.
         *   See {@link AudioFormat#CHANNEL_IN_MONO} and
         *   {@link AudioFormat#CHANNEL_IN_STEREO}.  {@link AudioFormat#CHANNEL_IN_MONO} is guaranteed
         *   to work on all devices.
         * [audioFormat] the format in which the audio data is to be returned.
         *   See {@link AudioFormat#ENCODING_PCM_8BIT}, {@link AudioFormat#ENCODING_PCM_16BIT},
         *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
         *  [bufferSizeInBytes] the total size (in bytes) of the buffer where audio data is written
         *   to during the recording. New audio data can be read from this buffer in smaller chunks
         *   than this size. default use [AudioRecord.getMinBufferSize]
         */
        @JvmStatic
        @Synchronized
        fun getRecorder(
            maxDuration: Long,
            audioSource: Int = MediaRecorder.AudioSource.MIC,
            sampleRateInHz: Int = 44100,
            channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
            audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes: Int = -1
        ): RxRecorder {
            release()
            val bufferSize = max(
                bufferSizeInBytes,
                AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
            )
            return RxRecorder(
                maxDuration,
                audioSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                bufferSize
            )
        }

        /**
         * 当前是否正在录音
         */
        @JvmStatic
        fun isRecording(): Boolean {
            return isRecording.get()
        }

        /**
         * 监听系统的内存使用情况，回调到本方法进行内存释放
         */
        @JvmStatic
        fun onTrimMemory(level: Int) {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !isRecording()) {
                ObjectPools.clearAll()
            }
        }

        @Synchronized
        private fun release() {
            isRecording.compareAndSet(true, false)
            if (subscription != null) {
                subscription?.cancel()
                subscription = null
            }
            currentDuration = 0
            isPause.set(false)
        }

        private fun mapFormat(format: Int): Int {
            return when (format) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                else -> 0
            }
        }


    }

    private var disposable: Disposable? = null
    private val flowable: FlowableProcessor<AudioData> =
        PublishProcessor.create<AudioData>().toSerialized()

    init {
        val publishProcessor = PublishProcessor.create<AudioStatus>().toSerialized()
        processor = publishProcessor
        publishProcessor.subscribe(object : Subscriber<AudioStatus> {
            override fun onComplete() {
                release()
            }

            override fun onNext(audioStatus: AudioStatus) {
                when (audioStatus) {
                    AudioStatus.RESUME, AudioStatus.START -> {
                        disposable = Schedulers.single().createWorker().schedule(
                            AudioRecordRunnable(
                                audioSource, sampleRateInHz, channelConfig,
                                audioFormat, bufferSize, maxDuration, flowable
                            )
                        )
                    }
                    AudioStatus.MAX_DURATION_REACHED -> {
                        dispose()
                    }
                    AudioStatus.STOP -> {
                        if (isPause.get()) {
                            flowable.onNext(AudioData(AudioStatus.STOP))
                            flowable.onComplete()
                        }
                        dispose()
                    }
                    AudioStatus.PAUSE -> {
                        dispose()
                    }
                    else -> {
                    }
                }
                subscription?.request(1)
            }

            override fun onError(t: Throwable) {
                Timber.e(t)
            }

            override fun onSubscribe(s: Subscription) {
                subscription = s
                s.request(1)
            }
        })
    }

    private fun dispose() {
        isRecording.compareAndSet(true, false)
        disposable?.dispose()
        disposable = null
    }

    @Synchronized
    fun start(): Flowable<AudioData> {
        return if (isRecording.compareAndSet(false, true)) {
            flowable.doOnSubscribe {
                processor?.onNext(AudioStatus.START)
            }.onBackpressureBuffer()
        } else {
            stop()
            Flowable.error<AudioData>(RuntimeException("当前正在录音"))
        }
    }

    @Synchronized
    fun stop() {
        if (isRecording.get() || isPause.get()) {
            processor?.onNext(AudioStatus.STOP)
        }
    }

    @Synchronized
    fun pause() {
        if (isPause.compareAndSet(false, true)) {
            processor?.onNext(AudioStatus.PAUSE)
        }
    }

    @Synchronized
    fun resume() {
        if (isPause.compareAndSet(true, false)) {
            if (isRecording.compareAndSet(false, true)) {
                processor?.onNext(AudioStatus.RESUME)
            }
        }
    }

    private class AudioRecordRunnable(
        audioSource: Int,
        sampleRateInHz: Int,
        channelConfig: Int,
        audioFormat: Int,
        val bufferSize: Int,
        val maxDuration: Long,
        val emitter: FlowableProcessor<AudioData>
    ) : Runnable {

        private var noiseSuppressor: NoiseSuppressor? = null
        private var acousticEchoCanceler: AcousticEchoCanceler? = null
        private var automaticGainControl: AutomaticGainControl? = null

        private var audioRecord: AudioRecord? = null
        private val bytesPerSecond: Int
        private var emptyDataCount = 0 //适配某些国产机乱改的权限系统，系统API判断有权限，实际没有权限，也不报错，只是读不到音频文件

        init {
            val record =
                AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSize)
            audioRecord = record
            bytesPerSecond =
                record.sampleRate * mapFormat(record.audioFormat) / 8 * record.channelCount
        }

        override fun run() {
            if (audioRecord == null) {
                noPermission()
                return
            }
            val audioRecord = this.audioRecord ?: return
            if (enabledAudioEffect) {
                startNS(audioRecord)
            }
            try {
                audioRecord.startRecording()
            } catch (e: IllegalStateException) {
                Timber.e(e)
                noPermission()
                return
            }
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                noPermission()
                return
            }
            if (currentDuration == 0L) {
                emitter.onNext(AudioData(AudioStatus.START))
            } else {
                emitter.onNext(AudioData(AudioStatus.RESUME))
            }
            var lostFrame = 0 //刚启动录音的前一百毫秒丢到，避免空白或者杂音
            while (isRecording.get()) {
                val buffer = ObjectPools.getDirectByteBuffer(bufferSize)
                val readSize = audioRecord.read(buffer, bufferSize)
                if (lostFrame < 1) {
                    lostFrame++
                    ObjectPools.release(buffer)
                    continue
                }
                if (readSize > 0) {
                    val readMs = 1000 * readSize / bytesPerSecond
                    currentDuration += readMs
                    buffer.clear()
                    buffer.limit(readSize)
                    Timber.i("audioRecord.read readSize:$readSize readMs:$readMs currentDuration:$currentDuration buffer:$buffer")
                    emitter.onNext(
                        AudioData(
                            AudioStatus.RECORDING,
                            readSize,
                            buffer,
                            audioRecord.channelCount,
                            currentDuration
                        )
                    )
                } else {
                    ObjectPools.release(buffer)
                    emptyDataCount++
                    if (emptyDataCount > 10) {
                        noPermission()
                        return
                    }
                }

                if (currentDuration >= maxDuration) {
                    emitter.onNext(AudioData(AudioStatus.MAX_DURATION_REACHED))
                    emitter.onComplete()
                    processor?.onNext(AudioStatus.MAX_DURATION_REACHED)
                    processor?.onComplete()
                    releaseAudio()
                    return
                }
            }

            releaseAudio()
            if (isPause.get()) {
                emitter.onNext(AudioData(AudioStatus.PAUSE))
            } else {
                emitter.onNext(AudioData(AudioStatus.STOP))
                emitter.onComplete()
                processor?.onComplete()
            }
        }

        private fun startNS(audioRecord: AudioRecord) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
                    noiseSuppressor?.enabled = true
                    Timber.i("开启噪音抑制器")
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                    acousticEchoCanceler?.enabled = true
                    Timber.i("开启声学回声消除器")
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
            try {
                if (AutomaticGainControl.isAvailable()) {
                    automaticGainControl = AutomaticGainControl.create(audioRecord.audioSessionId)
                    automaticGainControl?.enabled = true
                    Timber.i("开启自动增益控制")
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        private fun noPermission() {
            if (emitter.hasSubscribers() && !emitter.hasComplete() && !emitter.hasThrowable()) {
                emitter.onError(NoPermissionException("请检查是否开启录音权限"))
            }
            processor?.onNext(AudioStatus.STOP)
            processor?.onComplete()
            releaseAudio()
        }

        private fun releaseAudio() {
            releaseAudioEffect()
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        private fun releaseAudioEffect() {
            noiseSuppressor?.safetyRelease()
            acousticEchoCanceler?.safetyRelease()
            automaticGainControl?.safetyRelease()
        }

        private fun AudioEffect.safetyRelease() {
            try {
                this.release()
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

    }


    data class AudioData(
        @JvmField
        val status: AudioStatus,
        @JvmField
        val readSize: Int = 0, //数据大小
        @JvmField
        val rawData: ByteBuffer? = null, //录音数据，使用完后请调用 ObjectPools.release() 进行回收
        @JvmField
        val channelCount: Int = 1,
        @JvmField
        val duration: Long = 0 //毫秒
    )

    enum class AudioStatus {
        START,
        PAUSE,
        RESUME,
        STOP,
        RECORDING,
        MAX_DURATION_REACHED
    }


    class NoPermissionException(msg: String) : RuntimeException(msg)
}
