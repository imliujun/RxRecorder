package com.imliujun.audio

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.imliujun.recorder.mp3.RecordUtils
import com.yanzhenjie.permission.AndPermission
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    val format = SimpleDateFormat("mm:ss", Locale.getDefault())

    var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RecordUtils.context = application
        Timber.plant(Timber.DebugTree())
        AndPermission.with(this).runtime().permission(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).start()
        record.setOnClickListener {
            recording()
        }

        pause.setOnClickListener {
            RecordUtils.pauseRecording()
        }

        resume.setOnClickListener {
            RecordUtils.resumeRecording()
        }

        stop.setOnClickListener {
            RecordUtils.stopRecording()
        }

        play.setOnClickListener {
            RecordUtils.play(2000).subscribe()
        }
    }

    fun recording() {
        disposable?.dispose()
        disposable = RecordUtils.recording(2 * 60 * 1000)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Timber.i("status:%s", it.status)
                when (it.status) {
                    RecordUtils.RecordStatus.VOLUME -> {
                        setVolume(it.volume)
                    }
                    RecordUtils.RecordStatus.MAX_DURATION_REACHED, RecordUtils.RecordStatus.STOP -> {
                        val action =
                            if (it.status == RecordUtils.RecordStatus.STOP) "结束录音" else "录音达到最大时长"
                        text.text = "$action\n${text.text}"
                        text.text = "音频文件路径：${it.audioFile?.path}\n${text.text}"
                    }
                    RecordUtils.RecordStatus.RECORDING -> {
                        duration.text = "录音时长：${format.format(Date(it.duration))}"
                    }
                    RecordUtils.RecordStatus.START -> {
                        text.text = "开始录音\n${text.text}"
                    }
                    RecordUtils.RecordStatus.PAUSE -> {
                        text.text = "暂停录音\n${text.text}"
                    }
                    RecordUtils.RecordStatus.RESUME -> {
                        text.text = "继续录音\n${text.text}"
                    }
                }
            }, Timber::e)
    }

    private fun setVolume(volume: Double) {
        volumeText.text = volume.toString()
        val layoutParams = volumeView.layoutParams
        layoutParams.height = (300 * volume).toInt()
        volumeView.layoutParams = layoutParams
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable?.dispose()
    }

}
