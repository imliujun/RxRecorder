# RxRecorder

**Android 录音组件，支持录制成 MP3**



## Gradle

```groovy
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
  	//直接获取 pcm 原始数据
    implementation 'com.github.imliujun.RxRecorder:recorder:v0.1'
  	//录制编码成 MP3 文件
  	implementation 'com.github.imliujun.RxRecorder:recorder-mp3:v0.1'
}
```



## 简单使用

```kotlin
//使用MP3录制功能，首先初始化
RecordUtils.context = application

RecordUtils.recording(2 * 60 * 1000)
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

```



使用非常简单，拿着工具类一通点看方法名就明白了。有问题可以直接看源码，就几百行代码。



## 感谢

[RxAndroidAudio](https://github.com/Piasy/RxAndroidAudio)  音频播放使用了 RxAndroidAudio 的代码
[TAndroidLame](https://github.com/naman14/TAndroidLame) Lame 编码使用了 TAndroidLame