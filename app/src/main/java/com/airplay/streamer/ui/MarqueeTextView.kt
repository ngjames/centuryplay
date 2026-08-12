package com.airplay.streamer.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A marquee TextView that drives its scroll animation from the selected state
 * instead of focus. On Android TV a focus-based marquee would steal D-pad
 * focus from the media card, so this view stays non-focusable and runs the
 * ticker via [isSelected] (the TextView marquee restarts whenever the view is
 * selected while ellipsizing with marquee).
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        isSelected = true
    }
}
