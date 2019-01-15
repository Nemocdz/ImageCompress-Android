package com.nemocdz.imagecompress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.bumptech.glide.Glide
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import com.bumptech.glide.load.resource.gif.GifBitmapProvider
import java.io.*
import kotlin.math.max
import kotlin.math.min

object ImageCompress {
    /**
     * 返回同步压缩图片 Byte 数据 [rawData] 的长边到 [limitLongWidth] 后的 Byte 数据，Gif 目标长边最大压缩到 512，超过用 512
     */
    fun compressImageDataWithLongWidth(context: Context, rawData:ByteArray, limitLongWidth: Int):ByteArray?{
        val format = rawData.imageFormat()
        if (format == ImageFormat.UNKNOWN){
            return null
        }

        val (imageWidth, imageHeight) = rawData.imageSize()
        val longSideWidth = max(imageWidth, imageHeight)

        if (longSideWidth <= limitLongWidth) {
            return rawData
        }

        if (format == ImageFormat.GIF) {
            // 压缩 Gif 分辨率太大编码时容易崩溃
            return compressGifDataWithLongWidth(context, rawData, if(limitLongWidth < 512) limitLongWidth else 512)
        } else {
            val image = BitmapFactory.decodeByteArray(rawData,0,rawData.count())
            val ratio = limitLongWidth.toDouble() / longSideWidth.toDouble()
            val resizeImageFrame = Bitmap.createScaledBitmap(image, (image.width.toDouble() * ratio).toInt(), (image.height.toDouble() * ratio).toInt(), true)
            image.recycle()
            var resultData:ByteArray? = null
            when (format){
                ImageFormat.PNG -> {
                    resultData = resizeImageFrame.toByteArray(Bitmap.CompressFormat.PNG)
                }
                ImageFormat.JPG -> {
                    resultData = resizeImageFrame.toByteArray(Bitmap.CompressFormat.JPEG)
                }
                else -> {}
            }
            resizeImageFrame.recycle()
            return resultData
        }
    }


    /**
     * 返回同步压缩图片 Byte 数据 [rawData] 的数据大小到 [limitDataSize] 后的 Byte 数据
     */
    fun compressImageDataWithSize(context: Context, rawData: ByteArray, limitDataSize:Int):ByteArray?{
        if (rawData.count() <= limitDataSize){
            return rawData
        }

        val format = rawData.imageFormat()
        if (format == ImageFormat.UNKNOWN){
            return null
        }

        var resultData = rawData

        // 若是 GIF，只用抽帧减少大小
        if (format == ImageFormat.GIF){
            var sampleCount = resultData.fitSampleCount()
            while (resultData.count() > limitDataSize){
                val data = compressGifDataWithSampleCount(context, resultData, sampleCount)
                if (data != null){
                    resultData = data
                } else {
                    return null
                }
                sampleCount = 2
            }
        } else {
            // 若是 JPG，先用压缩系数压缩 6 次，二分法
            if (format == ImageFormat.JPG){
                var compression = 100
                var maxCompression = 100
                var minCompression = 0

                try {
                    val outputStream = ByteArrayOutputStream()
                    for (index in 0..6){
                        compression = (maxCompression + minCompression) / 2
                        outputStream.reset()
                        val image = BitmapFactory.decodeByteArray(rawData,0,rawData.count())
                        image.compress(Bitmap.CompressFormat.JPEG, compression, outputStream)
                        image.recycle()
                        resultData = outputStream.toByteArray()
                        if (resultData.count() < (limitDataSize.toDouble() * 0.9).toInt()){
                            minCompression = compression
                        } else if (resultData.count() > limitDataSize) {
                            maxCompression = compression
                        } else {
                            break
                        }
                    }
                    outputStream.close()
                } catch (e:IOException){
                    e.printStackTrace()
                }

                if (resultData.count() <= limitDataSize){
                    return resultData
                }
            }

            val (imageWidth, imageHeight) = resultData.imageSize()
            var longSideWidth = max(imageWidth, imageHeight)

            // 图片尺寸按比率缩小，比率按字节比例逼近
            while (resultData.count() > limitDataSize){
                val ratio = Math.sqrt(limitDataSize.toDouble() / resultData.count().toDouble())
                longSideWidth = (longSideWidth.toDouble() * ratio).toInt()
                val data = compressImageDataWithLongWidth(context, resultData, longSideWidth)
                if (data != null){
                    resultData = data
                } else {
                    return null
                }
            }
        }
        return resultData
    }


