/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.renderscript_test

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.renderscript.RenderScript
import com.google.android.renderscript.Range2d
import com.google.android.renderscript.Toolkit
import kotlin.math.abs
import kotlin.math.min

data class TestLayout(
    val sizeX: Int,
    val sizeY: Int,
    val restriction: Range2d?
)

// List of dimensions (sizeX, sizeY) to try when generating random data.
val commonLayoutsToTry = listOf(
    // Small layouts to start with
    TestLayout(3, 4, null),
    TestLayout(3, 4, Range2d(0, 1, 0, 3)),
    TestLayout(3, 4, Range2d(2, 3, 1, 4)),
    // The size of most of the original RenderScript Intrinsic tests
    TestLayout(160, 100, null),
    /* Other tests, if you're patient:
    TestLayout(10, 14, null),
    TestLayout(10, 14, Range2d(2, 3, 8, 14)),
    TestLayout(125, 227, Range2d(50, 125, 100, 227)),
    TestLayout(800, 600, null),
    // Weirdly shaped ones
    TestLayout(1, 1, null), // A single item
    estLayout(16000, 1, null), // A single item
    TestLayout(1, 16000, null), // One large row
    // A very large test
    TestLayout(1024, 2048, null),
    */
)


class Tester(context: Context, private val validate: Boolean) {
    private val renderscriptContext = RenderScript.create(context)
    private val testImage1 = BitmapFactory.decodeResource(context.resources, R.drawable.img800x450a)
    private val testImage2 = BitmapFactory.decodeResource(context.resources, R.drawable.img800x450b)

    init {
        validateTestImage(testImage1)
        validateTestImage(testImage2)
    }

    /**
     * Verify that the test images are in format that works for our tests.
     */
    private fun validateTestImage(bitmap: Bitmap) {
        require(bitmap.config == Bitmap.Config.ARGB_8888)
        require(bitmap.rowBytes == bitmap.width * 4) {
            "Can't handle bitmaps that have extra padding. " +
                "${bitmap.rowBytes} != ${bitmap.width} * 4." }
        require(bitmap.byteCount == bitmap.rowBytes * bitmap.height)
    }

    fun destroy() {
        renderscriptContext.destroy()
    }

    @ExperimentalUnsignedTypes
    fun testAll(timer: TimingTracker): String {
        val tests  = listOf(
            Pair("blur", ::testBlur),
        )
        val results = Array(tests.size) { "" }
        for (i in tests.indices) {
            val (name, test) = tests[i]
            println("Doing $name")
            val success = test(timer)
            results[i] = "$name " + if (success) "succeeded" else "FAILED! FAILED! FAILED! FAILED!"
            println("      ${results[i]}")
        }

        return results.joinToString("\n")
    }

    @ExperimentalUnsignedTypes
    private fun testBlur(timer: TimingTracker): Boolean {
        return arrayOf(1, 3, 8, 25).all { radius ->
            testOneBitmapBlur(timer, testImage1, radius, null) and
                    testOneBitmapBlur(timer, testImage1, radius, Range2d(6, 23, 2, 4)) and
                    commonLayoutsToTry.all { (sizeX, sizeY, restriction) ->
                        arrayOf(1, 4).all { vectorSize ->
                            testOneRandomBlur(timer, vectorSize, sizeX, sizeY, radius, restriction)
                        }
                    }
        }
    }

    @ExperimentalUnsignedTypes
    private fun testOneRandomBlur(
        timer: TimingTracker,
        vectorSize: Int,
        sizeX: Int,
        sizeY: Int,
        radius: Int,
        restriction: Range2d?
    ): Boolean {
        val inputArray = randomByteArray(0x50521f0, sizeX, sizeY, vectorSize)
        val intrinsicOutArray = timer.measure("IntrinsicBlur") {
            intrinsicBlur(
                renderscriptContext, inputArray, vectorSize, sizeX, sizeY, radius, restriction
            )
        }
        val toolkitOutArray = timer.measure("ToolkitBlur") {
            Toolkit.blur(inputArray, vectorSize, sizeX, sizeY, radius, restriction)
        }
        if (!validate) return true

        val referenceOutArray = timer.measure("ReferenceBlur") {
            referenceBlur(inputArray, vectorSize, sizeX, sizeY, radius, restriction)
        }
        return validateSame("blur", intrinsicOutArray, referenceOutArray, toolkitOutArray) {
            println("blur $vectorSize ($sizeX, $sizeY) radius = $radius $restriction")
            logArray("blur input        ", inputArray)
            logArray("blur reference out", referenceOutArray)
            logArray("blur intrinsic out", intrinsicOutArray)
            logArray("blur toolkit   out", toolkitOutArray)
        }
    }

