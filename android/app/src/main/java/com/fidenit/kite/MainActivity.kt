package com.fidenit.kite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.fidenit.kite.ui.KiteApp

class MainActivity : ComponentActivity() {
    private val vm: KiteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KiteApp(vm) }
    }
}
