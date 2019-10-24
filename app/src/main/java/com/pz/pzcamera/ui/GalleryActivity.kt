package com.pz.pzcamera.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pz.pzcamera.R

val EXTENSION_WHITELIST = arrayOf("JPG")

class GalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
    }
}
