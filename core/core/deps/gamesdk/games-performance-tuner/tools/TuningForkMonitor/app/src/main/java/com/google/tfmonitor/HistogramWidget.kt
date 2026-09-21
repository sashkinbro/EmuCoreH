/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.google.tfmonitor

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import android.os.Build
import android.view.View
import java.util.concurrent.atomic.AtomicInteger

class HistogramWidget : ConstraintLayout {
    companion object {
        private const val DEFAULT_HEIGHT_DP = 1
    }

    // All the bars, id to view map. Alternatively we can use findViewById.
    private val bars: MutableMap<Int, TextView> = HashMap()

    // Currently state of the bars
    private var currentBars: MutableList<HistogramBarMetaData> = ArrayList()

    // Default left margin in dp
    private var leftMarginDp = 0

    // Weight to use for the horizontal chain (of bars)
    private var weights: FloatArray

    /**
     * The list of View ids of the bar in the histogram in order.
     */
    val barIds: List<Int> get() = currentBars.map { it.id }

    constructor(context: Context, attrs: AttributeSet?): this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int):
            super(context, attrs, defStyleAttr) {
        val array = context.theme.obtainStyledAttributes(
            attrs, R.styleable.HistogramWidget, 0, 0)
        try {
            leftMarginDp = array.getInt(R.styleable.HistogramWidget_leftMarginDp, 0)
        } finally {
            array.recycle()
        }
        weights = FloatArray(0)
    }

    /**
     * Sets data to the widget.
     */
    fun setData(newData: List<HistogramBarMetaData>) {
        createBars(this, newData)
    }

    /**
     * Update the constraint set with the bar metadata.
     */
    private fun updateConstraintSet(
        set: ConstraintSet) {
        for (i in 0 until currentBars.size) {
            var metadata = currentBars[i]
            val view = bars[metadata.id]!!
            val height: Float = metadata.height*height

            set.constrainHeight(view.id, height.toInt())
        }
    }

    /**
     * Programmatically create views that represent histogram bars
     */
    private fun createBars(layout: ConstraintLayout, data: List<HistogramBarMetaData>) {
        var columns = data.size
        if (columns <= 1) {
            return
        }

        val set = ConstraintSet()
        set.clone(layout)


        if (!currentBars.isEmpty()) {
            currentBars.clear()
            for (b in bars) {
                layout.removeView(b.value)
                set.clear(b.key)
            }
            bars.clear()
        }

        weights = FloatArray(columns)

        val marginInDp = fromDp(context, leftMarginDp)
        val size = fromDp(context, DEFAULT_HEIGHT_DP)

        for (i in 0 until columns) {
            val bar = createBar(layout.context)
            val barColour = data[i].barColour

            // Initialize
            bar.background = ColorDrawable(barColour)
            val layoutParams = LayoutParams(size, size)
            layout.addView(bar, layoutParams)
            set.constrainHeight(bar.id, (data[i].height*height).toInt())
            set.connect(
                bar.id,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM)
            set.setMargin(bar.id, ConstraintSet.END, marginInDp)
            weights[i] = 1f

            // Create the currentBars list to best mimic the initial state.
            currentBars.add(HistogramBarMetaData(bar.id, data[i].height, barColour))
            bars[bar.id] = bar
        }
        set.createHorizontalChain(
            ConstraintSet.PARENT_ID, ConstraintSet.LEFT,
            ConstraintSet.PARENT_ID, ConstraintSet.RIGHT,
            barIds.toIntArray(), weights, ConstraintSet.CHAIN_SPREAD
        )
        set.applyTo(layout)
    }

    /**
     * Create a single bar.
     */
    private fun createBar(context: Context): TextView {
        val bar = TextView(context)
        bar.id = myGenerateViewId()
        bar.gravity = Gravity.CENTER
        return bar
    }

    /**
     * Get px from dp
     */
    private fun fromDp(context: Context, inDp: Int): Int {
        val scale = context.resources.displayMetrics.density
        return (inDp * scale).toInt()
    }

    private val sNextGeneratedId = AtomicInteger(
        1
    )

    /**
     * While [View.generateViewId] require API Level >= 17, this tool is compatibe with all API.
     *
     * According to current API Level, it decide weather using system API or not.
     * So you can use [ ][.generateViewId] and [View.generateViewId] in the
     * same time and don't worry about getting same id.
     *
     * @return Id
     */
    private fun myGenerateViewId(): Int {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            generateCompatViewId()
        } else {
            View.generateViewId()
        }
    }

    /**
     * Generate a value suitable for use in [View.setId]
     * This value will not collide with ID values generated at build time by aapt for R.id.
     *
     * @return a generated ID value
     */
    private fun generateCompatViewId(): Int {
        while (true) {
            val result = sNextGeneratedId.get()
            var newValue = result + 1
            /**
             * ID number larger than 0x00FFFFFF is reserved for static views defined in the /res xml
             * files.
             * Android doesn't want you to use 0 as a view's id, and it needs to be flipped before
             * 0x01000000 to avoid the conflicts with static resource IDs
             */
            if (newValue > 0x00FFFFFF) {
                newValue = 1 // Roll over to 1, not 0.
            }
            if (sNextGeneratedId.compareAndSet(result, newValue)) {
                return result
            }
        }
    }
}