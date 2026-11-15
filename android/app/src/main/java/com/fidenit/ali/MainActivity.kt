package com.fidenit.ali

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.fidenit.ali.ui.AliApp

class MainActivity : ComponentActivity() {
    private val vm: AliViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AliApp(vm) }
    }
}