    @ExperimentalUnsignedTypes
    private fun testOneBitmapBlur(
        timer: TimingTracker,
        bitmap: Bitmap,
        radius: Int,
        restriction: Range2d?
    ): Boolean {
        val intrinsicOutArray = timer.measure("IntrinsicBlur") {
            intrinsicBlur(renderscriptContext, bitmap, radius, restriction)
        }

        val toolkitOutBitmap = timer.measure("ToolkitBlur") {
            Toolkit.blur(bitmap, radius, restriction)
        }
        if (!validate) return true

        val referenceOutArray = timer.measure("ReferenceBlur") {
            referenceBlur(
                getBitmapBytes(bitmap),
                vectorSizeOfBitmap(bitmap),
                bitmap.width,
                bitmap.height,
                radius,
                restriction
            )
        }

        val toolkitOutArray = getBitmapBytes(toolkitOutBitmap)
        return validateSame("blur", intrinsicOutArray, referenceOutArray, toolkitOutArray) {
            println("BlurBitmap ${bitmap.config} $radius $restriction")
            logArray("blur reference out", referenceOutArray)
            logArray("blur intrinsic out", intrinsicOutArray)
            logArray("blur toolkit   out", toolkitOutArray)
        }
    }

    enum class ColorMatrixConversionType {
        RGB_TO_YUV,
        YUV_TO_RGB,
        GREYSCALE,
        RANDOM
    }

    /**
     * Verifies that the arrays returned by the Intrinsic, the reference code, and the Toolkit
     * are all within a margin of error.
     *
     * RenderScript Intrinsic test (rc/android/cts/rscpp/RSCppTest.java) used 3 for ints.
     * For floats, rc/android/cts/rscpp/verify.rscript uses 0.0001f.
     */
    @ExperimentalUnsignedTypes
    private fun validateSame(
        task: String,
        intrinsic: ByteArray,
        reference: ByteArray,
        toolkit: ByteArray,
        skipFourth: Boolean = false,
        allowedIntDelta: Int = 3,
        errorLogging: () -> Unit
    ): Boolean {
        val success = validateAgainstReference(
            task, reference, "Intrinsic", intrinsic, skipFourth, allowedIntDelta
        ) and validateAgainstReference(
            task, reference, "Toolkit", toolkit, skipFourth, allowedIntDelta
        )
        if (!success) {
            println("$task FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!")
            errorLogging()
        }
        return success
    }

    private fun validateSame(
        task: String,
        intrinsic: IntArray,
        reference: IntArray,
        toolkit: IntArray,
        allowedIntDelta: Int = 3,
        errorLogging: () -> Unit
    ): Boolean {
        val success = validateAgainstReference(
            task, reference, "Intrinsic", intrinsic, allowedIntDelta
        ) and validateAgainstReference(
            task, reference, "Toolkit", toolkit, allowedIntDelta
        )
        if (!success) {
            println("$task FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!FAIL!")
            errorLogging()
        }
        return success
    }

    @ExperimentalUnsignedTypes
    private fun validateAgainstReference(
        task: String,
        in1: ByteArray,
        name2: String,
        in2: ByteArray,
        skipFourth: Boolean,
        allowedIntDelta: Int
    ): Boolean {
        if (in1.size != in2.size) {
            println("$task. Sizes don't match: Reference ${in1.size}, $name2 ${in2.size}")
            return false
        }
        var same = true
        val maxDetails = 80
        val diffs = CharArray(min(in1.size, maxDetails)) {'.'}
        for (i in in1.indices) {
            if (skipFourth && i % 4 == 3) {
                continue
            }
            val delta = abs(in1[i].toUByte().toInt() - in2[i].toUByte().toInt())
            if (delta > allowedIntDelta) {
                if (same) {
                    println(
                        "$task. At $i, Reference is ${in1[i].toUByte()}, $name2 is ${in2[i].toUByte()}"
                    )
                }
                if (i < maxDetails) diffs[i] = 'X'
                same = false
            }
        }
        if (!same) {
            for (i in 0 until (min(in1.size, maxDetails) / 4)) print("%-3d|".format(i))
            println()
            println(diffs)
        }
        return same
    }

    private fun validateAgainstReference(
        task: String,
        in1: IntArray,
        name2: String,
        in2: IntArray,
        allowedIntDelta: Int
    ): Boolean {
        if (in1.size != in2.size) {
            println("$task. Sizes don't match: Reference ${in1.size}, $name2 ${in2.size}")
            return false
        }
        for (i in in1.indices) {
            val delta = abs(in1[i] - in2[i])
            if (delta > allowedIntDelta) {
                println("$task. At $i, Reference is ${in1[i]}, $name2 is ${in2[i]}")
                return false
            }
        }
        return true
    }
}
