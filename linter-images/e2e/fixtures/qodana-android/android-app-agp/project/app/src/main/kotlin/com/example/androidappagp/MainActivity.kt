package com.example.androidappagp

import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Planted defect: the empty catch swallows the exception (`e` unused, body empty) — a normal
        // code smell the shared qodana-jvm dist flags (CatchMayIgnoreException, pinned in jvm-maven-basic).
        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
        }
    }
}
