package com.voicebot.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightDp: Int = 300

    private val maxHeightPx: Int
        get() = (maxHeightDp * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val constrainedHeight = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, constrainedHeight)
    }
}
