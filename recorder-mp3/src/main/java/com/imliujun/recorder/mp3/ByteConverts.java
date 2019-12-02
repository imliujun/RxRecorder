package com.imliujun.recorder.mp3;


import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

import timber.log.Timber;

/**
 * 项目名称：moli
 * 类描述：
 * 创建人：liujun
 * 创建时间：2018/4/28 11:48
 * 修改人：liujun
 * 修改时间：2018/4/28 11:48
 * 修改备注：
 */
public final class ByteConverts {
    
    /**
     * ByteBuffer 转为 short[]
     *
     * @param bufferSize 需要转换的 Byte 长度
     */
    public static void byteToShorts(@NonNull @NotNull ByteBuffer buffer, int bufferSize, @NonNull @NotNull short[] shorts) {
        int size = bufferSize / 2;
        for (int i = 0; i < size; i++) {
            shorts[i] = (short) (buffer.get(i * 2 + 1) << 8 | buffer.get(i * 2) & 0xFF);
        }
    }
    
    /**
     * short[] 转为 byte[]
     *
     * @param size 需要转换的 short 长度
     */
    public static void shortToByte(@NonNull @NotNull short[] shorts, int size, @NonNull @NotNull byte[] bytes) {
        if (size > shorts.length || size * 2 > bytes.length) {
            Timber.w("short2byte: too long short data array");
        }
        for (int i = 0; i < size; i++) {
            bytes[i * 2] = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xff);
        }
    }
    
    /**
     * byte 转为 int
     *
     * @param buffer 最少要有4个 byte
     */
    public static int byteBufferToInt(@NonNull @NotNull ByteBuffer buffer) {
        return buffer.get(0) & 0xFF |
            (buffer.get(1) & 0xFF) << 8 |
            (buffer.get(2) & 0xFF) << 16 |
            (buffer.get(3) & 0xFF) << 24;
    }
}
