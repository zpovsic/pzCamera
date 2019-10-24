package com.pz.pzcamera.utils

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pz.pzcamera.R
import com.pz.pzcamera.R.layout

class MessageView @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {
    private val message: TextView
    private val title: TextView
    fun setTitleAndMessage(title: String, message: String) {
        setTitle(title)
        setMessage(message)
    }

    fun setTitle(title: String) {
        this.title.text = title
    }

    fun setMessage(message: String) {
        this.message.text = message
    }

    init {
        orientation = VERTICAL
        View.inflate(context, layout.option_view, this)
        val content: ViewGroup = findViewById(R.id.content)
        View.inflate(context, layout.spinner_text, content)
        title = findViewById(R.id.title)
        message = content.getChildAt(0) as TextView
    }
}