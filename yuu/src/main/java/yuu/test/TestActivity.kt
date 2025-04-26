package yuu.test

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * @Author      : yuu
 * @Date        : 2025-04-26
 * @Description :
 */
class TestActivity: AppCompatActivity() {
    private val TAG = "yuu"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

    }
}