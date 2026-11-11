package com.unspektrawesome.language

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.unspektrawesome.MainActivity

class LanguageResetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.initialize(this)
        AppLanguage.useCustom(false)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