    /**
     * 返回同步压缩 gif 图片 Byte 数据 [rawData] 每一帧长边到 [limitLongWidth] 后的 Byte 数据
     */
    private fun compressGifDataWithLongWidth(context: Context, rawData:ByteArray, limitLongWidth:Int):ByteArray?{
        val gifDecoder = StandardGifDecoder(GifBitmapProvider(Glide.get(context).bitmapPool))
        val headerParser = GifHeaderParser()
        headerParser.setData(rawData)
        val header = headerParser.parseHeader()
        gifDecoder.setData(header, rawData)
        val frameCount = gifDecoder.frameCount

        // 计算帧的间隔
        val frameDurations = (0..(frameCount - 1)).map { gifDecoder.getDelay(it) }

        // 计算调整后大小
        val longSideWidth = max(header.width, header.height)
        val ratio =  limitLongWidth.toFloat() / longSideWidth.toFloat()
        val resizeWidth = (header.width.toFloat() * ratio).toInt()
        val resizeHeight = (header.height.toFloat() * ratio).toInt()

        // 每一帧进行缩放
        val resizeImageFrames = (0 until frameCount).mapNotNull{
            gifDecoder.advance()
            var imageFrame = gifDecoder.nextFrame
            if (imageFrame != null) {
               imageFrame = Bitmap.createScaledBitmap(imageFrame, resizeWidth, resizeHeight, true)
            }
            imageFrame
        }

        val gifEncoder = AnimatedGifEncoder()
        var resultData:ByteArray? = null

        try {
            val outputStream = ByteArrayOutputStream()
            gifEncoder.start(outputStream)
            gifEncoder.setRepeat(0)

            // 每一帧都进行重新编码
            resizeImageFrames.zip(frameDurations).forEach{
                // 设置帧间隔
                gifEncoder.setDelay(it.second)
                gifEncoder.addFrame(it.first)
                it.first.recycle()
            }

            gifEncoder.finish()

            resultData = outputStream.toByteArray()
            outputStream.close()
            return resultData
        } catch (e:IOException){
            e.printStackTrace()
        }
        return resultData
    }

    /**
     * 返回同步压缩 gif 图片 Byte 数据 [rawData] 的按 [sampleCount] 采样后的 Byte 数据
     */
    private fun compressGifDataWithSampleCount(context:Context, rawData:ByteArray, sampleCount:Int):ByteArray?{
        if (sampleCount <= 1){
            return rawData
        }
        val gifDecoder = StandardGifDecoder(GifBitmapProvider(Glide.get(context).bitmapPool))
        val headerParser = GifHeaderParser()
        headerParser.setData(rawData)
        val header = headerParser.parseHeader()
        gifDecoder.setData(header, rawData)

        val frameCount = gifDecoder.frameCount

        // 计算帧的间隔
        val frameDurations = (0 until frameCount).map { gifDecoder.getDelay(it) }

        // 合并帧的时间,最长不可高于 200ms
        val mergeFrameDurations = (0 until frameCount).filter { it % sampleCount == 0 }.map { min(frameDurations.subList(it, min(it + sampleCount, frameCount)).fold(0){ acc, duration -> acc + duration }, 200) }

        // 抽取帧
        val sampleImageFrames = (0 until frameCount).mapNotNull {
            gifDecoder.advance()
            var imageFrame:Bitmap? = null
            if (it % sampleCount == 0) {
                imageFrame = gifDecoder.nextFrame
            }
            imageFrame
        }

        val gifEncoder = AnimatedGifEncoder()

        var resultData:ByteArray? = null

        try {
            val outputStream = ByteArrayOutputStream()
            gifEncoder.start(outputStream)
            gifEncoder.setRepeat(0)

            // 每一帧图片都进行重新编码
            sampleImageFrames.zip(mergeFrameDurations).forEach{
                // 设置帧间隔
                gifEncoder.setDelay(it.second)
                gifEncoder.addFrame(it.first)
                it.first.recycle()
            }
            gifEncoder.finish()

            resultData = outputStream.toByteArray()
            outputStream.close()
        } catch (e:IOException){
            e.printStackTrace()
        }

        return resultData
    }
}

fun ByteArray.imageFormat(): ImageFormat {
    val headerData = this.slice(0..2)
    val hexString = headerData.fold(StringBuilder("")) { result, byte -> result.append( (byte.toInt() and 0xFF).toString(16) ) }.toString().toUpperCase()
    var imageFormat = ImageFormat.UNKNOWN
    when (hexString) {
        "FFD8FF" -> {
            imageFormat = ImageFormat.JPG
        }
        "89504E" -> {
            imageFormat = ImageFormat.PNG
        }
        "474946" -> {
            imageFormat = ImageFormat.GIF
        }
    }
    return imageFormat
}


fun Bitmap.toByteArray(format: Bitmap.CompressFormat): ByteArray? {
    var data:ByteArray? = null
    try {
        val outputStream = ByteArrayOutputStream()
        this.compress(format, 100, outputStream)
        data = outputStream.toByteArray()
        outputStream.close()
        return data
    } catch (e:IOException){
        e.printStackTrace()
    }
    return data
}

private fun ByteArray.imageSize():Pair<Int,Int>{
    var imageWidth = 0
    var imageHeight = 0

    if (this.imageFormat() == ImageFormat.GIF){
        val headerParser = GifHeaderParser()
        headerParser.setData(this)
        val header = headerParser.parseHeader()
        imageWidth = header.width
        imageHeight = header.height
    } else {
        try {
            val imageFrame = BitmapFactory.decodeByteArray(this,0,this.count())
            imageWidth = imageFrame.width
            imageHeight = imageFrame.height
            imageFrame.recycle()
        } catch (e:Throwable){
            e.printStackTrace()
        }
    }
    return Pair(imageWidth, imageHeight)
}

private fun ByteArray.fitSampleCount(): Int {
    val headerParser = GifHeaderParser()
    headerParser.setData(this)
    val header = headerParser.parseHeader()
    val frameCount = header.numFrames
    var sampleCount = 2
    when (frameCount) {
        in 0..7 -> {
            sampleCount = 2
        }
        in 8..19 -> {
            sampleCount = 3
        }
        in 20..29 -> {
            sampleCount = 4
        }
        in 30..39 -> {
            sampleCount = 5
        }
        else -> {
            sampleCount = 6
        }
    }
    return sampleCount
}

enum class ImageFormat{
    JPG, PNG, GIF, UNKNOWN
}


