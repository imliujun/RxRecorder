/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Piasy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.imliujun.recorder.mp3

import android.media.MediaPlayer
import androidx.annotation.WorkerThread
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

object RxAudioPlayer {
    
    /**
     * allow further customized manipulation.
     */
    var mediaPlayer: MediaPlayer? = null
        private set
    
    @Throws(IOException::class)
    private fun create(config: PlayConfig): MediaPlayer {
        stopPlay()
        
        val player: MediaPlayer
        when (config.mType) {
            PlayConfig.TYPE_URI -> {
                Timber.d("MediaPlayer to start play uri: %s", config.mUri)
                player = MediaPlayer()
                player.setDataSource(config.mContext, config.mUri)
                return player
            }
            PlayConfig.TYPE_FILE -> {
                Timber.d("MediaPlayer to start play file: %s", config.mAudioFile.name)
                player = MediaPlayer()
                player.setDataSource(config.mAudioFile.absolutePath)
                return player
            }
            PlayConfig.TYPE_RES -> {
                Timber.d("MediaPlayer to start play: %s", config.mAudioResource)
                player = MediaPlayer.create(config.mContext, config.mAudioResource)
                return player
            }
            PlayConfig.TYPE_URL -> {
                Timber.d("MediaPlayer to start play: %s", config.mUrl)
                player = MediaPlayer()
                player.setDataSource(config.mUrl)
                return player
            }
            else ->
                // can't happen, just fix checkstyle
                throw IllegalArgumentException("Unknown type: " + config.mType)
        }
    }
    
    /**
     * play audio from local file. should be scheduled in IO thread.
     */
    fun play(config: PlayConfig): Observable<Boolean> {
        return if (!config.isArgumentValid) {
            Observable.error(IllegalArgumentException(""))
        } else Observable.create<Boolean> { emitter ->
            val player = create(config)
            setMediaPlayerListener(player, emitter)
            player.setVolume(config.mLeftVolume, config.mRightVolume)
            player.setAudioStreamType(config.mStreamType)
            player.isLooping = config.mLooping
            if (config.needPrepare()) {
                player.prepare()
            }
            emitter.onNext(true)
            
            player.start()
            mediaPlayer = player
        }.doOnError { stopPlay() }
        
    }
    
    /**
     * prepare audio from local file. should be scheduled in IO thread.
     */
    fun prepare(config: PlayConfig): Observable<Boolean> {
        return if (!config.isArgumentValid || !config.isLocalSource) {
            Observable.error(IllegalArgumentException(""))
        } else Observable.create<Boolean> { emitter ->
            val player = create(config)
            setMediaPlayerListener(player, emitter)
            player.setVolume(config.mLeftVolume, config.mRightVolume)
            player.setAudioStreamType(config.mStreamType)
            player.isLooping = config.mLooping
            if (config.needPrepare()) {
                player.prepare()
            }
            emitter.onNext(true)
            
            mediaPlayer = player
        }.doOnError { e -> stopPlay() }
        
    }
    
    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                }
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
    
    fun resume() {
        try {
            mediaPlayer?.start()
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
    
    /**
     * Non reactive API.
     */
    @WorkerThread
    fun playNonRxy(config: PlayConfig,
                   onCompletionListener: MediaPlayer.OnCompletionListener,
                   onErrorListener: MediaPlayer.OnErrorListener): Boolean {
        if (!config.isArgumentValid) {
            return false
        }
        
        try {
            val player = create(config)
            setMediaPlayerListener(player, onCompletionListener, onErrorListener)
            player.setVolume(config.mLeftVolume, config.mRightVolume)
            player.setAudioStreamType(config.mStreamType)
            player.isLooping = config.mLooping
            if (config.needPrepare()) {
                player.prepare()
            }
            player.start()
            
            mediaPlayer = player
            return true
        } catch (e: RuntimeException) {
            Timber.e(e)
            stopPlay()
            return false
        } catch (e: IOException) {
            Timber.e(e)
            stopPlay()
            return false
        }
        
    }
    
    @Synchronized
    fun stopPlay(): Boolean {
        if (mediaPlayer == null) {
            return false
        }
        
        try {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.setOnErrorListener(null)
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (e: IllegalStateException) {
            Timber.e(e)
        } finally {
            mediaPlayer = null
        }
        
        return true
    }
    
    fun progress(): Int {
        return if (mediaPlayer != null) {
            mediaPlayer!!.currentPosition / 1000
        } else 0
    }
    
    private fun setMediaPlayerListener(player: MediaPlayer,
                                       emitter: ObservableEmitter<Boolean>) {
        player.setOnCompletionListener { _ ->
            Timber.d("OnCompletionListener::onCompletion")
            
            // could not call stopPlay immediately, otherwise the second sound
            // could not play, thus no complete notification
            // TODO discover why?
            Observable.timer(50, TimeUnit.MILLISECONDS).subscribe({
                stopPlay()
                emitter.onComplete()
            }, {
                if (!emitter.isDisposed) {
                    emitter.onError(it)
                }
            })
        }
        player.setOnErrorListener { _, what, extra ->
            Timber.d("OnErrorListener::onError$what, $extra")
            if (!emitter.isDisposed) {
                emitter.onError(Throwable("Player error: $what, $extra"))
            }
            stopPlay()
            true
        }
    }
    
    private fun setMediaPlayerListener(player: MediaPlayer,
                                       onCompletionListener: MediaPlayer.OnCompletionListener,
                                       onErrorListener: MediaPlayer.OnErrorListener) {
        player.setOnCompletionListener { mp ->
            Timber.d("OnCompletionListener::onCompletion")
            
            // could not call stopPlay immediately, otherwise the second sound
            // could not play, thus no complete notification
            // TODO discover why?
            Observable.timer(50, TimeUnit.MILLISECONDS).subscribe({
                stopPlay()
                onCompletionListener.onCompletion(mp)
            }) { Timber.d("OnCompletionListener::onError,%s ", it.message) }
        }
        player.setOnErrorListener { mp, what, extra ->
            Timber.d("OnErrorListener::onError$what, $extra")
            onErrorListener.onError(mp, what, extra)
            stopPlay()
            true
        }
    }
    
    
}
